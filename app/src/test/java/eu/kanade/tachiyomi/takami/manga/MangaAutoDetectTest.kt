package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Поиск селекторов по разметке.
 *
 * Разметка в образцах повторяет форму известных манга-сайтов: список
 * карточек, список глав, лента страниц.
 */
class MangaAutoDetectTest {

    private val catalogHtml = """
        <html><body>
        <nav><a href="/"><img src="/logo.png"></a>
             <a href="/manga">Каталог</a><a href="/top">Топ</a></nav>
        <div class="manga-list">
          <div class="manga-card"><a href="/manga/one" title="Первый"><img src="/i/1.jpg"></a></div>
          <div class="manga-card"><a href="/manga/two" title="Второй"><img src="/i/2.jpg"></a></div>
          <div class="manga-card"><a href="/manga/three" title="Третий"><img src="/i/3.jpg"></a></div>
          <div class="manga-card"><a href="/manga/four" title="Четвёртый"><img src="/i/4.jpg"></a></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `карточка каталога находится по повтору, а не по имени класса`() {
        val found = MangaAutoDetect.catalog(Jsoup.parse(catalogHtml))

        found.shouldNotBeNull()
        found.itemSelector shouldBe "div.manga-card"
        found.linkSelector shouldBe "a[href]"
        found.count shouldBe 4
    }

    @Test
    fun `меню без картинок не считается каталогом`() {
        val html = "<html><body><nav>" +
            (1..6).joinToString("") { "<a href=\"/p" + it + "\">Пункт</a>" } +
            "</nav></body></html>"

        MangaAutoDetect.catalog(Jsoup.parse(html)) shouldBe null
    }

    @Test
    fun `главы находятся по номеру в подписи`() {
        val html = """
            <html><body>
            <ul class="chapters">
              <li class="chapter-item"><a href="/ch/1">Глава 1</a></li>
              <li class="chapter-item"><a href="/ch/2">Глава 2</a></li>
              <li class="chapter-item"><a href="/ch/3">Глава 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        val found = MangaAutoDetect.chapters(Jsoup.parse(html))

        found.shouldNotBeNull()
        found.itemSelector shouldBe "li.chapter-item"
        found.count shouldBe 3
    }

    @Test
    fun `страницы главы — самая длинная группа картинок`() {
        val html = """
            <html><body>
            <header><div class="logo"><img src="/logo.png"></div></header>
            <div class="reader">
              <div class="page"><img class="page-image" data-src="/p/1.jpg"></div>
              <div class="page"><img class="page-image" data-src="/p/2.jpg"></div>
              <div class="page"><img class="page-image" data-src="/p/3.jpg"></div>
            </div>
            </body></html>
        """.trimIndent()

        val found = MangaAutoDetect.pages(Jsoup.parse(html))

        found.shouldNotBeNull()
        found.containerSelector shouldBe "div.page"
        found.itemSelector shouldBe "img.page-image"
        found.count shouldBe 3
    }

    @Test
    fun `пустая страница не даёт ложных находок`() {
        val doc = Jsoup.parse("<html><body><p>Ничего</p></body></html>")

        MangaAutoDetect.catalog(doc) shouldBe null
        MangaAutoDetect.chapters(doc) shouldBe null
        MangaAutoDetect.pages(doc) shouldBe null
    }
}
