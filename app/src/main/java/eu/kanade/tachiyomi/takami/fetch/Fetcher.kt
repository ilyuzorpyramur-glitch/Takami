package eu.kanade.tachiyomi.takami.fetch

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_LIBREDNS
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.network.doh360
import eu.kanade.tachiyomi.network.dohAdGuard
import eu.kanade.tachiyomi.network.dohAliDNS
import eu.kanade.tachiyomi.network.dohCloudflare
import eu.kanade.tachiyomi.network.dohControlD
import eu.kanade.tachiyomi.network.dohDNSPod
import eu.kanade.tachiyomi.network.dohGoogle
import eu.kanade.tachiyomi.network.dohLibreDNS
import eu.kanade.tachiyomi.network.dohMullvad
import eu.kanade.tachiyomi.network.dohNajalla
import eu.kanade.tachiyomi.network.dohQuad101
import eu.kanade.tachiyomi.network.dohQuad9
import eu.kanade.tachiyomi.network.dohShecan
import eu.kanade.tachiyomi.takami.report.TakamiReports
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
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    private val breaker: CircuitBreaker? = null,
    private val challenges: ChallengeBrowser? = null,
    private val protection: ProtectionRegistry = ProtectionRegistry(),
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
       * Одинаковые одновременные запросы склеиваются в
       * один, свежая
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
        val cached = store?.lookup(store.key(url, referer), at)
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
            val result = runCatching {
                val loaded = fetchWithCache(url, referer, priority, cached)
                remember(key, loaded, guard.now())
                loaded
            }
            // Снимаем запись раньше, чем будим ожидающих: иначе следующий
            // вызов успеет присоединиться к уже завершённой загрузке,
            // получит её ошибку и не спросит предохранитель.
            inFlight.remove(key, slot)
            result.fold(
                onSuccess = { slot.complete(it) },
                onFailure = { slot.completeExceptionally(it) },
            )
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
        cached: CachedHit?,
    ): FetchedPage {
        val store = cache
        val key = store?.key(url, referer).orEmpty()
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

    /**
     * Отправка отчёта: нужен только код ответа, тело служебное.
     *
      * Один проход через общий диспетчер: повторов здесь
      * нет, иначе при
     * недоступном приёме запросы пошли бы подряд.
     */
    suspend fun postReport(url: String, body: String, token: String = ""): Int {
        currentCoroutineContext().ensureActive()
        // Подпись кладём в заголовок только при непустом значении:
        // пустая подпись означает, что отправка выключена.
        val headers = if (token.isEmpty()) {
            emptyMap()
        } else {
            mapOf("Authorization" to "Bearer $token")
        }
        val raw = attempt(
            url = url,
            referer = null,
            priority = FetchPriority.BACKGROUND,
            head = false,
            post = body,
            headers = headers,
        )
        return raw.code
    }

    suspend fun post(url: String, body: String, referer: String? = null): String {
        currentCoroutineContext().ensureActive()
        val raw = send(url, referer, FetchPriority.INTERACTIVE, post = body)
        return ResponseGate.check(raw.contentType, raw.bytes, raw.url).html
    }

    /**
     * Ответ как текст, без ворот HTML.
     *
     * Служебные ответы плееров — это JSON: ворота считают его не той
     * страницей и отбрасывают, а разобрать его нужно. Кодировку берём у
     * ответа, размер уже ограничен при чтении.
     */
    suspend fun getText(url: String, referer: String? = null): String {
        currentCoroutineContext().ensureActive()
        return textOf(send(url, referer, FetchPriority.INTERACTIVE))
    }

    /** То же для запроса с телом: ссылки плеера приходят ответом на POST. */
    suspend fun postText(url: String, body: String, referer: String? = null): String {
        currentCoroutineContext().ensureActive()
        return textOf(send(url, referer, FetchPriority.INTERACTIVE, post = body, form = true))
    }

    /** Текст ответа: код проверяем, пустое тело — ошибка, ворота не зовём. */
    private fun textOf(raw: Raw): String {
        if (raw.code !in 200..299) throw HttpStatusError(raw.code, raw.url)
        if (raw.bytes.isEmpty()) throw NotHtmlError(raw.url, raw.contentType.orEmpty())
        val charset = CharsetSniffer.detect(raw.contentType, raw.bytes)
        return String(raw.bytes, charset).removePrefix("\uFEFF")
    }

    /**
      * Первые байты медиа: проверяем, что ссылка отдаёт поток, а не
      * страницу.
      * Читаем не больше [FetchLimits.PROBE_BYTES]: сервер вправе
      * проигнорировать
     * Range, и тогда без ограничения мы бы скачали весь файл.
     */
    suspend fun probe(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        currentCoroutineContext().ensureActive()
        val host = hostOf(url)
        return guard.run(host) {
            currentCoroutineContext().ensureActive()
            val builder = request(url, referer)
            // Заголовки окна: мастер HLS без них отвечает отказом.
            headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
            val request = builder
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
     * Сводка по доменам: расход, остаток и состояние
     * предохранителя.
     */
    suspend fun diagnostics(): List<HostReport> {
        val hosts = (guard.budgetHosts() + (breaker?.hosts() ?: emptyList()))
            .distinct()
            .sorted()
        return hosts.map { host ->
            val info = breaker?.state(host)
            HostReport(
                host = host,
                used = guard.used(host),
                remaining = guard.remaining(host),
                limit = guard.dailyLimit,
                breaker = info?.state ?: BreakerState.CLOSED,
                pauseLeftMs = info?.pauseLeftMs ?: 0L,
                lastError = info?.lastError,
            )
        }
    }

    /** Требует ли сайт проверки человеком и какого уровня. */
    fun verification(host: String): VerificationStatus? = protection.status(host)

    /** Все сайты, которые ждут проверки. */
    fun verifications(): List<VerificationStatus> = protection.all()

    /** Человек прошёл проверку: снимаем пометку. */
    fun clearVerification(host: String) = protection.clear(host)

    /**
     * Забирает cookie из видимого окна проверки в общий jar.
     *
      * Так следующий запрос идёт уже с cookie, которую
      * поставил сайт после
     * проверки человеком.
     */
    fun acceptCookies(url: String, host: String) {
        val header = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url)
        }.getOrNull().orEmpty()
        val base = url.toHttpUrlOrNull()
        val parsed = if (base == null) emptyList() else ChallengeCookies.parse(host, header)
        if (parsed.isNotEmpty() && base != null) client.cookieJar.saveFromResponse(base, parsed)
        protection.clear(host)
    }

    /**
     * Сброс дневных счётчиков: кнопка в диагностике, только для
     * отладочной сборки.
     */
    suspend fun resetCounters() {
        guard.resetBudget()
    }

    /**
      * Цепочка редиректов разворачивается по одному
      * запросу: каждый
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
        var browserPassed = false
        while (true) {
            // Условные заголовки относятся к исходному адресу: после
            // перехода
            // метка версии уже от другой страницы и только запутает
            // сайт.
            val extra = if (hops == 0) conditional else emptyMap()
            val raw = send(current, ref, priority, headers = extra)
            val etag = raw.headers["ETag"]
            val lastModified = raw.headers["Last-Modified"]

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
                val gated: GatedPage
                try {
                    gated = ResponseGate.check(raw.contentType, raw.bytes, raw.url)
                } catch (e: ChallengePageError) {
                    // Заглушка без явных заголовков: браузер
                    // пробуем один раз.
                    val host = hostOf(current)
                    if (browserPassed || passBrowser(raw.url, host) == null) {
                        refuse(host, raw.url, ProtectionLevel.CAPTCHA, "проверка браузера не пройдена")
                    }
                    browserPassed = true
                    continue
                }
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

    /**
     * Запрос с повторами.
     *
      * Повтор идёт тем же путём, что и первая попытка: через
      * диспетчер лимитов и
       * предохранитель. Своего клиента у повторов нет —
       * иначе лимиты
      * обходились бы
     * одной строкой.
     */
    private suspend fun send(
        url: String,
        referer: String?,
        priority: FetchPriority,
        head: Boolean = false,
        post: String? = null,
        headers: Map<String, String> = emptyMap(),
        form: Boolean = false,
    ): Raw {
        currentCoroutineContext().ensureActive()
        return RetryPolicy().execute(guard.timeSource) {
            attempt(url, referer, priority, head, post, headers, form)
        }
    }

    private suspend fun attempt(
        url: String,
        referer: String?,
        priority: FetchPriority,
        head: Boolean,
        post: String?,
        headers: Map<String, String>,
        form: Boolean = false,
    ): Raw {
        currentCoroutineContext().ensureActive()
        val host = hostOf(url)
        val gate = breaker
        // Домен в паузе: ни одного запроса, даже пробного.
        if (gate != null && !gate.allow(host)) throw BreakerOpenError(host)

        val healthBefore = gate?.state(host)?.state
        // Пробный запрос после паузы: его неудача снова
        // закрывает домен,
        // и повтор внутри той же операции уже не имеет смысла.
        val probing = gate?.state(host)?.state == BreakerState.HALF_OPEN
        val first = try {
            sendOnce(url, referer, priority, head, post, headers, host, form)
        } catch (e: IOException) {
            gate?.onFailure(host, e.message)
            noteFailure(url, e)
            noteHealth(host, healthBefore)
        if (probing && !gate.allow(host)) throw BreakerOpenError(host)
            throw e
        }
        gate?.onSuccess(host)
        noteHealth(host, healthBefore)
        if (first.protection.passed) return first

        // Уровень A: настоящий браузер проходит проверку сам.
        // Повтор — один.
        if (first.protection.level == ProtectionLevel.BROWSER_CHECK) {
            if (passBrowser(first.url, host) != null) {
                val again = try {
                    sendOnce(url, referer, priority, head, post, headers, host, form)
                } catch (e: IOException) {
                    gate?.onFailure(host, e.message)
                    throw e
                }
                gate?.onSuccess(host)
                if (again.protection.passed) return again
                refuse(host, again.url, ProtectionLevel.CAPTCHA, again.protection.reason)
            }
            // Не прошли — дальше это уже уровень B: нужен человек.
            refuse(host, first.url, ProtectionLevel.CAPTCHA, "проверка браузера не пройдена")
        }
        if (first.protection.level == ProtectionLevel.CAPTCHA) {
            refuse(host, first.url, ProtectionLevel.CAPTCHA, first.protection.reason)
        }
        // Уровень C: отказ. Повторов нет, сайт помечается и
        // ждёт человека.
        refuse(host, first.url, ProtectionLevel.BLOCKED, first.protection.reason)
    }

    /**
     * Один проход к сайту: без повторов и без разбора защиты.
     *
      * Возвращает ответ вместе с вердиктом о защите;
      * решают, что с ним делать,
      * выше — иначе повтор внутри повтора обходил бы и
      * лимиты, и запрет на
     * «ещё одну попытку на удачу».
     */
    private suspend fun sendOnce(
        url: String,
        referer: String?,
        priority: FetchPriority,
        head: Boolean,
        post: String?,
        headers: Map<String, String>,
        host: String,
        form: Boolean = false,
    ): Raw = guard.run(host, priority) {
        // Пока ждали слот и интервал, экран могли закрыть.
        currentCoroutineContext().ensureActive()
        val startedAt = guard.now()
        val builder = request(url, referer)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = when {
            head -> builder.head().build()
            post != null -> builder.post(post.toRequestBody(if (form) formMediaType else htmlMediaType)).build()
            else -> builder.get().build()
        }
        val response = execute(request, host)
        response.use { received ->
            val code = received.code
            val retryAfter = if (code == HTTP_TOO_MANY_REQUESTS) retryAfterMs(received) else null
            val bytes = if (head) ByteArray(0) else readCapped(received)
            val signal = if (code in 200..399) {
                ProtectionSignal(ProtectionLevel.NONE)
            } else {
                Protection.of(
                    code = code,
                    server = received.header("Server"),
                    mitigated = received.header("cf-mitigated"),
                    html = bytes.toString(Charsets.UTF_8),
                )
            }
            if (code !in 200..399 && signal.passed) {
                if (retryAfter != null) guard.retryAfter(host, retryAfter)
                throw HttpStatusError(code, url, retryAfter)
            }
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
                protection = signal,
            )
        }
    }

    /**
     * Проверка браузером: скрытое окно с JS, cookie и один повтор.
     *
     * Возвращает строку cookie, если сайт пустил. Cookie сразу попадает в
     * общий jar: следующие запросы идут уже с ней.
     */
    private suspend fun passBrowser(pageUrl: String, host: String): String? {
        val browser = challenges ?: return null
        val address = pageUrl.ifBlank { "https://" + host + "/" }
        val cookie = runCatching { browser.pass(address, host) }
            .onFailure { logcat(LogPriority.DEBUG, it) { "Takami: проверка браузера" +
                "сорвалась" } }
            .getOrNull()
            ?: return null
        val parsed = ChallengeCookies.parse(host, cookie)
        val base = address.toHttpUrlOrNull()
        if (parsed.isNotEmpty() && base != null) client.cookieJar.saveFromResponse(base, parsed)
        protection.clear(host)
        return cookie
    }

    /** Сайт не пустил: помечаем и прекращаем попытки. */
    private fun refuse(
        host: String,
        url: String,
        level: ProtectionLevel,
        reason: String,
    ): Nothing {
        TakamiReports.noteFailure(host, "protection", level.name.lowercase(), "blocked", null, 0L, 0)
        protection.mark(host, level, reason, System.currentTimeMillis())
        logcat(LogPriority.DEBUG) { "Takami: " + host + " требует проверки — " + reason }
        throw VerificationRequiredError(host, level, url)
    }

    /**
     * Отчёт о сбое: шаг, тип ошибки и код ответа.
     *
      * Тексты ошибок содержат адреса, поэтому наружу
      * уходит только тип.
     */
    private fun noteFailure(url: String, error: IOException) {
        val host = runCatching { hostOf(url) }.getOrDefault("")
        if (host.isBlank()) return
        val step = when (error) {
            is HttpStatusError -> "response"
            is NetworkError -> "network"
            is BreakerOpenError -> "pause"
            is DailyLimitReached -> "limit"
            is VerificationRequiredError -> "check"
            is ChallengePageError -> "challenge"
            is NotHtmlError -> "content"
            is PageTooLargeError -> "content"
            is TooManyRedirectsError -> "redirect"
            else -> "other"
        }
        val code = (error as? HttpStatusError)?.code
        val kind = error::class.simpleName ?: "error"
        TakamiReports.noteFailure(host, step, kind, healthOf(host), code, 0L, 0)
    }

    private fun healthOf(host: String): String =
        breaker?.state(host)?.state?.name?.lowercase() ?: "unknown"

    /** Смена состояния источника: сообщаем только о переходах. */
    private fun noteHealth(host: String, before: BreakerState?) {
        val now = breaker?.state(host)?.state ?: return
        if (before == null || before == now) return
        TakamiReports.noteHealth(host, now.name.lowercase())
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

    /** Домен в паузе брейкера: не долбим упавший сайт. */
    fun isPaused(host: String): Boolean = runCatching {
        breaker?.state(host)?.state == BreakerState.OPEN
    }.getOrDefault(false)

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
        val protection: ProtectionSignal = ProtectionSignal(ProtectionLevel.NONE),
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
                val app = context.applicationContext
                val guard = HostGuard(budget ?: FileRequestBudget(app))
                val breaker = CircuitBreaker(File(app.filesDir, BREAKER_FILE))
                // Сбор отчётов стартует вместе с сетью: без
                // адреса в сборке
                // он просто ничего не отправляет.
                TakamiReports.start(app)
                instance = Fetcher(
                    client = buildClient(currentDohProvider()),
                    guard = guard,
                    cache = HttpCache(app),
                    breaker = breaker,
                    challenges = runCatching { WebViewChallengeBrowser(app) }.getOrNull(),
                )
            }
        }

        fun get(): Fetcher = instance
            ?: throw IllegalStateException("Нет Fetcher: вызовите Fetcher.init(context)")

        /**
         * Собирает клиент с тем же DNS over HTTPS, что и у общего клиента
          * приложения: при блокировке DNS виртуальные
          * источники должны работать
         * так же, как обычные расширения.
         */
        internal fun buildClient(dohProvider: Int = NO_DOH): OkHttpClient {
            val builder = OkHttpClient.Builder()
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
            return withDoh(builder, dohProvider).build()
        }

        /**
         * Выбор провайдера — ровно как в общем сетевом слое
         * приложения. */
        internal fun withDoh(builder: OkHttpClient.Builder, provider: Int): OkHttpClient.Builder =
            when (provider) {
                PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
                PREF_DOH_GOOGLE -> builder.dohGoogle()
                PREF_DOH_ADGUARD -> builder.dohAdGuard()
                PREF_DOH_QUAD9 -> builder.dohQuad9()
                PREF_DOH_ALIDNS -> builder.dohAliDNS()
                PREF_DOH_DNSPOD -> builder.dohDNSPod()
                PREF_DOH_360 -> builder.doh360()
                PREF_DOH_QUAD101 -> builder.dohQuad101()
                PREF_DOH_MULLVAD -> builder.dohMullvad()
                PREF_DOH_CONTROLD -> builder.dohControlD()
                PREF_DOH_NJALLA -> builder.dohNajalla()
                PREF_DOH_SHECAN -> builder.dohShecan()
                PREF_DOH_LIBREDNS -> builder.dohLibreDNS()
                else -> builder
            }

        /**
         * Провайдер из настроек приложения; без настроек —
         * системный DNS. */
        internal fun currentDohProvider(): Int = runCatching {
            Injekt.get<NetworkPreferences>().dohProvider().get()
        }.getOrDefault(NO_DOH)

        /** Счётчики ведём по домену без www: www.site.ru и site.ru — один сайт. */
        internal fun hostOf(url: String): String {
            val host = url.toHttpUrlOrNull()?.host ?: throw InvalidUrlError(url)
            return host.lowercase().removePrefix("www.")
        }

        private const val BREAKER_FILE = "takami/breaker.json"

        /** Провайдер не выбран: работаем через системный DNS. */
        internal const val NO_DOH = -1

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

/** Тело служебного запроса плеера: обычная форма. */
private val formMediaType = "application/x-www-form-urlencoded".toMediaType()
