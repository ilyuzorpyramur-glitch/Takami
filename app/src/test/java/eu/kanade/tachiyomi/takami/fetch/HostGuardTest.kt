package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections

class HostGuardTest {
    private lateinit var server: MockWebServer
    private val arrivals = Collections.synchronizedList(mutableListOf<Long>())

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals += System.currentTimeMillis()
                return MockResponse().setBody("ok")
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `два запроса к одному домену идут с интервалом не меньше 1500 мс`(): Unit = runBlocking {
        val fetcher = Fetcher(OkHttpClient(), HostGuard(InMemoryRequestBudget()))
        val url = server.url("/catalog").toString()

        fetcher.get(url)
        fetcher.get(url)

        arrivals.size shouldBe 2
        // Допуск 10 мс: у часов миллисекундное округление, сам лимит — 1500 мс.
        (arrivals[1] - arrivals[0]) shouldBeGreaterThanOrEqual 1_490L
    }

    @Test
    fun `запросы к разным доменам не ждут друг друга`(): Unit = runBlocking {
        val second = MockWebServer()
        second.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody("ok")
        }
        second.start()

        try {
            val fetcher = Fetcher(OkHttpClient(), HostGuard(InMemoryRequestBudget()))
            fetcher.get(server.url("/a").toString())
            fetcher.get(second.url("/b").toString())

            arrivals.size shouldBe 1
            second.requestCount shouldBe 1
        } finally {
            second.shutdown()
        }
    }
}
