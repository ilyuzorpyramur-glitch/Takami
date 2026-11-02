package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/** Лимиты домена: пауза, разброс, приоритет экрана над фоном. */
class HostGuardTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: FakeFetchClock
    private val arrivals = Collections.synchronizedList(mutableListOf<String>())

    @BeforeEach
    fun setUp() {
        clock = FakeFetchClock()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals += (request.path ?: "").trimStart('/')
                return MockResponse().setBody("<html>ok</html>")
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `пауза между запросами не меньше 1500 мс`(): Unit = runBlocking {
        val guard = HostGuard(InMemoryRequestBudget(), clock, jitter = { 0L })
        var firstAt = 0L

        guard.run("site.example") { firstAt = clock.now() }
        guard.run("site.example") { }

        (clock.now() - firstAt) shouldBe FetchLimits.MIN_INTERVAL_MS
    }

    @Test
    fun `разброс сверх минимума`(): Unit = runBlocking {
        val guard = HostGuard(InMemoryRequestBudget(), clock, jitter = { 300L })
        var sent = 0
        guard.run("site.example") { sent++ }
        val first = clock.now()
        guard.run("site.example") { sent++ }

        sent shouldBe 2
        (clock.now() - first) shouldBe FetchLimits.MIN_INTERVAL_MS + 300L
    }

    @Test
    fun `фон уступает экрану`(): Unit = runBlocking {
        val gate = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").trimStart('/')
                arrivals += path
                if (path == "screen") {
                    gate.countDown()
                    release.await()
                }
                return MockResponse().setBody("<html>ok</html>")
            }
        }
        val fetcher = Fetcher(Fetcher.buildClient(), HostGuard(InMemoryRequestBudget(), clock))

        val screen = async(Dispatchers.IO) { fetcher.get(server.url("/screen").toString()) }
        gate.await()
        val background = launch(Dispatchers.IO) {
            fetcher.get(server.url("/sync").toString(), null, FetchPriority.BACKGROUND)
        }
        // Фон не должен занять второй слот, пока экран ждёт ответа.
        arrivals.toList() shouldBe listOf("screen")
        release.countDown()

        screen.await()
        background.join()
        arrivals.toList() shouldContainExactly listOf("screen", "sync")
    }

    @Test
    fun `запросы к разным доменам не ждут друг друга`(): Unit = runBlocking {
        val second = MockWebServer()
        second.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setBody("<html>ok</html>")
        }
        second.start()

        try {
            val fetcher = Fetcher(Fetcher.buildClient(), HostGuard(InMemoryRequestBudget(), clock))
            fetcher.get(server.url("/a").toString())
            fetcher.get(second.url("/b").toString())

            arrivals.size shouldBe 1
            second.requestCount shouldBe 1
        } finally {
            second.shutdown()
        }
    }
}
