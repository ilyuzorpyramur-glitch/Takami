package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.longs.shouldBeGreaterThan
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * Предохранитель: пауза после серии ошибок, ровно один пробный
 * запрос, независимость доменов.
 */
class CircuitBreakerTest {
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

    private fun breaker(file: File? = null) = CircuitBreaker(file, clock)

    private fun fetcher(guardBreaker: CircuitBreaker): Fetcher = Fetcher(
        Fetcher.buildClient(),
        HostGuard(InMemoryRequestBudget(), clock, jitter = { 0L }),
        null,
        guardBreaker,
    )

    private fun alwaysFailing(code: Int = 503): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setResponseCode(code)
    }

    @Test
    fun `три ошибки открывают паузу и четвёртый запрос не идёт в сеть`(): Unit = runBlocking {
        server.dispatcher = alwaysFailing()
        val gate = breaker()
        val fetcher = fetcher(gate)
        val url = server.url("/catalog").toString()

        assertFailsWith<HttpStatusError> { fetcher.get(url) }
        val afterFirst = server.requestCount
        afterFirst shouldBe FetchLimits.RETRY_ATTEMPTS

        assertFailsWith<BreakerOpenError> { fetcher.get(url) }
        server.requestCount shouldBe afterFirst
        gate.state(server.hostName).state shouldBe BreakerState.OPEN
    }

    @Test
    fun `после паузы уходит ровно один пробный запрос`(): Unit = runBlocking {
        server.dispatcher = alwaysFailing()
        val gate = breaker()
        val fetcher = fetcher(gate)
        val url = server.url("/catalog").toString()
        assertFailsWith<HttpStatusError> { fetcher.get(url) }

        clock.advance(FetchLimits.BREAKER_PAUSE_MS + 1L)
        val before = server.requestCount

        // Вызовы идут по очереди: пробный достаётся первому, его неудача
        // сразу возвращает паузу, и следующие уже не стучатся в сайт.
        val first = runCatching { fetcher.get(url) }
        val second = runCatching { fetcher.get(url) }
        val third = runCatching { fetcher.get(url) }

        first.isFailure shouldBe true
        second.exceptionOrNull()!!::class shouldBe BreakerOpenError::class
        third.exceptionOrNull()!!::class shouldBe BreakerOpenError::class
        // Пробный уходит один раз: его неудача сразу возвращает паузу,
        // поэтому повтор внутри той же операции уже не проходит.
        (server.requestCount - before) shouldBe 1
        gate.state(server.hostName).state shouldBe BreakerState.OPEN
    }

    @Test
    fun `успешный пробный запрос закрывает предохранитель`(): Unit = runBlocking {
        val calls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (calls.incrementAndGet() <= FetchLimits.RETRY_ATTEMPTS) {
                    MockResponse().setResponseCode(503)
                } else {
                    MockResponse().setBody("<html>ok</html>")
                }
        }
        val gate = breaker()
        val fetcher = fetcher(gate)
        val url = server.url("/catalog").toString()
        assertFailsWith<HttpStatusError> { fetcher.get(url) }
        gate.state(server.hostName).state shouldBe BreakerState.OPEN

        clock.advance(FetchLimits.BREAKER_PAUSE_MS + 1L)
        val body = fetcher.get(url)

        body.contains("ok") shouldBe true
        gate.state(server.hostName).state shouldBe BreakerState.CLOSED
        gate.state(server.hostName).failures shouldBe 0
    }

    @Test
    fun `пауза переживает перезапуск приложения`(): Unit = runBlocking {
        val file = File(dir, "breaker.json")
        server.dispatcher = alwaysFailing()
        val first = breaker(file)
        assertFailsWith<HttpStatusError> {
            fetcher(first).get(server.url("/catalog").toString())
        }

        // Перезапуск: новый объект читает состояние с диска.
        val reopened = breaker(file)
        val state = reopened.state(server.hostName)

        state.state shouldBe BreakerState.OPEN
        state.pauseLeftMs shouldBeGreaterThan 0L
        reopened.allow(server.hostName) shouldBe false
    }

    @Test
    fun `домены независимы`(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("<html>ok</html>")
        }
        val gate = breaker()
        repeat(FetchLimits.BREAKER_FAILURES) { gate.onFailure("fallen.example", "HTTP 503") }

        val healthy = fetcher(gate).get(server.url("/catalog").toString())

        healthy.contains("ok") shouldBe true
        gate.state("fallen.example").state shouldBe BreakerState.OPEN
        gate.state(server.hostName).state shouldBe BreakerState.CLOSED
    }

    @Test
    fun `одна ошибка не открывает паузу`() = runBlocking {
        val gate = breaker()

        gate.onFailure("site.example", "HTTP 503")
        gate.onFailure("site.example", "HTTP 503")

        gate.state("site.example").state shouldBe BreakerState.CLOSED
        gate.allow("site.example") shouldBe true

        gate.onFailure("site.example", "HTTP 503")
        gate.state("site.example").state shouldBe BreakerState.OPEN
    }

    @Test
    fun `битый файл состояния не мешает работе`() {
        val file = File(dir, "broken.json")
        file.writeText("{ это не json")

        val gate = breaker(file)

        gate.state("site.example").state shouldBe BreakerState.CLOSED
        gate.allow("site.example") shouldBe true
    }
}
