package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.HosterSpec
import eu.kanade.tachiyomi.takami.SiteSpec

/**
 * Плеер, описанный в спеке.
 *
 * Знание «это playerjs, карту надо спросить отдельно» живёт в полях спеки,
 * а не в ветках кода: здесь только сопоставление адреса с описанием.
 */
object Hoster {

    private const val ID = "{id}"

    /** Какой из описанных в спеке плееров это; null — не описан. */
    fun match(spec: SiteSpec, url: String): HosterSpec? {
        if (url.isBlank()) return null
        return spec.video.hosters.firstOrNull { hoster -> matches(hoster.urlPattern, url) }
    }

    /** Адрес запроса карты: подстановка {id} в apiTemplate; null — не спрашиваем. */
    fun apiUrl(hoster: HosterSpec, pageUrl: String): String? {
        val template = hoster.apiTemplate?.trim().orEmpty()
        if (template.isEmpty()) return null
        val id = if (ID in template) idOf(hoster, pageUrl) else ""
        if (ID in template && id.isBlank()) return null
        val url = MediaUrl.normalize(template.replace(ID, id), pageUrl)
        return url.takeIf { it.isNotEmpty() }
    }

    /** Идентификатор плеера из адреса; пусто — не нашли. */
    fun idOf(hoster: HosterSpec, url: String): String {
        val pattern = hoster.idRegex?.takeIf { it.isNotBlank() } ?: hoster.urlPattern
        val match = find(pattern, url) ?: return ""
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun matches(pattern: String, url: String): Boolean =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url) }
            .getOrDefault(false)

    private fun find(pattern: String, url: String): MatchResult? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE).find(url) }.getOrNull()
}
