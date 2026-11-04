package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.FilterOption
import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Сборка адреса каталога из выбранного: query, path, sort, text. */
class FilterUrlTest {

    private fun spec(vararg filters: FilterSpec) = SiteSpec(
        host = "example.test",
        baseUrl = "https://example.test",
        name = "Тест",
        catalog = CatalogSpec(itemSelector = "div.item"),
        filters = filters.toList(),
    )

    private val genre = FilterSpec(
        name = "Жанр",
        type = FilterSpec.TYPE_CHECK,
        key = "genre",
        options = listOf(FilterOption("Комедия", "comedy"), FilterOption("Драма", "drama")),
    )

    @Test
    fun `query — параметры через амперсанд с кодированием`() {
        val state = FilterState(mapOf("genre" to listOf("comedy", "drama")))

        val url = FilterUrl.apply("https://example.test/catalog/", spec(genre), state)

        url shouldBe "https://example.test/catalog/?genre=comedy&genre=drama"
    }

    @Test
    fun `path — подстановка по шаблону`() {
        val year = FilterSpec(
            name = "Год",
            type = FilterSpec.TYPE_SELECT,
            key = "year",
            valueTemplate = "year-{v}",
            inUrl = FilterSpec.IN_PATH,
            options = listOf(FilterOption("2024", "2024")),
        )
        val state = FilterState(mapOf("year" to listOf("2024")))

        val url = FilterUrl.apply("https://example.test/catalog/", spec(year), state)

        url shouldBe "https://example.test/catalog/year-2024"
    }

    @Test
    fun `sort — один параметр`() {
        val sort = FilterSpec(
            name = "Сортировка",
            type = FilterSpec.TYPE_SORT,
            key = "sort",
            options = listOf(FilterOption("По дате", "date")),
        )
        val state = FilterState(mapOf("sort" to listOf("date")))

        val url = FilterUrl.apply("https://example.test/catalog/", spec(sort), state)

        url shouldBe "https://example.test/catalog/?sort=date"
    }

    @Test
    fun `text — значение как есть, пустое не добавляется`() {
        val text = FilterSpec(name = "Год выхода", type = FilterSpec.TYPE_TEXT, key = "q")
        val empty = FilterUrl.apply("https://example.test/catalog/", spec(text), FilterState(mapOf("q" to listOf(""))))
        val filled = FilterUrl.apply(
            "https://example.test/catalog/",
            spec(text),
            FilterState(mapOf("q" to listOf("abc"))),
        )

        empty shouldBe "https://example.test/catalog/"
        filled shouldBe "https://example.test/catalog/?q=abc"
    }

    @Test
    fun `пустое состояние не меняет адрес`() {
        val url = FilterUrl.apply("https://example.test/catalog/", spec(genre), FilterState.EMPTY)

        url shouldBe "https://example.test/catalog/"
    }

    @Test
    fun `умолчание из спеки работает, если пользователь ничего не выбрал`() {
        val status = FilterSpec(
            name = "Статус",
            type = FilterSpec.TYPE_SELECT,
            key = "status",
            default = listOf("ongoing"),
            options = listOf(FilterOption("Онгоинг", "ongoing")),
        )

        val url = FilterUrl.apply("https://example.test/catalog/", spec(status), FilterState.EMPTY)

        url shouldBe "https://example.test/catalog/?status=ongoing"
    }

    @Test
    fun `существующий запрос сохраняется, фильтры дописываются после него`() {
        val state = FilterState(mapOf("genre" to listOf("comedy")))

        val url = FilterUrl.apply("https://example.test/catalog/?page=2", spec(genre), state)

        url shouldBe "https://example.test/catalog/?page=2&genre=comedy"
    }
}
