package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Оба вида карты плеера дают один список качеств. */
class PlayerMapTest {

    @Test
    fun `строка с квадратными скобками`() {
        val field = "[360p]https://cdn.example/360.mp4,[720p]https://cdn.example/720.mp4"

        val links = PlayerMap.parse(field, "AniStar")

        links.map { it.quality } shouldBe listOf("360p", "720p")
        links.map { it.url } shouldBe
            listOf("https://cdn.example/360.mp4", "https://cdn.example/720.mp4")
        links.all { it.dubbing == "AniStar" } shouldBe true
    }

    @Test
    fun `объект с числами`() {
        val field = """{ "360": "https://cdn.example/360.mp4", "720": "https://cdn.example/720.mp4" }"""

        val links = PlayerMap.parse(field)

        links.map { it.quality } shouldBe listOf("360p", "720p")
        links.size shouldBe 2
    }

    @Test
    fun `пустое поле и мусор ничего не выдумывают`() {
        PlayerMap.parse("") shouldBe emptyList()
        PlayerMap.parse("null") shouldBe emptyList()
    }

    @Test
    fun `один адрес без метки остаётся неопределённым`() {
        val links = PlayerMap.parse("""file: "https://cdn.example/video.mp4"""")

        links.single().quality shouldBe ""
        links.single().url shouldBe "https://cdn.example/video.mp4"
    }
}
