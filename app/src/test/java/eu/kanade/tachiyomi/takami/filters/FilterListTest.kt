package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.FilterOption
import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** Сборка списка фильтров из спеки и обратное преобразование в состояние. */
class FilterListTest {

    private val spec = SiteSpec(
        host = "example.test",
        baseUrl = "https://example.test",
        name = "Тест",
        catalog = CatalogSpec(itemSelector = "div.item"),
        filters = listOf(
            FilterSpec(
                name = "Жанр",
                type = FilterSpec.TYPE_CHECK,
                key = "genre",
                options = listOf(FilterOption("Комедия", "comedy"), FilterOption("Драма", "drama")),
            ),
            FilterSpec(
                name = "Статус",
                type = FilterSpec.TYPE_SELECT,
                key = "status",
                options = listOf(FilterOption("Онгоинг", "ongoing"), FilterOption("Завершён", "completed")),
            ),
            FilterSpec(
                name = "Тип",
                type = FilterSpec.TYPE_TRI,
                key = "kind",
                options = listOf(FilterOption("Сериал", "tv")),
            ),
            FilterSpec(
                name = "Год выхода",
                type = FilterSpec.TYPE_TEXT,
                key = "q",
            ),
            FilterSpec(
                name = "Сортировка",
                type = FilterSpec.TYPE_SORT,
                key = "sort",
                options = listOf(FilterOption("По дате", "date")),
            ),
        ),
    )

    @Test
    fun `пустая спека даёт пустой список`() {
        val empty = spec.copy(filters = emptyList())

        FilterList.build(empty).size shouldBe 0
    }

    @Test
    fun `непустая спека даёт непустой список`() {
        FilterList.build(spec).size shouldNotBe 0
    }

    @Test
    fun `флажки отмечаются по сохранённому выбору`() {
        val saved = FilterState(mapOf("genre" to listOf("drama")))

        val list = FilterList.build(spec, saved)
        val box = list.list.filterIsInstance<AnimeFilter.CheckBox>().first { it.name == "Драма" }
        val other = list.list.filterIsInstance<AnimeFilter.CheckBox>().first { it.name == "Комедия" }

        box.state shouldBe true
        other.state shouldBe false
    }

    @Test
    fun `выбор списка читается обратно по значению`() {
        val list = FilterList.build(spec)
        val select = list.list.filterIsInstance<AnimeFilter.Select<*>>().first { it.name == "Статус" }

        select.values.size shouldBe 3
        select.values[0] shouldBe "Любой"

        val state = FilterState(mapOf("status" to listOf("completed")))

        FilterList.toState(list, spec).values shouldBe emptyMap()
        FilterList.toState(FilterList.build(spec, state), spec).values shouldBe
            mapOf("status" to listOf("completed"))
    }

    @Test
    fun `tri-фильтр различает включение и исключение`() {
        val list = FilterList.build(spec, FilterState(mapOf("kind" to listOf("-tv"))))
        val tri = list.list.filterIsInstance<AnimeFilter.TriState>().single()

        tri.isExcluded() shouldBe true

        FilterList.toState(list, spec).values shouldBe mapOf("kind" to listOf("-tv"))
    }

    @Test
    fun `текст и сортировка ходят туда и обратно`() {
        val text = FilterList.build(spec, FilterState(mapOf("q" to listOf("2024"))))
        val field = text.list.filterIsInstance<AnimeFilter.Text>().single()

        field.state shouldBe "2024"
        FilterList.toState(text, spec).values shouldBe mapOf("q" to listOf("2024"))

        val sort = FilterList.build(spec, FilterState(mapOf("sort" to listOf("date"))))
        val select = sort.list.filterIsInstance<AnimeFilter.Select<*>>().first { it.name == "Сортировка" }

        select.values[0] shouldBe "По умолчанию"
        FilterList.toState(sort, spec).values shouldBe mapOf("sort" to listOf("date"))
    }

    @Test
    fun `чужие элементы в списке не валят разбор`() {
        val list = FilterList.build(spec)
        val stranger = AnimeFilterList(list.list + object : AnimeFilter.Text("Чужое", "значение") {})

        FilterList.toState(stranger, spec).values shouldBe emptyMap()
    }
}
