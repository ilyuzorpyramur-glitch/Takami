package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Дисковый кэш: свежее без сети, просроченное — условным
 * запросом.
 */
class HttpCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: FakeFetchClock

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun setUp() {
        clock = FakeFetchClock()
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun cache(
        maxBytes: Long = FetchLimits.CACHE_MAX_BYTES.toLong(),
        maxEntries: Int = FetchLimits.CACHE_INDEX_MAX_ENTRIES,
    ) = HttpCache(File(dir, "http"), maxBytes, maxEntries) { clock.now() }

    /**
     * Новый экземпляр на той же папке — как после перезапуска
     * приложения.
     */
    private fun fetcher(store: HttpCache = cache()): Fetcher = Fetcher(
        Fetcher.buildClient(),
        HostGuard(InMemoryRequestBudget(), clock, jitter = { 0L }),
        store,
    )

    private fun html(text: String) = "<html><body>" + text + "</body></html>"

    @Test
    fun `повторный вход в тот же адрес не идёт в сеть`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(html("каталог")))
        val store = cache()
        val url = server.url("/catalog").toString()

        fetcher(store).get(url)
        val second = fetcher(store).get(url)

        second.contains("каталог") shouldBe true
        server.requestCount shouldBe 1
    }

    @Test
    fun `304 отдаёт сохранённое тело без разбора`(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody(html("каталог")))
        server.enqueue(MockResponse().setResponseCode(304))
        val store = cache()
        val url = server.url("/catalog").toString()

        fetcher(store).get(url)
        clock.advance(FetchLimits.CACHE_TTL_MS + 1L)
        val second = fetcher(store).page(url)

        second.html.contains("каталог") shouldBe true
        second.code shouldBe 200
        server.requestCount shouldBe 2
        server.takeRequest().getHeader("If-None-Match") shouldBe null
        server.takeRequest().getHeader("If-None-Match") shouldBe "\"v1\""
    }

    @Test
    fun `изменённая страница заменяет запись`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(html("старое")))
        server.enqueue(MockResponse().setBody(html("новое")))
        val store = cache()
        val url = server.url("/catalog").toString()

        fetcher(store).get(url)
        clock.advance(FetchLimits.CACHE_TTL_MS + 1L)
        val second = fetcher(store).get(url)

        second.contains("новое") shouldBe true
        server.requestCount shouldBe 2
    }

    @Test
    fun `правка в хвосте страницы тоже замечается`(): Unit = runBlocking {
        val head = "а".repeat(5_000)
        server.enqueue(MockResponse().setBody(html(head)))
        server.enqueue(MockResponse().setBody(html(head + "хвост")))
        val store = cache()
        val url = server.url("/catalog").toString()

        fetcher(store).get(url)
        clock.advance(FetchLimits.CACHE_TTL_MS + 1L)
        val second = fetcher(store).get(url)

        second.contains("хвост") shouldBe true
    }

    @Test
    fun `ttl сутки и разброс в пределах двух часов`(): Unit = runBlocking {
        val store = cache()
        val ttl = store.ttlFor("anime.example")

        ttl shouldBeGreaterThanOrEqual (FetchLimits.CACHE_TTL_MS - FetchLimits.CACHE_TTL_JITTER_MS).toLong()
        ttl shouldBeLessThanOrEqual (FetchLimits.CACHE_TTL_MS + FetchLimits.CACHE_TTL_JITTER_MS).toLong()

        val entry = CacheEntry(fetchedAt = clock.now(), ttlMs = ttl)
        store.isFresh(entry) shouldBe true
        val late = clock.now() + FetchLimits.CACHE_TTL_MS + FetchLimits.CACHE_TTL_JITTER_MS
        store.isFresh(entry, late + 1) shouldBe false
    }

    @Test
    fun `разные источники получают разный срок`(): Unit = runBlocking {
        val store = cache()

        val first = store.ttlFor("one.example")
        val second = store.ttlFor("two.example")

        (first == second) shouldBe false
    }

    @Test
    fun `старые записи вытесняются при превышении`(): Unit = runBlocking {
        val store = cache(maxBytes = 2_048L)

        repeat(6) { index ->
            val key = store.key("https://site.example/" + index, null)
            store.store(key, CacheEntry(url = "https://site.example/" + index), html("страница " + index))
            clock.advance(1_000L)
        }

        store.sizeBytes() shouldBeLessThanOrEqual 2_048L
        store.lookup(store.key("https://site.example/0", null)) shouldBe null
        (store.lookup(store.key("https://site.example/5", null)) != null) shouldBe true
    }

    @Test
    fun `битый индекс читается как пустой`(): Unit = runBlocking {
        val store = cache()
        File(dir, "http").mkdirs()
        File(dir, "http/index.json").writeText("{ это не json")

        store.entries() shouldBe 0
        store.lookup(store.key("https://site.example/", null)) shouldBe null

        store.store(store.key("https://site.example/", null), CacheEntry(url = "https://site.example/"), html("ok"))
        store.entries() shouldBe 1
    }

    @Test
    fun `битое тело считается промахом и не ломает`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(html("каталог")))
        val store = cache()
        val url = server.url("/catalog").toString()

        fetcher(store).get(url)
        File(dir, "http").listFiles()?.filter { it.name.endsWith(".html") }?.forEach { it.writeText("") }

        val again = fetcher(store).get(url)

        again.contains("каталог") shouldBe true
    }

    @Test
    fun `без сети отдаётся последняя сохранённая`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(html("сохранённое")))
        val store = cache()
        val url = server.url("/catalog").toString()
        fetcher(store).get(url)

        clock.advance(FetchLimits.CACHE_TTL_MS + 1L)
        server.shutdown()
        val offline = fetcher(store).get(url)

        offline.contains("сохранённое") shouldBe true
    }

    @Test
    fun `referer входит в ключ`(): Unit = runBlocking {
        val store = cache()

        store.key("https://s.example/1", "https://s.example/a") shouldBe
            store.key("https://s.example/1", "https://s.example/a")
        (store.key("https://s.example/1", "https://s.example/a") ==
            store.key("https://s.example/1", "https://s.example/b")) shouldBe false
    }

    @Test
    fun `заголовки условного запроса собираются из записи`() {
        val entry = CacheEntry(etag = "\"v2\"", lastModified = "Mon, 02 Nov 2026 10:00:00 GMT")
        val headers = Conditional.headers(entry)

        headers["If-None-Match"] shouldBe "\"v2\""
        headers["If-Modified-Since"] shouldBe "Mon, 02 Nov 2026 10:00:00 GMT"
        Conditional.headers(null).isEmpty() shouldBe true
    }

    @Test
    fun `ответ 304 распознаётся`() {
        Conditional.isNotModified(304) shouldBe true
        Conditional.isNotModified(200) shouldBe false
    }

    @Test
    fun `отпечаток ловит правку в начале страницы`() {
        val first = html("один").toByteArray()
        val second = html("два").toByteArray()

        (Conditional.contentHash(first) == Conditional.contentHash(second)) shouldBe false
    }

    @Test
    fun `запись без метки версии сверяется по отпечатку`() {
        val body = html("каталог").toByteArray()
        val entry = CacheEntry(bodyHash = Conditional.contentHash(body), sizeBytes = body.size.toLong())

        Conditional.sameContent(entry, body) shouldBe true
        Conditional.sameContent(entry, html("другое").toByteArray()) shouldBe false
        Conditional.sameContent(CacheEntry(), body) shouldBe false
    }
}
