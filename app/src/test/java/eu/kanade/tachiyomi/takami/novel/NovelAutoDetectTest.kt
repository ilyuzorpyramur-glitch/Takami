package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Селекторы находятся по структуре, а не по имени класса. */
class NovelAutoDetectTest {

    @Test
    fun `карточки находятся по повтору ссылки с картинкой`() {
        val card = "<div class=\"item\"><a href=\"/n/1\"><img src=\"/1.jpg"></a></div>"
        val doc = Jsoup.parse("<body>" + card.repeat(5) + "</body>")

        val found = NovelAutoDetect.catalog(doc)

        found shouldNotBe null
        found!!.itemSelector shouldBe "div.item"
        found.count shouldBe 5
    }

    @Test
    fun `главы находятся по номеру в подписи`() {
        val doc = Jsoup.parse(
            "<ul><li><a href=\"/c/1\">Глава 1</a></li>" +
                "<li><a href=\"/c/2\">Глава 2</a></li>" +
                "<li><a href=\"/c/3\">Глава 3</a></li></ul>",
        )

        NovelAutoDetect.chapters(doc)!!.count shouldBe 3
    }

    @Test
    fun `текст находится по самому большому блоку абзацев`() {
        val doc = Jsoup.parse(
            "<div class=\"read\"><p>a</p><p>b</p><p>c</p></div>" +
                "<div class=\"side\"><p>d</p></div>",
        )

        NovelAutoDetect.text(doc)!!.containerSelector shouldBe "div.read"
    }

    @Test
    fun `меню без картинок не считается каталогом`() {
        val doc = Jsoup.parse("<nav><a href=\"/a\">a</a><a href=\"/b\">b</a></nav>")

        NovelAutoDetect.catalog(doc) shouldBe null
    }
}
