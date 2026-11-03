package eu.kanade.tachiyomi.takami.report

import eu.kanade.tachiyomi.takami.Forge
import eu.kanade.tachiyomi.takami.ForgeResult
import eu.kanade.tachiyomi.takami.ParseFailures
import eu.kanade.tachiyomi.takami.SpecEngine
import eu.kanade.tachiyomi.takami.fetch.FakeFetchClock
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import eu.kanade.tachiyomi.takami.fetch.HostGuard
import eu.kanade.tachiyomi.takami.fetch.InMemoryRequestBudget
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Провал сборки спеки на живом пути: локальный сервер, без сети. */
class ForgeReportTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        ParseFailures.sink = null
        server.shutdown()
    }

    private fun fetcher(): Fetcher = Fetcher(
        Fetcher.buildClient(),
        HostGuard(InMemoryRequestBudget(), FakeFetchClock(), jitter = { 0L }),
    )

    @Test
    fun `провал сборки спеки попадает в очередь`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body><p>пусто</p></body></html>"))

        val active = Reporter(
            dir = dir,
            endpoint = "https://collector.example/ingest",
            appVersion = "0.1",
            token = "signature",
            send = { _, _, _ -> },
        )
        installParseSink(active) { "install-hash" }

        val result = Forge(SpecEngine(fetcher())).build(server.url("/catalog/").toString())

        // Страница открылась, движок не опознан, карточек нет: оба провала видны.
        active.pending().map { it.step + "/" + it.error } shouldBe
            listOf("forge/engine_unknown", "catalog/no_cards")
        (result is ForgeResult.Failure) shouldBe true
        // Домен без пути: адрес страницы в отчёт не уходит.
        active.pending().map { it.host }.distinct() shouldBe listOf(server.hostName)
    }
}
