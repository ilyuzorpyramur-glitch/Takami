package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Декодер плеера: шесть видов поля file и мусор на входе. */
class PlayerjsTest {

    @Test
    fun `строковая карта`() {
        val html = """var p = new Playerjs({id:"v", file:"[360p]//cdn.example/a.mp4,[720p]//cdn.example/b.mp4"});"""

        val links = Playerjs.parse(html, "Студия")

        links.map { it.quality } shouldBe listOf("360p", "720p")
        links.first().url shouldBe "https://cdn.example/a.mp4"
        links.all { it.dubbing == "Студия" } shouldBe true
    }

    @Test
    fun `плейлист массивом объектов`() {
        val html = "file:\"[{\"\"title\"\":\"\"360p\"\",\"\"file\"\":\"\"//cdn.example/a.mp4\"\"}," +
            "{\"\"title\"\":\"\"720p\"\",\"\"file\"\":\"\"//cdn.example/b.mp4\"\"}]\""

        val links = Playerjs.parse(html)

        links.map { it.quality } shouldBe listOf("360p", "720p")
        links.map { it.url } shouldBe
            listOf("https://cdn.example/a.mp4", "https://cdn.example/b.mp4")
    }

    @Test
    fun `объектная карта`() {
        val html = """file:{"360":"//cdn.example/a.mp4","720":"//cdn.example/b.mp4"}"""

        Playerjs.parse(html).map { it.quality } shouldBe listOf("360p", "720p")
    }

    @Test
    fun `массив адресов без метки`() {
        val html = """file:["//cdn.example/a.mp4","//cdn.example/b.m3u8"]"""

        val links = Playerjs.parse(html)

        links.size shouldBe 2
        links.all { it.quality == "" } shouldBe true
    }

    @Test
    fun `одиночный адрес без метки`() {
        Playerjs.parse("""file:"//cdn.example/index.m3u8"""").single().url shouldBe
            "https://cdn.example/index.m3u8"
    }

    @Test
    fun `base64 раскодируется один уровень`() {
        val html = """file:"WzcyMHBdLy9jZG4uZXhhbXBsZS9hYmMubXA0"""

        val links = Playerjs.parse(html)

        links.single().quality shouldBe "720p"
        links.single().url shouldBe "https://cdn.example/abc.mp4"
    }

    @Test
    fun `битый base64 даёт пусто без исключения`() {
        Playerjs.parse("""file:"AAAAAAAAAAAAAAAAAAAAAAAAAAAA"""") shouldBe emptyList()
        Playerjs.parse("""file:"!!!!-!!!!-!!!!-!!!!-!!!!-!!!!"""") shouldBe emptyList()
        Playerjs.parse("""file:"Zhbm90aHR0cHdpdGhvdXRzbGFzaGVzMTIzNDU2"""") shouldBe emptyList()
    }

    @Test
    fun `мусор и пустой вход дают пустой список`() {
        Playerjs.parse("") shouldBe emptyList()
        Playerjs.parse("<html><body>нет плеера</body></html>") shouldBe emptyList()
        Playerjs.parse("""file:null""") shouldBe emptyList()
        Playerjs.parse("""file:""") shouldBe emptyList()
    }
}
