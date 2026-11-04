package eu.kanade.tachiyomi.ui.discovery

import tachiyomi.domain.discovery.model.DiscoveryReleaseStatus
import tachiyomi.domain.discovery.model.DiscoverySuggestion

/** Стек карточек: порядок задан подборкой, курсор двигается только вперёд и назад. */
data class SwipeStack(
    val cards: List<DiscoverySuggestion> = emptyList(),
    val index: Int = 0,
) {
    val current: DiscoverySuggestion? get() = cards.getOrNull(index)

    val remaining: Int get() = (cards.size - index).coerceAtLeast(0)

    val finished: Boolean get() = index >= cards.size

    /** Следующая карточка; за концом стека курсор не уходит. */
    fun advance(): SwipeStack = copy(index = (index + 1).coerceAtMost(cards.size))

    /** Возврат на одну карточку — для отмены скрытия. */
    fun rewind(): SwipeStack = copy(index = (index - 1).coerceAtLeast(0))

    /** Сколько карточек держать в памяти вперёд, включая текущую. */
    fun window(count: Int = SwipeLogic.PREFETCH): List<DiscoverySuggestion> =
        cards.drop(index).take(count.coerceAtLeast(1))
}

/**
 * Правила карточки без UI: шкала оценки, число серий, статус, подпись источника.
 *
 * Живёт отдельно от Compose, чтобы проверяться на подставных данных:
 * ни одного запроса, ни одной картинки.
 */
object SwipeLogic {

    /** Больше тридцати карточек в память не берём. */
    const val MAX_CARDS = 30

    /** Сколько обложек готовим вперёд. */
    const val PREFETCH = 3

    /** Оценка в шкале 0..10; ноль, отрицательное и больше десяти — «нет оценки». */
    fun rating(raw: Double?): Double? {
        val value = raw ?: return null
        if (value <= 0.0 || value > 10.0) return null
        return round(value)
    }

    /** Оценка одной цифрой после запятой; null — показываем прочерк. */
    fun ratingText(raw: Double?): String? = rating(raw)?.let(::format)

    /** Число серий; ноль и отрицательное — «нет данных». */
    fun episodes(raw: Int?): Int? = raw?.takeIf { it > 0 }

    /** Сырой статус провайдера → ключ общего вида; незнакомый — пусто. */
    fun statusKey(raw: String?): String? = statusOf(raw)?.key

    /** Сырой статус провайдера → общий статус (Shikimori, MAL, AniList). */
    fun statusOf(raw: String?): DiscoveryReleaseStatus? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null
        DiscoveryReleaseStatus.entries.firstOrNull { it.shikimoriValue == text }?.let { return it }
        DiscoveryReleaseStatus.entries.firstOrNull { it.anilistValue.lowercase() == text }?.let { return it }
        return when {
            text.startsWith("currently") || text.contains("publishing") -> DiscoveryReleaseStatus.ONGOING
            text.startsWith("finished") || text.contains("complete") -> DiscoveryReleaseStatus.FINISHED
            text.startsWith("not yet") -> DiscoveryReleaseStatus.ANONS
            text.contains("hiatus") || text.contains("discontinued") -> DiscoveryReleaseStatus.PAUSED
            else -> null
        }
    }

    /** Подпись источника оценки: имена собственные, переводу не подлежат. */
    fun sourceLabel(provider: String?): String = when (provider) {
        "shikimori_trend" -> "Shikimori"
        "jikan_trend" -> "MAL"
        "anilist_trend" -> "AniList"
        else -> ""
    }

    /** Показ засчитывается только при половине карточки и секунде на экране. */
    fun countsAsExposure(visibleFraction: Float, millis: Long): Boolean =
        visibleFraction >= 0.5f && millis >= 1_000L

    private fun round(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    private fun format(value: Double): String {
        val tenths = kotlin.math.round(value * 10.0).toInt()
        return (tenths / 10).toString() + "." + (tenths % 10).toString()
    }
}
