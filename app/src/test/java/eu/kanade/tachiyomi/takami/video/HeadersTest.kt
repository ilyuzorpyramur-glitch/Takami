package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.SniffSpec
import eu.kanade.tachiyomi.takami.video.sniff.LinkHeaders
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Заголовки окна: доезжают до ссылки и до сетевого слоя. */
class HeadersTest {

    @Test
    fun `заголовки переносятся в готовую ссылку`() {
        val link = QualityLink(
            url = "https://cdn.example/a.m3u8",
            headers = mapOf("Referer" to "https://site.example/ep/1"),
        )

        val stream = VideoStream.of(link)

        stream.headers["Referer"] shouldBe "https://site.example/ep/1"
    }

    @Test
    fun `ссылка без заголовков даёт null, а не пустой набор`() {
        LinkHeaders.okhttp(emptyMap()) shouldBe null
    }

    @Test
    fun `карта заголовков превращается в набор для запроса`() {
        val headers = LinkHeaders.okhttp(
            mapOf("Referer" to "https://site.example/", "Origin" to "https://site.example"),
        )

        headers?.get("Referer") shouldBe "https://site.example/"
        headers?.size shouldBe 2
    }

    @Test
    fun `из спеки берутся только разрешённые заголовки`() {
        val pass = SniffSpec(passHeaders = listOf("Referer", "Origin"))
        val map = LinkHeaders.passable(pass, "https://site.example/ep/1")

        map["Referer"] shouldBe "https://site.example/ep/1"
        map["Origin"] shouldBe "https://site.example"
        map.containsKey("Cookie") shouldBe false
    }
}
