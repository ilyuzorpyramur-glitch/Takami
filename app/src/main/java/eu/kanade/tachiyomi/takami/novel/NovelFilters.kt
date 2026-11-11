package eu.kanade.tachiyomi.takami.novel

import eu.kanade.tachiyomi.novelsource.model.NovelFilterList

/**
 * Фильтры ранобэ-источника по спеке.
 *
 * Жанровых параметров спека пока не описывает,
 * поэтому список пуст:
 * приложение покажет пустую панель, а не выдуманные поля.
 */
object NovelFilters {
    @Suppress("UNUSED_PARAMETER")
    fun build(spec: NovelSpec): NovelFilterList = NovelFilterList(emptyList())
}
