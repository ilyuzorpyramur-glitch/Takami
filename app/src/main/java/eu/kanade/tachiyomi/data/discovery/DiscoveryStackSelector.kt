package eu.kanade.tachiyomi.data.discovery

import tachiyomi.domain.discovery.model.DiscoverySuggestion

/**
 * Единый отбор карточек подборки: фильтр политики по рядам и межрядовый дедуп.
 *
 * Карусель главной, лента и стек свайпов обязаны проходить один и тот же шаг,
 * иначе стек покажет то, чего нет на главной, и наоборот.
 */
object DiscoveryStackSelector {

    fun select(
        items: List<DiscoverySuggestion>,
        policy: DiscoveryCandidatePolicy,
        hidden: Set<String> = emptySet(),
        consumed: Set<String> = emptySet(),
        dismissed: Set<String> = emptySet(),
        prefilter: (DiscoverySuggestion) -> Boolean = { true },
    ): List<DiscoverySuggestion> {
        if (items.isEmpty()) return emptyList()
        val allowed = items.filter { item ->
            item.cleanTitle !in hidden && item.cleanTitle !in consumed && prefilter(item)
        }
        if (allowed.isEmpty()) return emptyList()
        val filtered = allowed
            .groupBy { it.rowType }
            .flatMap { (_, row) ->
                policy.filterRowCandidates(row, { it.genres }, scoreSelector = { it.score })
            }
        return dedupeCrossRow(filtered).filterNot { it.cleanTitle in dismissed }
    }
}
