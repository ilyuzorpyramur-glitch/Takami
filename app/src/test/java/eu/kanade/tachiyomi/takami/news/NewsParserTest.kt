package eu.kanade.tachiyomi.takami.news

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Разбор лент и раздела: фикстуры руками. */
class NewsParserTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <item>
            <title>Первая запись</title>
            <link>https://example.test/news/one.html</link>
            <description>&lt;p&gt;Короткий анонс&lt;/p&gt; первой записи,
            второй абзац.</description>
            <pubDate>Wed, 04 Nov 2026 10:00:00 +0300</pubDate>
          </item>
          <item>
            <title>Запись без ссылки</title>
            <description>Тут ссылки нет</description>
          </item>
          <item>
            <link>https://example.test/news/three.html</link>
            <description>Без заголовка</description>
          </item>
        </channel></rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>Атомная запись</title>
            <link rel="alternate" href="https://example.test/blog/one.html"/>
            <summary>Анонс первой атомной записи</summary>
            <updated>2026-11-04T08:00:00Z</updated>
          </entry>
          <entry>
            <title>Без ссылки</title>
            <summary>Ссылки нет</summary>
          </entry>
        </feed>
    """.trimIndent()

    private val section = """
        <html><body>
          <div class="news-list">
            <div class="news-item"><a href="/news/one.html">Заголовок первой</a>
            <time>04.11.2026</time><p>Анонс первой записи</p></div>
            <div class="news-item"><a href="/news/two.html">Заголовок второй</a>
            <p>Анонс второй записи</p></div>
            <div class="news-item"><p>Ссылки нет</p></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `rss даёт записи с заголовком, ссылкой, анонсом и датой`() {
        val items = NewsParser.parseRss(rss)

        items.size shouldBe 1
        val first = items.single()
        first.url shouldBe "https://example.test/news/one.html"
        first.title shouldBe "Первая запись"
        first.snippet shouldBe "Короткий анонс первой записи, второй абзац."
        first.publishedAt shouldNotBeNull()
    }

    @Test
    fun `записи без ссылки или без заголовка отбрасываются`() {
        NewsParser.parseRss(rss).size shouldBe 1
        NewsParser.parseAtom(atom).size shouldBe 1
    }

    @Test
    fun `atom даёт ссылку из alternate и дату updated`() {
        val item = NewsParser.parseAtom(atom).single()

        item.url shouldBe "https://example.test/blog/one.html"
        item.title shouldBe "Атомная запись"
        item.snippet shouldBe "Анонс первой атомной записи"
        item.publishedAt shouldNotBeNull()
    }

    @Test
    fun `раздел по спеке даёт две записи из трёх карточек`() {
        val spec = NewsSpec(path = "/news/", itemSelector = "div.news-item")

        val items = NewsParser.parseSection(section, "https://example.test/", spec)

        items.size shouldBe 2
        items[0].url shouldBe "https://example.test/news/one.html"
        items[0].title shouldBe "Заголовок первой"
        items[0].snippet shouldBe "Анонс первой записи"
    }

    @Test
    fun `относительные адреса склеиваются с базовым`() {
        val spec = NewsSpec(path = "/news/", itemSelector = "div.news-item")

        NewsParser.parseSection(section, "https://example.test/", spec).first().url shouldBe
            "https://example.test/news/one.html"
    }

    @Test
    fun `анонс обрезается по словам и по строкам`() {
        val long = "<p>" + ("слово ".repeat(80)).trim() + "</p>"
        val snippet = NewsParser.snippetOf(long)

        snippet.contains('<') shouldBe false
        snippet.contains('\n') shouldBe false
        snippet.split(' ').size shouldBe 30
        snippet.length shouldBe 180 - 1
    }

    @Test
    fun `кривая дата даёт пустую строку, а не начало эпохи`() {
        NewsParser.parseDate("не дата").shouldBeNull()
        NewsParser.parseDate("2026-11-04T10:00:00+03:00") shouldNotBeNull()
    }

    @Test
    fun `заглушка распознаётся как пустая страница`() {
        isStubPage("") shouldBe true
        isStubPage("<html><body>RSS in offline mode</body></html>") shouldBe true
        isStubPage(section) shouldBe false
    }

    @Test
    fun `дедуп по адресу записи`() {
        val items = listOf(
            NewsItem(url = "https://example.test/a", title = "Раз", source = "Сайт"),
            NewsItem(url = "https://example.test/a", title = "Другой заголовок", source = "Сайт"),
        )
        dedupNews(items).size shouldBe 1
    }

    @Test
    fun `дедуп по нормализованному заголовку`() {
        val items = listOf(
            NewsItem(url = "https://example.test/a", title = "Обычная запись!", source = "Сайт"),
            NewsItem(url = "https://example.test/b", title = "Обычная   запись", source = "Сайт"),
        )
        dedupNews(items).size shouldBe 1
    }

    @Test
    fun `сортировка по дате, записи без даты в конце`() {
        val items = listOf(
            NewsItem(url = "https://example.test/a", title = "Без даты", source = "Сайт"),
            NewsItem(url = "https://example.test/b", title = "Старая", publishedAt = 1000L, source = "Сайт"),
            NewsItem(url = "https://example.test/c", title = "Свежая", publishedAt = 2000L, source = "Сайт"),
        )

        val sorted = sortNews(items)

        sorted.map { it.title } shouldBe listOf("Свежая", "Старая", "Без даты")
    }
}
