package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.takami.FilterOption
import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec

/**
 * Двусторонняя связь спеки и интерфейса: спека → список фильтров для экрана,
 * выбор пользователя обратно в [FilterState].
 *
 * Порядок совпадает в обе стороны, поэтому обратное преобразование не теряет
 * значения: пункты идут в том же порядке, в каком были собраны.
 */
object FilterList {

    /** Заголовок группы на экране фильтров. */
    private const val GROUP = "Фильтры"
    private const val ANY = "Любой"
    private const val DEFAULT = "По умолчанию"

    /** Спека → элементы экрана. Состояние каждого элемента берётся из [saved]. */
    fun build(spec: SiteSpec, saved: FilterState = FilterState.EMPTY): AnimeFilterList {
        if (spec.filters.isEmpty()) return AnimeFilterList()
        val items = mutableListOf<AnimeFilter<*>>(AnimeFilter.Header(GROUP))
        for (filter in spec.filters) {
            val chosen = saved.values[filter.key].orEmpty()
            when (filter.type) {
                FilterSpec.TYPE_CHECK ->
                    if (filter.maxSelected == 1) {
                        items.add(select(filter, chosen, ANY))
                    } else {
                        items.add(AnimeFilter.Header(filter.name))
                        for (option in filter.options) {
                            items.add(checkbox(option, option.value in chosen))
                        }
                    }
                FilterSpec.TYPE_TRI -> {
                    items.add(AnimeFilter.Header(filter.name))
                    for (option in filter.options) {
                        items.add(triState(option, chosen))
                    }
                }
                FilterSpec.TYPE_SELECT -> items.add(select(filter, chosen, ANY))
                FilterSpec.TYPE_SORT -> items.add(select(filter, chosen, DEFAULT))
                FilterSpec.TYPE_TEXT -> items.add(
                    object : AnimeFilter.Text(filter.name, chosen.firstOrNull().orEmpty()) {},
                )
            }
        }
        return AnimeFilterList(items)
    }

    /** Экран → спека: неизвестные ключи и значения отбрасывает [clean]. */
    fun toState(list: AnimeFilterList, spec: SiteSpec): FilterState {
        val actual = list.filterNot { it is AnimeFilter.Header || it is AnimeFilter.Separator }
        val values = mutableMapOf<String, MutableList<String>>()
        var index = 0
        for (filter in spec.filters) {
            for (entry in plan(filter)) {
                val item = actual.getOrNull(index) ?: break
                index++
                if (!entry.clazz.isInstance(item)) continue
                val value = valueOf(item, filter, entry.option) ?: continue
                values.getOrPut(filter.key) { mutableListOf() }.add(value)
            }
        }
        return FilterState(values).clean(spec)
    }

    /** Чего ждать от одного фильтра на экране: класс элемента и его пункт. */
    private data class Entry(val clazz: Class<out AnimeFilter<*>>, val option: FilterOption?)

    /** Планы строятся в том же порядке, что и элементы в [build]. */
    private fun plan(filter: FilterSpec): List<Entry> = when (filter.type) {
        FilterSpec.TYPE_CHECK ->
            if (filter.maxSelected == 1) {
                listOf(Entry(AnimeFilter.Select::class.java, null))
            } else {
                filter.options.map { Entry(AnimeFilter.CheckBox::class.java, it) }
            }
        FilterSpec.TYPE_TRI -> filter.options.map { Entry(AnimeFilter.TriState::class.java, it) }
        FilterSpec.TYPE_SELECT, FilterSpec.TYPE_SORT -> listOf(Entry(AnimeFilter.Select::class.java, null))
        FilterSpec.TYPE_TEXT -> listOf(Entry(AnimeFilter.Text::class.java, null))
        else -> emptyList()
    }

    /** Значение одного элемента экрана; null — элемент ничего не выбрал. */
    private fun valueOf(item: AnimeFilter<*>, filter: FilterSpec, option: FilterOption?): String? = when (item) {
        is AnimeFilter.Select<*> -> {
            val state = item.state
            if (state > 0 && state <= filter.options.size) filter.options[state - 1].value else null
        }
        is AnimeFilter.CheckBox ->
            if (item.state) option?.value else null
        is AnimeFilter.TriState -> when {
            item.isIncluded() -> option?.value
            item.isExcluded() -> FilterState.EXCLUDE_PREFIX + (option?.value ?: "")
            else -> null
        }
        is AnimeFilter.Text -> item.state.takeIf { it.isNotBlank() }
        else -> null
    }

    /** Список с пустым пунктом впереди: нулевой индекс значит «ничего не выбрано». */
    private fun select(filter: FilterSpec, chosen: List<String>, empty: String): AnimeFilter.Select<String> {
        val labels = listOf(empty) + filter.options.map { it.label }
        val index = filter.options.indexOfFirst { it.value in chosen }
        return object : AnimeFilter.Select<String>(filter.name, labels.toTypedArray(), index + 1) {}
    }

    private fun checkbox(option: FilterOption, state: Boolean): AnimeFilter.CheckBox =
        object : AnimeFilter.CheckBox(option.label, state) {}

    /** Включённое значение попадает в адрес, исключённое — с минусом. */
    private fun triState(option: FilterOption, chosen: List<String>): AnimeFilter.TriState {
        val state = when {
            FilterState.EXCLUDE_PREFIX + option.value in chosen -> AnimeFilter.TriState.STATE_EXCLUDE
            option.value in chosen -> AnimeFilter.TriState.STATE_INCLUDE
            else -> AnimeFilter.TriState.STATE_IGNORE
        }
        return object : AnimeFilter.TriState(option.label, state) {}
    }
}
