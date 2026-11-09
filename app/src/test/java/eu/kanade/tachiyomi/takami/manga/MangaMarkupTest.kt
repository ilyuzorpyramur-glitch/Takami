package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разметка против оболочки: образцы взяты из ответов настоящих сайтов.
 *
 * Сайты с браузерной сборкой отдают каркас без карточек — их нельзя
 * путать с пустым каталогом.
 */
class MangaMarkupTest {

    @Test
    fun `оболочка Vue распознаётся как браузерная сборка`() {
        val html = """
            <div id="app"><!---->
              <script>window.current_user_country_code = 'US'; window.is_bot = rm_h.utils.isBot();</script>
            </div>
        """.trimIndent() + "x".repeat(300)

        MangaMarkup.detect(html) shouldBe MangaMarkup.JS_ONLY
    }

    @Test
    fun `каркас с пустым корнем без карточек — тоже браузерная сборка`() {
        val html = "<html><body><div id=\"root\"></div>" + "<span></span>".repeat(60) + "</body></html>"

        MangaMarkup.detect(html) shouldBe MangaMarkup.JS_ONLY
    }

    @Test
    fun `проверка браузера отличается от пустого ответа`() {
        val html = "<html><title>Just a moment...</title>" + "<div>checking your browser</div>".repeat(20) + "</html>"

        MangaMarkup.detect(html) shouldBe MangaMarkup.ANTI_BOT
    }

    @Test
    fun `серверная разметка с карточками остаётся серверной`() {
        val card = "<div class=\"card\"><a href=\"/manga/one\"><img src=\"/i/1.jpg\"></a></div>"
        val html = "<html><body><div id=\"app\">" + card.repeat(4) + "</div></body></html>"

        MangaMarkup.detect(html) shouldBe MangaMarkup.SERVER
    }

    @Test
    fun `пустой ответ не выдаёт себя за рабочий сайт`() {
        MangaMarkup.detect("") shouldBe MangaMarkup.EMPTY
        MangaMarkup.detect("<html></html>") shouldBe MangaMarkup.EMPTY
    }

    @Test
    fun `подпись называет причину`() {
        MangaMarkup.text(MangaMarkup.JS_ONLY) shouldBe "сайт собирает страницу в браузере"
        MangaMarkup.text(MangaMarkup.ANTI_BOT) shouldBe "сайт закрылся проверкой браузера"
    }
}
