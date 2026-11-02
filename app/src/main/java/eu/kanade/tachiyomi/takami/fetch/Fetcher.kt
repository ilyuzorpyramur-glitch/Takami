package eu.kanade.tachiyomi.takami.fetch

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
 * Единственная точка HTTP для виртуальных источников.
 *
  * Наружу не отдаёт [OkHttpClient]: иначе лимиты, повторы и кэш можно было
  * бы обойти,
  * создав свой клиент. Внутри — пул соединений, gzip, HTTP/2, cookie-jar и
  * таймауты 20/20 с.
 */
class Fetcher internal constructor(
    private val client: OkHttpClient,
    private val guard: HostGuard,
) {
    suspend fun get(url: String, referer: String? = null): String =
        call(request(url, referer).get().build(), readBody = true).body

    suspend fun head(url: String): ResponseInfo {
        val result = call(request(url, null).head().build(), readBody = false)
        return ResponseInfo(result.code, result.url, result.headers.toMultimap())
    }

    suspend fun post(url: String, body: String, referer: String? = null): String =
        call(request(url, referer).post(body.toRequestBody(htmlMediaType)).build(), readBody = true).body

    /**
      * Первые байты медиа: проверяем, что ссылка отдаёт поток, а не
      * страницу.
     * Трафик минимальный — Range 0..256.
     */
    suspend fun probe(url: String, referer: String? = null): String =
        call(request(url, referer).header("Range", "bytes=0-256").get().build(), readBody = true).body

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

    private suspend fun call(request: Request, readBody: Boolean): HttpResult {
        currentCoroutineContext().ensureActive()
        val host = hostKey(request.url.host)
        val startedAt = guard.now()

        return guard.run(host) {
            // Пока ждали слот и интервал, экран могли закрыть.
            currentCoroutineContext().ensureActive()
            val response = execute(request, host)
            response.use { received ->
                val code = received.code
                val retryAfter = if (code == HTTP_TOO_MANY_REQUESTS) retryAfterMs(received) else null
                if (retryAfter != null) guard.retryAfter(host, retryAfter)
                if (code !in 200..399) {
                    throw HttpStatusError(code, request.url.toString(), retryAfter)
                }

                val body = if (readBody) received.body.string() else ""
                logcat(LogPriority.DEBUG) {
                    "Takami fetch: " + request.method + " " + host + " -> " + code +
                        " за " + (guard.now() - startedAt) + " мс"
                }
                HttpResult(code, request.url.toString(), body, received.headers)
            }
        }
    }

    private suspend fun execute(request: Request, host: String): Response = try {
        withContext(Dispatchers.IO) { client.newCall(request).execute() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw NetworkError(host, e)
    }

    private data class HttpResult(
        val code: Int,
        val url: String,
        val body: String,
        val headers: Headers,
    )

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

        @Volatile
        private var instance: Fetcher? = null

        /**
         * Готовит общий сетевой слой. Вызывается при инициализации
         * реестра источников.
         */
        fun init(context: Context, budget: RequestBudget? = null) {
            if (instance != null) return
            synchronized(this) {
                if (instance != null) return
                val guard = HostGuard(budget ?: FileRequestBudget(context.applicationContext))
                instance = Fetcher(buildClient(), guard)
            }
        }

        fun get(): Fetcher = instance
            ?: throw IllegalStateException("Нет Fetcher: вызовите Fetcher.init(context)")

        private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .cookieJar(InMemoryCookieJar())
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** Счётчики ведём по домену без www: www.site.ru и site.ru — один сайт. */
        private fun hostKey(host: String): String = host.lowercase().removePrefix("www.")

        private fun retryAfterMs(response: Response): Long {
            val seconds = response.header("Retry-After")?.trim()?.toLongOrNull()
            val wait = if (seconds != null) seconds * 1_000L else FetchLimits.DEFAULT_RETRY_AFTER_MS
            return wait.coerceIn(0L, FetchLimits.MAX_RETRY_AFTER_MS)
        }
    }
}

private val htmlMediaType = "text/html; charset=utf-8".toMediaType()
