package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec

/**
 * Выбранное пользователем: имя параметра → выбранные значения.
 *
 * Значения хранятся как в адресе: у tri-фильтра исключение помечается минусом
 * («-значение»). Чужие ключи и значения не отсюда — их отбрасывает [clean].
 */
data class FilterState(val values: Map<String, List<String>> = emptyMap()) {

    /** Ни одного выбранного значения: адрес трогать не нужно. */
    fun isEmpty(): Boolean = values.all { it.value.isEmpty() }

    companion object {
        /** Пометка исключённого значения у tri-фильтра. */
        const val EXCLUDE_PREFIX = "-"

        val EMPTY = FilterState()
    }
}

/**
 * Оставляет только то, что описано в спеке: неизвестный ключ или значение
 * отбрасывается, а не валит приложение. Лишние значения при ограничении
 * на выбор обрезаются: остаётся первое выбранное.
 */
fun FilterState.clean(spec: SiteSpec): FilterState {
    val kept = spec.filters.associate { filter ->
        filter.key to allowedValues(filter, values[filter.key].orEmpty())
    }.filterValues { it.isNotEmpty() }
    return FilterState(kept)
}

/** Значения фильтра, которые спека признаёт своими. */
private fun allowedValues(filter: FilterSpec, raw: List<String>): List<String> {
    if (filter.type == FilterSpec.TYPE_TEXT) {
        return raw.filter { it.isNotBlank() }.take(if (filter.maxSelected > 0) filter.maxSelected else 1)
    }
    val known = filter.options.map { it.value }.toSet()
    val valid = raw.filter { value ->
        value.isNotBlank() && (value in known || value.removePrefix(FilterState.EXCLUDE_PREFIX) in known)
    }
    return if (filter.maxSelected > 0) valid.take(filter.maxSelected) else valid.distinct()
}
