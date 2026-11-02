package eu.kanade.tachiyomi.takami.fetch

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Краткий ответ на HEAD: код, итоговый адрес и заголовки. Тела здесь
 * нет.
 */
data class ResponseInfo(
    val code: Int,
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

/**
  * Готовая страница: текст, конечный адрес после редиректов и
  * кодировка.
 *
  * Конечный адрес важен не меньше текста: от него считаются
  * относительные ссылки,
 * а без него карточки каталога ведут в несуществующие пути.
 */
data class FetchedPage(
    val html: String,
    val url: String,
    val charset: String,
    val code: Int,
    val headers: Map<String, List<String>> = emptyMap(),
)

/**
 * Единственная точка HTTP для виртуальных источников.
 *
  * Наружу не отдаёт [OkHttpClient]: иначе лимиты и кэш можно обойти,
  * создав свой
 * клиент. Внутри — пул соединений, gzip, HTTP/2, cookie-jar и таймауты 20/20 с.
 */
class Fetcher internal constructor(
    private val client: OkHttpClient,
    private val guard: HostGuard,
    private val cache: HttpCache? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<FetchedPage>>()
    private val memory = ConcurrentHashMap<String, Remembered>()

    suspend fun get(
        url: String,
        referer: String? = null,
        priority: FetchPriority = FetchPriority.INTERACTIVE,
    ): String = page(url, referer, priority).html

    /**
     * Страница целиком: память, затем диск, затем сеть.
     *
      * Одинаковые одновременные запросы склеиваются в один, свежая
      * запись с диска
      * отдаётся без обращения к сайту, просроченная проверяется
      * условным запросом.
     */
    suspend fun page(
        url: String,
        referer: String? = null,
        priority: FetchPriority = FetchPriority.INTERACTIVE,
    ): FetchedPage {
        currentCoroutineContext().ensureActive()
        val key = keyOf(url, referer)
        val at = guard.now()

        memory[key]?.let { entry ->
            if (at - entry.at < FetchLimits.MEMORY_TTL_MS) return entry.page
            memory.remove(key, entry)
        }

        val store = cache
        val cached = store?.lookup(key, at)
        if (cached != null && store.isFresh(cached.entry, at)) {
            remember(key, cached.page, at)
            return cached.page
        }

        val slot = CompletableDeferred<FetchedPage>()
        inFlight.putIfAbsent(key, slot)?.let { return it.await() }

        // Загрузку ведём вне корутины экрана: если он закроется,
        // запрос всё равно
        // дойдёт до конца, ответ ляжет в кэш и не пропадёт впустую.
        scope.launch {
            try {
                val loaded = fetchWithCache(url, referer, priority, key, cached)
                remember(key, loaded, guard.now())
                slot.complete(loaded)
            } catch (e: Throwable) {
                slot.completeExceptionally(e)
            } finally {
                inFlight.remove(key, slot)
            }
        }
        return slot.await()
    }

    /**
     * Один проход к сайту с учётом сохранённой записи.
     *
      * Порядок разбора: «не изменилось» — берём диск; страница без
      * метки версии, но
      * с тем же отпечатком — тоже диск; иначе сохраняем новое. Если
      * сайт недоступен,
     * отдаём последнее сохранённое, чтобы экран не остался пустым.
     */
    private suspend fun fetchWithCache(
        url: String,
        referer: String?,
        priority: FetchPriority,
        key: String,
        cached: CachedHit?,
    ): FetchedPage {
        val store = cache
        val conditional = Conditional.headers(cached?.entry)

        val loaded = try {
            load(url, referer, priority, conditional)
        } catch (e: IOException) {
            if (cached != null) return cached.page
            throw e
        }

        if (loaded.notModified) {
            val hit = cached ?: throw HttpStatusError(Conditional.NOT_MODIFIED, url)
            store?.confirm(key, guard.now(), loaded.etag, loaded.lastModified)
            return hit.page
        }

        val page = loaded.page ?: throw HttpStatusError(loaded.code, url)
        if (store == null) return page

        val unchanged = cached != null &&
            loaded.etag == null &&
            loaded.bytes != null &&
            Conditional.sameContent(cached.entry, loaded.bytes)
        if (unchanged) {
            store.confirm(key, guard.now(), null, null)
            return cached.page
        }

        store.store(
            key = key,
            entry = CacheEntry(
                url = page.url,
                referer = referer ?: "",
                etag = loaded.etag,
                lastModified = loaded.lastModified,
                fetchedAt = guard.now(),
                ttlMs = store.ttlFor(hostOf(url)),
                charset = page.charset,
            ),
            html = page.html,
        )
        return page
    }

    suspend fun head(url: String): ResponseInfo {
        var current = url
        var hops = 0
        while (true) {
            val raw = send(current, null, FetchPriority.INTERACTIVE, head = true)
            if (raw.code !in REDIRECTS) return ResponseInfo(raw.code, raw.url, raw.headers.toMultimap())
            val next = nextHop(current, raw, url)
            if (++hops > FetchLimits.MAX_REDIRECTS) throw TooManyRedirectsError(url)
            current = next
        }
    }

    suspend fun post(url: String, body: String, referer: String? = null): String {
        currentCoroutineContext().ensureActive()
        val raw = send(url, referer, FetchPriority.INTERACTIVE, post = body)
        return ResponseGate.check(raw.contentType, raw.bytes, raw.url).html
    }

    /**
      * Первые байты медиа: проверяем, что ссылка отдаёт поток, а не
      * страницу.
      * Читаем не больше [FetchLimits.PROBE_BYTES]: сервер вправе
      * проигнорировать
     * Range, и тогда без ограничения мы бы скачали весь файл.
     */
    suspend fun probe(url: String, referer: String? = null): String {
        currentCoroutineContext().ensureActive()
        val host = hostOf(url)
        return guard.run(host) {
            currentCoroutineContext().ensureActive()
            val request = request(url, referer)
                .header("Range", "bytes=0-" + (FetchLimits.PROBE_BYTES - 1))
                .build()
            val response = execute(request, host)
            response.use { received ->
                val code = received.code
                if (code !in 200..299) throw HttpStatusError(code, url)
                val source = received.body.source()
                val buffer = Buffer()
                while (buffer.size < FetchLimits.PROBE_BYTES) {
                    if (source.read(buffer, FetchLimits.PROBE_BYTES - buffer.size) == -1L) break
                }
                buffer.readByteArray().toString(Charsets.ISO_8859_1)
            }
        }
    }

    /**
     * Цепочка редиректов разворачивается по одному запросу: каждый
     * — под лимитами.
     */
    private suspend fun load(
        url: String,
        referer: String?,
        priority: FetchPriority,
        conditional: Map<String, String> = emptyMap(),
    ): Loaded {
        var current = url
        var ref = referer
        var hops = 0
        while (true) {
            // Условные заголовки относятся к исходному адресу: после
            // перехода
            // метка версии уже от другой страницы и только запутает
            // сайт.
            val extra = if (hops == 0) conditional else emptyMap()
            val raw = send(current, ref, priority, headers = extra)
            val etag = raw.headers["ETag"]?.firstOrNull()
            val lastModified = raw.headers["Last-Modified"]?.firstOrNull()

            if (raw.code !in REDIRECTS) {
                if (Conditional.isNotModified(raw.code)) {
                    return Loaded(
                        page = null,
                        notModified = true,
                        code = raw.code,
                        etag = etag,
                        lastModified = lastModified,
                    )
                }
                val gated = ResponseGate.check(raw.contentType, raw.bytes, raw.url)
                val page = FetchedPage(
                    html = gated.html,
                    url = raw.url,
                    charset = gated.charset,
                    code = raw.code,
                    headers = raw.headers.toMultimap(),
                )
                return Loaded(
                    page = page,
                    notModified = false,
                    code = raw.code,
                    etag = etag,
                    lastModified = lastModified,
                    bytes = raw.bytes,
                )
            }

            if (hops >= FetchLimits.MAX_REDIRECTS) throw TooManyRedirectsError(url)
            val next = nextHop(current, raw, url)
            hops++
            ref = current
            current = next
        }
    }

    private suspend fun send(
        url: String,
        referer: String?,
        priority: FetchPriority,
        head: Boolean = false,
        post: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Raw {
        currentCoroutineContext().ensureActive()
        val host = hostOf(url)
        val startedAt = guard.now()

        return guard.run(host, priority) {
            // Пока ждали слот и интервал, экран могли закрыть.
            currentCoroutineContext().ensureActive()
            val builder = request(url, referer)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = when {
                head -> builder.head().build()
                post != null -> builder.post(post.toRequestBody(htmlMediaType)).build()
                else -> builder.get().build()
            }
            val response = execute(request, host)
            response.use { received ->
                val code = received.code
                val retryAfter = if (code == HTTP_TOO_MANY_REQUESTS) retryAfterMs(received) else null
                if (retryAfter != null) guard.retryAfter(host, retryAfter)
                if (code !in 200..399) throw HttpStatusError(code, url, retryAfter)

                val bytes = if (head) ByteArray(0) else readCapped(received)
                logcat(LogPriority.DEBUG) {
                    "Takami fetch: " + request.method + " " + host + " -> " + code +
                        " за " + (guard.now() - startedAt) + " мс"
                }
                Raw(
                    code = code,
                    url = received.request.url.toString(),
                    location = received.header("Location"),
                    contentType = received.header("Content-Type"),
                    headers = received.headers,
                    bytes = bytes,
                )
            }
        }
    }

    private fun nextHop(current: String, raw: Raw, original: String): String {
        val location = raw.location
        if (location.isNullOrBlank()) throw HttpStatusError(raw.code, original)
        return current.toHttpUrlOrNull()?.resolve(location)?.toString()
            ?: throw InvalidUrlError(location)
    }

    /**
      * Тело с потолком: заявленный размер проверяем сразу, по факту
      * — по ходу
     * чтения. Так страховка работает и когда сервер врёт о длине.
     */
    private fun readCapped(response: Response): ByteArray {
        val declared = response.body.contentLength()
        if (declared > FetchLimits.MAX_HTML_BYTES) {
            throw PageTooLargeError(response.request.url.toString(), declared)
        }
        val source = response.body.source()
        val cap = FetchLimits.MAX_HTML_BYTES + 1
        val buffer = Buffer()
        while (buffer.size < cap) {
            if (source.read(buffer, cap - buffer.size) == -1L) break
        }
        if (buffer.size > FetchLimits.MAX_HTML_BYTES) {
            throw PageTooLargeError(response.request.url.toString(), buffer.size)
        }
        return buffer.readByteArray()
    }

    private fun request(url: String, referer: String?): Request.Builder {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw InvalidUrlError(url)
        }
        builder
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return builder
    }

    private suspend fun execute(request: Request, host: String): Response = try {
        withContext(Dispatchers.IO) { client.newCall(request).execute() }
    } catch (e: IOException) {
        throw NetworkError(host, e)
    }

    private fun remember(key: String, page: FetchedPage, at: Long) {
        memory[key] = Remembered(page, at)
        memory.entries.removeIf { at - it.value.at >= FetchLimits.MEMORY_TTL_MS }
        while (memory.size > FetchLimits.MEMORY_MAX_ENTRIES) {
            val oldest = memory.entries.minByOrNull { it.value.at } ?: break
            memory.remove(oldest.key, oldest.value)
        }
    }

    private data class Raw(
        val code: Int,
        val url: String,
        val location: String?,
        val contentType: String?,
        val headers: Headers,
        val bytes: ByteArray,
    )

    private class Loaded(
        val page: FetchedPage?,
        val notModified: Boolean,
        val code: Int,
        val etag: String?,
        val lastModified: String?,
        val bytes: ByteArray? = null,
    )

    private class Remembered(val page: FetchedPage, val at: Long)

    /**
     * Cookies держим в памяти: сайтам с сессией это нужно, на диск
     * ничего не пишем.
     */
    private class InMemoryCookieJar : CookieJar {
        private val jar = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            jar[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val stored = jar[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            val alive = stored.filter { it.expiresAt > now }
            if (alive.isEmpty()) jar.remove(url.host) else jar[url.host] = alive
            return alive
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        const val ACCEPT_LANGUAGE = "ru,en;q=0.9"

        const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,*/*;q=0.8"

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val MAX_IDLE_CONNECTIONS = 4
        private const val KEEP_ALIVE_MINUTES = 5L

        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        @Volatile
        private var instance: Fetcher? = null

        /**
         * Готовит общий сетевой слой. Вызывается при инициализации
         * реестра.
         */
        fun init(context: Context, budget: RequestBudget? = null) {
            if (instance != null) return
            synchronized(this) {
                if (instance != null) return
                val guard = HostGuard(budget ?: FileRequestBudget(context.applicationContext))
                instance = Fetcher(buildClient(), guard, HttpCache(context.applicationContext))
            }
        }

        fun get(): Fetcher = instance
            ?: throw IllegalStateException("Нет Fetcher: вызовите Fetcher.init(context)")

        internal fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .cookieJar(InMemoryCookieJar())
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // Редиректы разворачиваем сами: каждый переход —
            // отдельный запрос
            // под лимитами домена, а не бесплатный хоп внутри OkHttp.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** Счётчики ведём по домену без www: www.site.ru и site.ru — один сайт. */
        internal fun hostOf(url: String): String {
            val host = url.toHttpUrlOrNull()?.host ?: throw InvalidUrlError(url)
            return host.lowercase().removePrefix("www.")
        }

        private fun keyOf(url: String, referer: String?): String =
            if (referer.isNullOrBlank()) url else url + "|" + referer

        private fun retryAfterMs(response: Response): Long {
            val seconds = response.header("Retry-After")?.trim()?.toLongOrNull()
            val wait = if (seconds != null) seconds * 1_000L else FetchLimits.DEFAULT_RETRY_AFTER_MS
            return wait.coerceIn(0L, FetchLimits.MAX_RETRY_AFTER_MS)
        }
    }
}

private val htmlMediaType = "text/html; charset=utf-8".toMediaType()
