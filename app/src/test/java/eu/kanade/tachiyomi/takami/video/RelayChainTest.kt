package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.VideoSpec
import eu.kanade.tachiyomi.takami.video.kodik.KodikFixture
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Цепочка с реле и Kodik на подставных ответах: без сети.
 *
 * Проверяется весь путь, который раньше обрывался на «ссылок нет»:
 * разметка без кадра плеера, ответ реле, страница плеера и запрос за
 * ссылками.
 */
class RelayChainTest {

    private val pageUrl = "https://site.example/667-title.html"

    private val card = """
        <html><body>
          <div class="tabs-block" data-player-anime-id="667">
            <div data-player-slot="0" data-player-title="Плеер Kodik"></div>
          </div>
        </body></html>
    """.trimIndent()

    private fun spec() = SiteSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Пример",
        catalog = CatalogSpec(itemSelector = "article"),
        video = VideoSpec(iframeDepth = 2, maxPlayers = 8, maxRequests = 16),
    )

    private val player = "https://player.example/serial/1/abc/720p"

    private val relay = """{"status":true,"data":{"name":"Kodik","src":"$player"}}"""

    @Test
    fun `реле приводит к ссылкам плеера`() = runBlocking {
        val pages = mapOf(
            "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=0" to relay,
            player to KodikFixture.pageHtml,
        )

        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(16),
            fetch = { pages[it] },
            post = { _, _, _ -> KodikFixture.ftorJson },
        )

        result.links.map { it.quality }.shouldContainExactly(listOf("360p", "480p", "720p"))
        result.links.all { it.dubbing == "AnimeVost" } shouldBe true
        result.blocked shouldBe false
    }

    @Test
    fun `без ответа на запрос ссылок нет, но и падения нет`() = runBlocking {
        val pages = mapOf(
            "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=0" to relay,
            player to KodikFixture.pageHtml,
        )

        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(16),
            fetch = { pages[it] },
            post = { _, _, _ -> null },
        )

        result.links shouldBe emptyList()
    }

    @Test
    fun `потолок запросов останавливает обход`() = runBlocking {
        val pages = mapOf(
            "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=0" to relay,
            player to KodikFixture.pageHtml,
        )

        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(1),
            fetch = { pages[it] },
            post = { _, _, _ -> KodikFixture.ftorJson },
        )

        result.links shouldBe emptyList()
    }
}
