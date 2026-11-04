package eu.kanade.tachiyomi.takami.video.relay

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Реле плеера: разметка, служебный адрес и ответ. Без сети. */
class PlayerRelayTest {

    private val card = """
        <html><body>
          <div class="tabs-block" data-player-anime-id="667">
            <div class="slot" data-player-slot="0" data-player-title="Плеер Kodik"></div>
            <div class="slot" data-player-slot="1" data-player-title="Плеер Aniboom"></div>
          </div>
          <a href="/engine/ajax/controller.php?mod=kepkaepisodes&amp;news_id=667">Серии</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `слоты берутся из разметки`() {
        val slots = PlayerRelay.slots(card, "https://site.example/667-title.html")

        slots.map { it.animeId + "/" + it.slot }.shouldContainExactly(listOf("667/0", "667/1"))
        slots.first().title shouldBe "Плеер Kodik"
    }

    @Test
    fun `служебные адреса собираются по слотам`() {
        val urls = PlayerRelay.endpoints(card, "https://site.example/667-title.html")

        urls shouldBe listOf(
            "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=0",
            "https://site.example/engine/ajax/controller.php?mod=player&id=667&slot=1",
        )
    }

    @Test
    fun `страница без реле не даёт адресов`() {
        PlayerRelay.endpoints("<html><body>нет плеера</body></html>", "https://site.example/1") shouldBe emptyList()
        PlayerRelay.slots("<html><body>нет плеера</body></html>", "https://site.example/1") shouldBe emptyList()
    }

    @Test
    fun `ответ реле даёт адрес плеера`() {
        val answer = """{"status":true,"data":{"name":"Kodik","src":"https://player.example/serial/1/abc/720p"}}"""

        PlayerRelay.playerOf(answer) shouldBe "https://player.example/serial/1/abc/720p"
        PlayerRelay.playerOf("<html><body>страница</body></html>") shouldBe null
    }

    @Test
    fun `шаблон заполняется номером тайтла и слота`() {
        val filled = PlayerRelay.fill("/engine/ajax/controller.php?mod=player&id={id}&slot={slot}", RelaySlot("42", 2))

        filled shouldBe "/engine/ajax/controller.php?mod=player&id=42&slot=2"
    }
}
