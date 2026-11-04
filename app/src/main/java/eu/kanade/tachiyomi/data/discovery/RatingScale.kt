package eu.kanade.tachiyomi.data.discovery

import tachiyomi.domain.discovery.model.DiscoveryReleaseStatus

/**
 * Приведение оценки провайдера к одной шкале 0..10.
 *
 * Ноль и отсутствие значения одинаково значат «оценки нет»: подставлять
 * ноль или чужое среднее нельзя, карточка покажет прочерк.
 */
object RatingScale {

    fun fromTen(raw: Double?): Double? = raw?.takeIf { it > 0.0 && it <= 10.0 }

    fun fromHundred(raw: Double?): Double? = raw?.takeIf { it > 0.0 && it <= 100.0 }?.div(10.0)

    /** Сырой статус провайдера → общий ключ; незнакомый — пусто. */
    fun statusKey(raw: String?): String? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null
        DiscoveryReleaseStatus.entries.firstOrNull { it.shikimoriValue == text }?.let { return it.key }
        DiscoveryReleaseStatus.entries.firstOrNull { it.anilistValue.lowercase() == text }?.let { return it.key }
        return when {
            text.startsWith("currently") || text.contains("publishing") -> DiscoveryReleaseStatus.ONGOING.key
            text.startsWith("finished") || text.contains("complete") -> DiscoveryReleaseStatus.FINISHED.key
            text.startsWith("not yet") -> DiscoveryReleaseStatus.ANONS.key
            text.contains("hiatus") || text.contains("discontinued") -> DiscoveryReleaseStatus.PAUSED.key
            else -> null
        }
    }
}
