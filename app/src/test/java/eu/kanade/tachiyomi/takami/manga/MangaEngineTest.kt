package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Разбор манга-разметки без сети. */
class MangaEngineTest {

    private val engine = MangaEngine()

    private val spec = MangaSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Источник",
        catalog = MangaCatalogSpec(itemSelector = "div.card"),
        details = MangaDetailsSpec(
            title = "h1",
            poster = "meta[property=og:image]",
            author = "span.author",
            description = "div.description",
            status = "span.status",
        ),
        chapters = ChaptersSpec(itemSelector = "ul.chapters li", dateSelector = "span.date"),
        pages = PagesSpec(containerSelector = "div.reader"),
    )

    @Test
    fun `каталог отдаёт ссылку, название и постер`() {
        val html = """
            <div class="card">
              <a href="/manga/one" title="Первый"><img src="/i/1.jpg"></a>
            </div>
            <div class="card"><a href="/manga/two">Второй</a></div>
            <div class="card"><a href="javascript:void(0)">Мусор</a></div>
        """.trimIndent()

        val (items, _) = engine.parseCatalog(spec, html, "https://site.example/")

        items.size shouldBe 2
        items[0].url shouldBe "https://site.example/manga/one"
        items[0].title shouldBe "Первый"
        items[0].thumbnail_url shouldBe "https://site.example/i/1.jpg"
        items[1].title shouldBe "Второй"
    }

    @Test
    fun `карточка тайтла дополняется автором, жанрами и статусом`() {
        val html = """
            <h1>Полное имя</h1>
            <meta property="og:image" content="https://cdn.example/cover.jpg">
            <span class="author">Автор</span>
            <div class="description">Описание</div>
            <span class="status">Завершён</span>
        """.trimIndent()
        val base = eu.kanade.tachiyomi.source.model.SManga.create().apply {
            url = "https://site.example/manga/one"
            title = "Старое"
        }

        val result = engine.parseDetails(spec, html, base)

        result.title shouldBe "Полное имя"
        result.author shouldBe "Автор"
        result.description shouldBe "Описание"
        result.thumbnail_url shouldBe "https://cdn.example/cover.jpg"
        result.status shouldBe eu.kanade.tachiyomi.source.model.SManga.COMPLETED
    }

    @Test
    fun `главы получают номер, дату и порядок`() {
        val html = """
            <ul class="chapters">
              <li><a href="/ch/1">Глава 1</a><span class="date">01.02.2024</span></li>
              <li><a href="/ch/2">Глава 2</a><span class="date">2024-03-05</span></li>
              <li><a href="/ch/3">Глава 2.5</a><span class="date">мусор</span></li>
            </ul>
        """.trimIndent()

        val chapters = engine.parseChapters(spec, html, "https://site.example/manga/one")

        chapters.size shouldBe 3
        chapters[0].chapter_number shouldBe 1f
        chapters[0].name shouldBe "Глава 1"
        chapters[0].date_upload shouldBe MangaEngine.parseDate("01.02.2024")
        chapters[2].chapter_number shouldBe 2.5f
        chapters[2].date_upload shouldBe 0L
        chapters.last().url shouldBe "https://site.example/ch/3"
    }

    @Test
    fun `страницы берут ленивый адрес и отсеивают служебное`() {
        val html = """
            <div class="reader">
              <img data-src="/p/1.jpg" src="/placeholder.gif">
              <img data-src="/p/2.jpg">
              <img src="/p/logo.png">
              <img data-src="data:image/png;base64,AA">
              <img data-src="/p/3.jpg">
            </div>
        """.trimIndent()

        val pages = engine.parsePages(spec, html, "https://site.example/ch/1")

        pages.size shouldBe 3
        pages.map { it.index } shouldBe listOf(0, 1, 2)
        pages[0].imageUrl shouldBe "https://site.example/p/1.jpg"
        pages[2].imageUrl shouldBe "https://site.example/p/3.jpg"
    }

    @Test
    fun `предел страниц соблюдается`() {
        val body = (1..30).joinToString("") { "<img data-src=\"/p/" + it + ".jpg\">" }
        val small = spec.copy(pages = spec.pages.copy(maxPages = 5))

        engine.parsePages(small, "<div class=\"reader\">" + body + "</div>", "https://site.example/")
            .size shouldBe 5
    }

    @Test
    fun `адрес страницы каталога собирается по шаблону`() {
        engine.pageUrl("https://site.example", "/manga", eu.kanade.tachiyomi.takami.PaginationSpec(), 1) shouldBe
            "https://site.example/manga"
        engine.pageUrl("https://site.example", "manga", eu.kanade.tachiyomi.takami.PaginationSpec(), 3) shouldBe
            "https://site.example/manga/page/3/"
    }

    @Test
    fun `дата понимает два порядка и мусор`() {
        MangaEngine.parseDate("05.03.2024") shouldBe MangaEngine.parseDate("2024-03-05")
        MangaEngine.parseDate("не дата") shouldBe null
        MangaEngine.parseDate("") shouldBe null
    }
}
