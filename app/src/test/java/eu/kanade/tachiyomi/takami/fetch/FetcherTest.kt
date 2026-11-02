package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Базовое поведение [Fetcher] на локальном сервере: тело, заголовки,
 * честный отказ.
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

    private fun fetcher(): Fetcher = Fetcher(
        OkHttpClient(),
        HostGuard(InMemoryRequestBudget(), FakeFetchClock()),
    )

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
}
