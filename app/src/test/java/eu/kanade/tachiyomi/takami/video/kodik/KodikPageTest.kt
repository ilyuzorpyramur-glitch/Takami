package eu.kanade.tachiyomi.takami.video.kodik

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Разбор страницы Kodik и ответа плеера: без сети.
 *
 * Страницы взяты у живого плеера и лежат в ресурсах проверки: если
 * разметка изменится, тест скажет об этом раньше прогона на устройстве.
 */
class KodikPageTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/kodik/" + name)?.readText().orEmpty()

    private val html by lazy { fixture("player.html") }

    private val response by lazy { fixture("ftor.json") }

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
        page.seriesUrl(translation, series, "https://player.example").shouldStartWith("https://player.example/serial/1")
        page.seriesUrl(translation, series, "https://player.example").endsWith("/720p") shouldBe true
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
        val encoded = "iPZ0kPU6Tg9eUBQck29aj2ZrHO4cG29bT3UdjA9pANQeG0pVVsf5WExqZhsf"

        KodikLinks.decode(encoded).shouldStartWith("https://")
        KodikLinks.decode("https://cdn.example/a.m3u8") shouldBe "https://cdn.example/a.m3u8"
        KodikLinks.decode("") shouldBe ""
        KodikLinks.decode("не base64!") shouldBe ""
    }

    @Test
    fun `тело запроса несёт подпись страницы и номер серии`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")
        val body = KodikLinks.body(page)

        body.contains("d=" + page.params["d"].orEmpty()) shouldBe true
        body.contains("pd=" + page.params["pd"].orEmpty()) shouldBe true
        body.contains("d_sign=") shouldBe true
        body.contains("ref_sign=") shouldBe true
        body.contains("id=" + page.video.id) shouldBe true
        body.contains("hash=" + page.video.hash) shouldBe true
        body.contains("type=seria") shouldBe true
        body.contains("cdn_is_working=true") shouldBe true
    }

    @Test
    fun `тело запроса называет серию из списка`() {
        val page = KodikPlayer.parse(html) ?: error("страница не разобралась")
        val series = page.series[2]
        val body = KodikLinks.body(page, series)

        body.contains("id=" + series.id) shouldBe true
        body.contains("hash=" + series.hash) shouldBe true
    }

    @Test
    fun `пустой ответ не даёт ссылок`() {
        KodikLinks.parse("", "AnimeVost", "") shouldBe emptyList()
        KodikLinks.parse("{\"links\":{}}", "AnimeVost", "").map { it.url } shouldBe emptyList()
    }
}
