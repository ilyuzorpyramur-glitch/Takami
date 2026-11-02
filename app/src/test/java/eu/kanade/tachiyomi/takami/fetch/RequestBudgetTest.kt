package eu.kanade.tachiyomi.takami.fetch

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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith

class RequestBudgetTest {
    private lateinit var server: MockWebServer

    @TempDir
    lateinit var dir: File

    private fun budget(day: String = "2026-11-02"): FileRequestBudget =
        FileRequestBudget(File(dir, "network-budget.json"), FetchLimits.DAILY_PER_HOST) { day }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody("<html>ok</html>")
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `301-й запрос за сутки не идёт в сеть`(): Unit = runBlocking {
        val clock = FakeFetchClock()
        val fetcher = Fetcher(OkHttpClient(), HostGuard(budget(), clock))
        val url = server.url("/catalog").toString()

        repeat(FetchLimits.DAILY_PER_HOST) { fetcher.get(url) }
        val error = assertFailsWith<DailyLimitReached> { fetcher.get(url) }

        error.limit shouldBe FetchLimits.DAILY_PER_HOST
        server.requestCount shouldBe FetchLimits.DAILY_PER_HOST
    }

    @Test
    fun `счётчик ведётся по домену`(): Unit = runBlocking {
        val store = budget()
        val fetcher = Fetcher(OkHttpClient(), HostGuard(store, FakeFetchClock()))

        fetcher.get(server.url("/one").toString())
        fetcher.get(server.url("/two").toString())

        store.used(server.hostName) shouldBe 2
    }

    @Test
    fun `счётчик переживает перезапуск приложения`(): Unit = runBlocking {
        budget().consume("anime.example")

        budget().used("anime.example") shouldBe 1
    }

    @Test
    fun `новый день начинает счёт заново`(): Unit = runBlocking {
        budget("2026-11-02").consume("anime.example")
        budget("2026-11-02").consume("anime.example")

        val tomorrow = budget("2026-11-03")

        tomorrow.used("anime.example") shouldBe 0
        tomorrow.remaining("anime.example") shouldBe FetchLimits.DAILY_PER_HOST
    }

    @Test
    fun `сброс возвращает полный лимит`(): Unit = runBlocking {
        val store = budget()
        store.consume("anime.example")

        store.reset()

        store.used("anime.example") shouldBe 0
        store.remaining("anime.example") shouldBe FetchLimits.DAILY_PER_HOST
    }

    @Test
    fun `битый файл счётчика не ломает запросы`(): Unit = runBlocking {
        File(dir, "network-budget.json").writeText("{ это не json")

        val store = budget()

        store.used("anime.example") shouldBe 0
        store.consume("anime.example") shouldBe true
        store.used("anime.example") shouldBe 1
    }
}
