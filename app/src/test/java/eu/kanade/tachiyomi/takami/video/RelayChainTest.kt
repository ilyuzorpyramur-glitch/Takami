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
 * разметка без кадра плеера, ответ реле (это JSON, а не страница),
 * страница плеера и запрос за ссылками.
 */
class RelayChainTest {

    private val pageUrl = "https://site.example/667-title.html"

    private val relayUrl = "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=0"

    private val player = "https://player.example/serial/1/abc/720p"

    private val relay = """{"status":true,"data":{"name":"Kodik","src":"$player"}}"""

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

    /** Страницы: кадр плеера отдаётся страницей, реле — коротким ответом. */
    private fun pages() = mapOf(player to KodikFixture.pageHtml)

    @Test
    fun `реле приводит к ссылкам плеера`() = runBlocking {
        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(16),
            post = { _, _, _ -> KodikFixture.ftorJson },
            text = { if (it == relayUrl) relay else null },
            fetch = { pages()[it] },
        )

        result.links.map { it.quality }.shouldContainExactly(listOf("360p", "480p", "720p"))
        result.links.all { it.dubbing == "AnimeVost" } shouldBe true
        result.blocked shouldBe false
    }

    @Test
    fun `без ответа реле ссылок нет, но и падения нет`() = runBlocking {
        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(16),
            post = { _, _, _ -> KodikFixture.ftorJson },
            text = { null },
            fetch = { pages()[it] },
        )

        result.links shouldBe emptyList()
    }

    @Test
    fun `без ответа на запрос ссылок нет, но и падения нет`() = runBlocking {
        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(16),
            post = { _, _, _ -> null },
            text = { if (it == relayUrl) relay else null },
            fetch = { pages()[it] },
        )

        result.links shouldBe emptyList()
    }

    @Test
    fun `потолок запросов останавливает обход`() = runBlocking {
        val result = Chain.collect(
            spec = spec(),
            html = card,
            pageUrl = pageUrl,
            dubbing = "",
            budget = Budget(1),
            post = { _, _, _ -> KodikFixture.ftorJson },
            text = { if (it == relayUrl) relay else null },
            fetch = { pages()[it] },
        )

        result.links shouldBe emptyList()
    }
}
