package eu.kanade.tachiyomi.takami.report

import eu.kanade.tachiyomi.takami.ParseFailures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Провал разбора: шаг в событии, отсутствие путей и склейка повторов. */
class ParseSinkTest {

    @TempDir
    lateinit var dir: File

    @AfterEach
    fun tearDown() {
        ParseFailures.sink = null
    }

    private fun reporter(): Reporter = Reporter(
        dir = dir,
        endpoint = "https://collector.example/ingest",
        appVersion = "0.1",
        token = "signature",
        send = { _, _, _ -> },
    )

    @Test
    fun `провал разбора кладёт событие с правильным шагом`() {
        val active = reporter()
        installParseSink(active) { "install-hash" }

        ParseFailures.noteFailure("https://site.example/title/4242?token=abc", "episodes", "no_episodes", 3)

        val event = active.pending().single()
        event.step shouldBe "episodes"
        event.error shouldBe "no_episodes"
        event.specVersion shouldBe 3
        event.installId shouldBe "install-hash"
        event.count shouldBe 1
        // Из адреса остался только домен: ни пути, ни query.
        event.host shouldBe "site.example"
    }

    @Test
    fun `повторный такой же провал не создаёт второе событие`() {
        val active = reporter()
        installParseSink(active) { "install-hash" }

        repeat(5) { ParseFailures.noteFailure("site.example", "video", "no_links", 2) }

        active.queueSize() shouldBe 1
        active.pending().single().count shouldBe 5
    }

    @Test
    fun `разные шаги одного сайта остаются разными событиями`() {
        val active = reporter()
        installParseSink(active) { "install-hash" }

        ParseFailures.noteFailure("site.example", "catalog", "no_cards", 2)
        ParseFailures.noteFailure("site.example", "episodes", "no_episodes", 2)
        ParseFailures.noteFailure("site.example", "video", "no_links", 2)

        active.pending().map { it.step }.sorted() shouldBe listOf("catalog", "episodes", "video")
    }

    @Test
    fun `без подписки сбор не падает`() {
        ParseFailures.sink = null

        ParseFailures.noteFailure("site.example", "forge", "engine_unknown", 0)

        reporter().queueSize() shouldBe 0
    }
}
