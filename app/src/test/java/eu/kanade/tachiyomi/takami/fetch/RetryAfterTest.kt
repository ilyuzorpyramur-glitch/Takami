package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertFailsWith

/**
 * Ответ 429: пауза из заголовка Retry-After уважается следующим
 * запросом.
 */
class RetryAfterTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: FakeFetchClock
    private val arrivals = Collections.synchronizedList(mutableListOf<Long>())

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

    @Test
    fun `429 задаёт паузу`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals += clock.now()
                return if (arrivals.size == 1) {
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "3")
                } else {
                    MockResponse().setBody("<html>ok</html>")
                }
            }
        }
        val guard = HostGuard(InMemoryRequestBudget(), clock)
        val fetcher = Fetcher(Fetcher.buildClient(), guard)
        val url = server.url("/catalog").toString()

        val error = assertFailsWith<HttpStatusError> { fetcher.get(url) }
        error.code shouldBe 429
        error.retryAfterMs shouldBe 3_000L

        fetcher.get(url)

        arrivals.size shouldBe 2
        (arrivals[1] - arrivals[0]) shouldBeGreaterThanOrEqual 3_000L
    }

    @Test
    fun `429 без Retry-After даёт паузу по умолчанию`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(429)
        }
        val guard = HostGuard(InMemoryRequestBudget(), clock)
        val fetcher = Fetcher(Fetcher.buildClient(), guard)
        val error = assertFailsWith<HttpStatusError> { fetcher.get(server.url("/x").toString()) }

        error.retryAfterMs shouldBe FetchLimits.DEFAULT_RETRY_AFTER_MS
    }
}
