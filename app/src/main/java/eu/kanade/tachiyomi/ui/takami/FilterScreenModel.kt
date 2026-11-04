package eu.kanade.tachiyomi.ui.takami

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.filters.FilterList
import eu.kanade.tachiyomi.takami.filters.FilterState
import eu.kanade.tachiyomi.takami.filters.FilterStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Экран «Фильтры источника»: список источников, черновик выбора, сохранение.
 *
 * Отдельная навигация, отдельный файл: на экран выбора качества и озвучки
 * из этапа 7 этот экран не опирается.
 */
class FilterScreenModel : ScreenModel {

    private val _editing = MutableStateFlow<SiteSpec?>(null)
    val editing: StateFlow<SiteSpec?> = _editing.asStateFlow()

    private val _draft = MutableStateFlow(AnimeFilterList())
    val draft: StateFlow<AnimeFilterList> = _draft.asStateFlow()

    /** Открывает источник: черновик собирается из спеки и сохранённого выбора. */
    fun open(spec: SiteSpec) {
        _editing.value = spec
        _draft.value = FilterList.build(spec, FilterState(FilterStore.load(spec.host)))
    }

    fun close() {
        _editing.value = null
        _draft.value = AnimeFilterList()
    }

    /** Переключает флажок: элемент заменяется новым, чтобы экран перерисовался. */
    fun toggle(index: Int) {
        update(index) { item ->
            when (item) {
                is AnimeFilter.CheckBox -> object : AnimeFilter.CheckBox(item.name, !item.state) {}
                else -> item
            }
        }
    }

    /** Трёхпозиционный: игнор → включить → исключить → игнор. */
    fun cycle(index: Int) {
        update(index) { item ->
            when (item) {
                is AnimeFilter.TriState -> object : AnimeFilter.TriState(item.name, nextState(item.state)) {}
                else -> item
            }
        }
    }

    /** Список и сортировка: выбранный пункт по позиции. */
    fun choose(index: Int, position: Int) {
        update(index) { item ->
            when (item) {
                is AnimeFilter.Select<*> -> object :
                    AnimeFilter.Select<String>(item.name, item.values.map { it.toString() }.toTypedArray(), position) {}
                else -> item
            }
        }
    }

    /** Свободный ввод: значение как набрано. */
    fun type(index: Int, value: String) {
        update(index) { item ->
            when (item) {
                is AnimeFilter.Text -> object : AnimeFilter.Text(item.name, value) {}
                else -> item
            }
        }
    }

    /** Сколько значений выбрано: цифра на кнопке «Применить». */
    fun count(): Int {
        val spec = _editing.value ?: return 0
        return FilterList.toState(_draft.value, spec).values.values.sumOf { it.size }
    }

    /** Применить: выбор уходит в файл, экран возвращается к каталогу. */
    fun applyChoice() {
        val spec = _editing.value ?: return
        FilterStore.save(spec.host, FilterList.toState(_draft.value, spec).values)
        close()
    }

    /** Сбросить: сохранённый выбор стирается, черновик возвращается к пустому. */
    fun reset() {
        val spec = _editing.value ?: return
        FilterStore.save(spec.host, emptyMap())
        _draft.value = FilterList.build(spec)
    }

    private fun update(index: Int, transform: (AnimeFilter<*>) -> AnimeFilter<*>) {
        val items = _draft.value.list.toMutableList()
        val current = items.getOrNull(index) ?: return
        items[index] = transform(current)
        _draft.value = AnimeFilterList(items)
    }

    private fun nextState(state: Int): Int = when (state) {
        AnimeFilter.TriState.STATE_IGNORE -> AnimeFilter.TriState.STATE_INCLUDE
        AnimeFilter.TriState.STATE_INCLUDE -> AnimeFilter.TriState.STATE_EXCLUDE
        else -> AnimeFilter.TriState.STATE_IGNORE
    }
}
