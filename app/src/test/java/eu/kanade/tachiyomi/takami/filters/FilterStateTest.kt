package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.FilterOption
import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Чистка состояния: чужие ключи и значения отбрасываются без исключения. */
class FilterStateTest {

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
                maxSelected = 2,
                options = listOf(FilterOption("Комедия", "comedy"), FilterOption("Драма", "drama")),
            ),
            FilterSpec(
                name = "Жанр по-другому",
                type = FilterSpec.TYPE_TRI,
                key = "mood",
                options = listOf(FilterOption("Серьёзное", "serious")),
            ),
        ),
    )

    @Test
    fun `неизвестный ключ отбрасывается`() {
        val state = FilterState(mapOf("wrong" to listOf("comedy")))

        state.clean(spec).values shouldBe emptyMap()
    }

    @Test
    fun `неизвестное значение отбрасывается`() {
        val state = FilterState(mapOf("genre" to listOf("comedy", "trash")))

        state.clean(spec).values shouldBe mapOf("genre" to listOf("comedy"))
    }

    @Test
    fun `исключение у tri-фильтра хранится с минусом`() {
        val state = FilterState(mapOf("mood" to listOf("-serious")))

        state.clean(spec).values shouldBe mapOf("mood" to listOf("-serious"))
    }

    @Test
    fun `ограничение на выбор оставляет первые значения`() {
        val state = FilterState(mapOf("genre" to listOf("drama", "comedy")))

        state.clean(spec.copy()).values shouldBe mapOf("genre" to listOf("drama", "comedy"))
    }

    @Test
    fun `пустое состояние признаётся пустым`() {
        FilterState.EMPTY.isEmpty() shouldBe true
        FilterState(mapOf("genre" to listOf("comedy"))).isEmpty() shouldBe false
    }
}
