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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/** Повторы на живом сервере: сбой проходит, отсутствие страницы — нет. */
class RetryTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: FakeFetchClock

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

    private fun fetcher(): Fetcher = Fetcher(
        Fetcher.buildClient(),
        HostGuard(InMemoryRequestBudget(), clock, jitter = { 0L }),
        null,
        CircuitBreaker(clock = clock),
    )

    @Test
    fun `ответ пятьсот три повторяется и потом проходит`(): Unit = runBlocking {
        val calls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (calls.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(503)
                } else {
                    MockResponse().setBody("<html>ok</html>")
                }
        }

        val body = fetcher().get(server.url("/catalog").toString())

        body.contains("ok") shouldBe true
        server.requestCount shouldBe 2
        clock.sleeps.contains(FetchLimits.retryDelaysMs[0]) shouldBe true
    }

    @Test
    fun `ответ пятьсот три повторяется не больше трёх раз`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        assertFailsWith<HttpStatusError> { fetcher().get(server.url("/catalog").toString()) }

        server.requestCount shouldBe FetchLimits.RETRY_ATTEMPTS
    }

    @Test
    fun `страница не найдена не повторяется`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        }

        assertFailsWith<HttpStatusError> {
            fetcher().get(server.url("/missing").toString())
        }

        server.requestCount shouldBe 1
    }

    @Test
    fun `перегрузка ждёт срок из заголовка`(): Unit = runBlocking {
        val calls = AtomicInteger(0)
        val arrivals = Collections.synchronizedList(mutableListOf<Long>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals += clock.now()
                return if (calls.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "9")
                } else {
                    MockResponse().setBody("<html>ok</html>")
                }
            }
        }

        fetcher().get(server.url("/catalog").toString())

        arrivals.size shouldBe 2
        (arrivals[1] - arrivals[0]) shouldBeGreaterThanOrEqual 9_000L
    }
}
