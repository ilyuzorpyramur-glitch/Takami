package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.Forge
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Сканер фильтров на минимальной разметке: списки, флажки, сортировка.
 * Фикстура собрана руками, без выдержек из живых страниц.
 */
class FilterScanTest {

    private val html = """
        <html><body>
          <div class="menu">
            <select name="genre">
              <option value="">Все</option>
              <option value="comedy">Комедия</option>
              <option value="drama">Драма</option>
            </select>
            <div class="checks">
              <label><input type="radio" name="status" value="ongoing"> Онгоинг</label>
              <label><input type="radio" name="status" value="completed"> Завершён</label>
            </div>
            <a href="/catalog/?sort=date">По дате</a>
            <a href="/catalog/?sort=rating">По рейтингу</a>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `список превращается в фильтр без мусора`() {
        val found = Forge().scanFilters(Jsoup.parse(html))

        val genre = found.first { it.key == "genre" }
        genre.type shouldBe FilterSpec.TYPE_SELECT
        genre.options.map { it.label } shouldBe listOf("Комедия", "Драма")
        genre.options.map { it.value } shouldBe listOf("comedy", "drama")
    }

    @Test
    fun `флажки собираются по имени группы`() {
        val found = Forge().scanFilters(Jsoup.parse(html))

        val status = found.first { it.key == "status" }
        status.type shouldBe FilterSpec.TYPE_CHECK
        status.maxSelected shouldBe 1
        status.options shouldHaveSize 2
        status.selector shouldBe "div.checks input"
    }

    @Test
    fun `ссылки-сортировки дают один фильтр`() {
        val found = Forge().scanFilters(Jsoup.parse(html))

        val sort = found.first { it.type == FilterSpec.TYPE_SORT }
        sort.key shouldBe "sort"
        sort.options.map { it.label } shouldBe listOf("По дате", "По рейтингу")
        sort.options.map { it.value } shouldBe listOf("sort=date", "sort=rating")
    }

    @Test
    fun `разметка без фильтров даёт пустой список`() {
        val bare = "<html><body><p>Пусто</p></body></html>"

        Forge().scanFilters(Jsoup.parse(bare)) shouldHaveSize 0
    }
}
