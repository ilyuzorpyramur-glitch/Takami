package eu.kanade.tachiyomi.takami.filters

import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import java.net.URLEncoder

/**
 * Собирает адрес каталога из базового адреса и выбранного.
 *
 * Ничего не знает про сайты: значения подставляются по шаблонам из спеки.
 * Пустое состояние адрес не меняет вообще — ни знака вопроса, ни пустых параметров.
 */
object FilterUrl {

    /**
     * [base] — адрес каталога или поиска без выбора; [state] — выбранное
     * пользователем. Если пользователь ключ не трогал, работает [FilterSpec.default].
     */
    fun apply(base: String, spec: SiteSpec, state: FilterState): String {
        val chosen = effective(spec, state)
        if (chosen.values.values.all { it.isEmpty() }) return base

        val question = base.indexOf('?')
        val basePath = if (question >= 0) base.substring(0, question) else base
        val savedQuery = if (question >= 0) base.substring(question + 1) else ""
        var path = basePath
        val added = mutableListOf<String>()

        for (filter in spec.filters) {
            val raw = chosen.values[filter.key].orEmpty()
            for (value in raw) {
                when (filter.inUrl) {
                    FilterSpec.IN_PATH -> {
                        val piece = filter.valueTemplate.replace("{v}", encode(value))
                        path = path.trimEnd('/') + "/" + piece.trimStart('/')
                    }
                    else -> added.add(filter.key + "=" + encode(filter.valueTemplate.replace("{v}", value)))
                }
            }
        }
        // Путь и запрос считаются порознь: path-фильтр не должен теряться
        // из-за того, что в базовом адресе уже был запрос.
        if (path == basePath && added.isEmpty()) return base

        val params = buildList {
            if (savedQuery.isNotBlank()) add(savedQuery)
            addAll(added)
        }
        return if (params.isEmpty()) path else path + "?" + params.joinToString("&")
    }

    /** Выбранное пользователя плюс умолчания из спеки по нетронутым ключам. */
    private fun effective(spec: SiteSpec, state: FilterState): FilterState {
        val cleaned = state.clean(spec)
        val merged = spec.filters.associate { filter ->
            val user = cleaned.values[filter.key].orEmpty()
            filter.key to if (user.isEmpty()) filter.default else user
        }.filterValues { it.isNotEmpty() }
        return FilterState(merged)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
