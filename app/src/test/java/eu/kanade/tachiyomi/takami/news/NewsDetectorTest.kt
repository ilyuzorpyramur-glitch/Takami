package eu.kanade.tachiyomi.takami.news

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Детект раздела: доказательства, а не догадки. Сети нет — лямбда подменяется. */
class NewsDetectorTest {

    private val baseUrl = "https://example.test/"

    private val home = """
        <html><body>
          <nav><a href="/catalog/">Каталог</a><a href="/news/">Новости</a><a href="/about/">О сайте</a></nav>
          <div class="cards"><div class="card"><a href="/a">Первый</a></div></div>
        </body></html>
    """.trimIndent()

    private val section = """
        <html><body>
          <div class="news-list">
            <div class="news-item"><a href="/news/one.html">Первый заголовок</a></div>
            <div class="news-item"><a href="/news/two.html">Второй заголовок</a></div>
            <div class="news-item"><a href="/news/three.html">Третий заголовок</a></div>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `страница с тремя карточками даёт спеку`() {
        val spec = runBlocking { NewsDetector.detect(baseUrl, home) {  section  } }

        spec.shouldNotBeNull()
        spec!!.path shouldBe "/news/"
        spec.itemSelector shouldBe ".news-item"
    }

    @Test
    fun `страница с одной карточкой даёт null`() {
        val oneCard = section.replace("two.html\">Второй заголовок</a></div>", "")
            .replace("three.html\">Третий заголовок</a></div>", "")

        runBlocking { NewsDetector.detect(baseUrl, home) {  oneCard  } }.shouldBeNull()
    }

    @Test
    fun `404 и заглушка дают null`() {
        runBlocking { NewsDetector.detect(baseUrl, home) {  null  } }.shouldBeNull()
        runBlocking { NewsDetector.detect(baseUrl, home) {  "<html><body>RSS in offline mode</body></html>"  } }.shouldBeNull()
    }

    @Test
    fun `ссылка на раздел, ведущая на ту же страницу, даёт null`() {
        val self = home.replace("/news/\">Новости", "/\">Новости")

        runBlocking { NewsDetector.detect(baseUrl, self) {  section  } }.shouldBeNull()
    }

    @Test
    fun `без ссылки-кандидата сеть не дёргается`() {
        val bare = "<html><body><p>Только каталог</p></body></html>"
        var fetched = 0

        val spec = runBlocking { NewsDetector.detect(baseUrl, bare) {  fetched++; section  } }

        spec.shouldBeNull()
        fetched shouldBe 0
    }

    @Test
    fun `кандидат находится по тексту ссылки`() {
        val doc = Jsoup.parse(home, baseUrl)

        NewsDetector.findCandidate(doc, baseUrl)?.path shouldBe "/news/"
    }

    @Test
    fun `ссылка без слова-подсказки кандидатом не становится`() {
        val noNews = home.replace("/news/\">Новости", "/list/\">Список")
        val doc = Jsoup.parse(noNews, baseUrl)

        NewsDetector.findCandidate(doc, baseUrl).shouldBeNull()
    }
}
