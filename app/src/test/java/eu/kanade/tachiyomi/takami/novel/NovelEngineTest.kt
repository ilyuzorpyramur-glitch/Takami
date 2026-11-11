package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** Разбор ранобэ-страниц без сети: только готовые образцы. */
class NovelEngineTest {

    private val engine = NovelEngine()

    private val spec = NovelSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Сайт",
        catalog = NovelCatalogSpec(itemSelector = "div.card"),
    )

    @Test
    fun `каталог собирает ссылку название и обложку`() {
        val html = """
            <div class="card">
              <a href="/novel/1" title="Тихий лес"><img src="/c/1.jpg"></a>
            </div>
            <div class="card">
              <a href="/novel/2" title="Второй том"><img src="/c/2.jpg"></a>
            </div>
        """.trimIndent()

        val (novels, _) = engine.parseCatalog(spec, html, "https://site.example/")

        novels.size shouldBe 2
        novels[0].title shouldBe "Тихий лес"
        novels[0].url shouldBe "https://site.example/novel/1"
        novels[1].thumbnail_url shouldBe "https://site.example/c/2.jpg"
    }

    @Test
    fun `главы получают номер и порядок из спеки`() {
        val html = """
            <ul><li><a href="/c/1">Глава 1</a></li>
            <li><a href="/c/2">Глава 2</a></li></ul>
        """.trimIndent()
        val chapters = engine.parseChapters(spec, html, "https://site.example/n/1")

        chapters.size shouldBe 2
        chapters[0].chapter_number shouldBe 1.0f
        chapters[1].name shouldBe "Глава 2"
    }

    @Test
    fun `новые главы при обратном порядке идут первыми`() {
        val html = """
            <ul><li><a href="/c/1">Глава 1</a></li>
            <li><a href="/c/2">Глава 2</a></li></ul>
        """.trimIndent()
        val reversed = spec.copy(chapters = spec.chapters.copy(newestFirst = true))

        engine.parseChapters(reversed, html, "https://site.example/n/1")
            .first().chapter_number shouldBe 2.0f
    }

    @Test
    fun `текст главы берётся из абзацев и без служебных блоков`() {
        val long = "Слово ".repeat(20).trim()
        val html = """
            <nav><p>меню меню меню меню меню меню меню меню меню меню</p></nav>
            <div class="read"><p>$long</p><p>$long</p>
            <div class="advert"><p>$long</p></div></div>
        """.trimIndent()

        val text = engine.parseText(spec, html, "https://site.example/c/1")

        text.contains("<p>") shouldBe true
        text.split("<p>").size - 1 shouldBe 2
    }

    @Test
    fun `дата разбирается в двух видах`() {
        NovelEngine.parseDate("12.03.2021") shouldNotBe null
        NovelEngine.parseDate("2021-03-12") shouldNotBe null
        NovelEngine.parseDate("вчера") shouldBe null
    }
}
