package eu.kanade.tachiyomi.takami.manga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Фильтры манга-источника по спеке.
 *
 * Экран источников строит список без
 * кода сайта: спека говорит, какие
 * поля показать. Фильтров по жанрам у
 * спеки пока нет, поэтому список
 * честно пустой — приложение покажет
 * пустую панель, а не выдуманные
 * поля.
 */
object MangaFilters {
    @Suppress("UNUSED_PARAMETER")
    fun build(spec: MangaSpec): FilterList = FilterList(emptyList())
}
