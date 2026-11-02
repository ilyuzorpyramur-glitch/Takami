package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import kotlin.test.assertFailsWith

/**
 * Поведение [Fetcher] на локальном сервере: тело, редиректы, лимиты
 * чтения.
 */
class FetcherTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher(budget: RequestBudget = InMemoryRequestBudget()): Fetcher =
        Fetcher(OkHttpClient(), HostGuard(budget, FakeFetchClock(), jitter = { 0L }))

    @Test
    fun `get отдаёт тело и заголовки`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("<html>каталог</html>"))

        val body = fetcher().get(server.url("/catalog").toString())

        body shouldBe "<html>каталог</html>"
        val sent = server.takeRequest()
        sent.getHeader("Accept-Language") shouldBe "ru,en;q=0.9"
        sent.getHeader("User-Agent")?.contains("Chrome/124") shouldBe true
    }

    @Test
    fun `head отдаёт код и заголовки без тела`(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "\"abc\"").setBody("лишнее"))

        val info = fetcher().head(server.url("/catalog").toString())

        info.code shouldBe 200
        info.header("ETag") shouldBe "\"abc\""
    }

    @Test
    fun `ответ 429 превращается в HttpStatusError с retryAfterMs`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

        val error = assertFailsWith<HttpStatusError> {
            fetcher().get(server.url("/catalog").toString())
        }

        error.code shouldBe 429
        error.retryAfterMs shouldBe 7_000L
    }

    @Test
    fun `редирект это отдельный запрос`(): Unit = runBlocking {
        val budget = InMemoryRequestBudget()
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/mirror/catalog"))
        server.enqueue(MockResponse().setBody("<html><body>каталог</body></html>"))

        val page = fetcher(budget).page(server.url("/catalog").toString())

        page.url.endsWith("/mirror/catalog") shouldBe true
        page.html.contains("каталог") shouldBe true
        server.requestCount shouldBe 2
        budget.used(server.hostName) shouldBe 2
    }

    @Test
    fun `бесконечная цепочка редиректов обрывается`(): Unit = runBlocking {
        repeat(FetchLimits.MAX_REDIRECTS + 1) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop"))
        }

        assertFailsWith<TooManyRedirectsError> {
            fetcher().get(server.url("/loop").toString())
        }
    }

    @Test
    fun `probe читает не больше 512 байт`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("A".repeat(64 * 1024)))

        val bytes = fetcher().probe(server.url("/movie.mp4").toString())

        bytes.length shouldBe FetchLimits.PROBE_BYTES
    }

    @Test
    fun `кодировка windows-1251 берётся из meta`(): Unit = runBlocking {
        val html = "<html><head><meta charset=\"windows-1251\"></head><body>Привет</body></html>"
        val bytes = html.toByteArray(Charset.forName("windows-1251"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(bytes)),
        )

        val page = fetcher().page(server.url("/page").toString())

        page.html.contains("Привет") shouldBe true
        page.charset.lowercase().contains("1251") shouldBe true
    }

    @Test
    fun `заглушка не повторяется`(): Unit = runBlocking {
        val budget = InMemoryRequestBudget()
        server.enqueue(
            MockResponse().setBody("<html><head><title>Just a moment...</title></head></html>"),
        )

        assertFailsWith<ChallengePageError> {
            fetcher(budget).get(server.url("/page").toString())
        }
        server.requestCount shouldBe 1
        budget.used(server.hostName) shouldBe 1
    }

    @Test
    fun `страница с типом json не уходит в разбор`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[]}""")
        }

        val error = assertFailsWith<NotHtmlError> {
            fetcher().get(server.url("/api").toString())
        }

        error.contentType.contains("json") shouldBe true
    }

    @Test
    fun `одновременные запросы склеиваются`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("<html>раз</html>"))

        val fetcher = fetcher()
        val url = server.url("/catalog").toString()
        val first = kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.Default) { fetcher.get(url) }
        val second = kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.Default) { fetcher.get(url) }

        first.await() shouldBe "<html>раз</html>"
        second.await() shouldBe "<html>раз</html>"
        server.requestCount shouldBe 1
    }

    @Test
    fun `короткая память без сети`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("<html>раз</html>"))

        val fetcher = fetcher()
        val url = server.url("/catalog").toString()
        fetcher.get(url)
        val again = fetcher.get(url)

        again shouldBe "<html>раз</html>"
        server.requestCount shouldBe 1
    }
}
