package eu.kanade.tachiyomi.takami.video.kodik

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Разбор страницы Kodik и ответа плеера: без сети.
 *
 * Страница взята у живого плеера и лежит в [KodikFixture]: если разметка
 * изменится, проверка скажет об этом раньше прогона на устройстве.
 */
class KodikPageTest {

    private val html: String get() = KodikFixture.pageHtml

    private val response: String get() = KodikFixture.ftorJson

    @Test
    fun `страница плеера разбирается на три оси`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")

        page.video.id shouldBe "1672819"
        page.video.type shouldBe "seria"
        page.series.size shouldBe 6
        page.translations.size shouldBe 8
        page.seasons.size shouldBe 1
        page.episode shouldBe 1
    }

    @Test
    fun `озвучка открытой серии подписана`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")

        page.current shouldBe "AnimeVost"
        page.translations.first { it.title == "AnimeVost" }.episodes shouldBe 6
    }

    @Test
    fun `без списка переводов озвучка берётся из отметки сезона`() {
        // У страницы одной серии списка переводов может не быть: студия
        // названа в отметке сезона.
        val short = html.replace("serial-translations-box", "нет-переводов")
        val page = KodikPlayer.parse(short) ?: error("страница не разобралась")

        page.translations.size shouldBe 0
        page.current shouldBe "AnimeVost"
    }

    @Test
    fun `чужая страница не считается плеером`() {
        KodikPlayer.parse("<html><body>обычная страница</body></html>") shouldBe null
        KodikPlayer.looks("<html><body>обычная страница</body></html>") shouldBe false
        KodikPlayer.isKodik("https://" + KodikPlayer.domains.first() + "/serial/1/2/720p") shouldBe true
        KodikPlayer.isKodik("https://site.example/serial/1/2/720p") shouldBe false
    }

    @Test
    fun `адрес серии собирается из списка перевода`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")
        val translation = page.translations.first { it.title == "AnimeVost" }
        val series = page.series[1]

        page.translationUrl(translation, "https://player.example")
            .shouldStartWith("https://player.example/serial/78325/")
        page.seriesUrl(translation, series, "https://player.example")
            .shouldContain(series.hash + "/720p")
    }

    @Test
    fun `ответ плеера даёт три качества`() {
        val links = KodikLinks.parse(response, "AnimeVost", "https://player.example/serial/1/2/720p")

        links.map { it.quality }.shouldContainExactly(listOf("360p", "480p", "720p"))
        links.all { it.url.startsWith("https://") } shouldBe true
        links.all { it.dubbing == "AnimeVost" } shouldBe true
        links.all { it.headers["Referer"] == "https://player.example/serial/1/2/720p" } shouldBe true
    }

    @Test
    fun `закодированный адрес раскодируется, открытый остаётся собой`() {
        val url = KodikLinks.decode(KodikFixture.encoded)

        url.shouldStartWith("https://")
        url.shouldContain("/s/m/")
        KodikLinks.decode("https://site.example/a.m3u8") shouldBe "https://site.example/a.m3u8"
        KodikLinks.decode("") shouldBe ""
        KodikLinks.decode("не base64!") shouldBe ""
    }

    @Test
    fun `тело запроса несёт подпись страницы и номер серии`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")
        val body = KodikLinks.body(page)

        body.shouldContain("d=" + page.params["d"].orEmpty())
        body.shouldContain("pd=" + page.params["pd"].orEmpty())
        body.shouldContain("d_sign=")
        body.shouldContain("ref_sign=")
        body.shouldContain("id=" + page.video.id)
        body.shouldContain("hash=" + page.video.hash)
        body.shouldContain("type=seria")
        body.shouldContain("cdn_is_working=true")
    }

    @Test
    fun `тело запроса называет серию из списка`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")
        val series = page.series[2]
        val body = KodikLinks.body(page, series)

        body.shouldContain("id=" + series.id)
        body.shouldContain("hash=" + series.hash)
    }

    @Test
    fun `пустой ответ не даёт ссылок`() {
        KodikLinks.parse("", "AnimeVost", "") shouldBe emptyList()
        KodikLinks.parse("{\"links\":{}}", "AnimeVost", "").map { it.url } shouldBe emptyList()
    }
}
