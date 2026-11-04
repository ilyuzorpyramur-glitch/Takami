package eu.kanade.tachiyomi.takami.video.sniff

import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.SniffSpec

/**
 * Кому снимок разрешён.
 *
 * Снимок — вмешательство в чужую страницу, поэтому решение принимает
 * отдельное правило, а не проверка по месту: так его видно и можно
 * проверить тестом.
 */
object SniffPolicy {

    /** Домены, которые только читаем: ни окна, ни шахты. */
    private val READ_ONLY = listOf("dreamerscast.com", "kodikdreamcast")

    /** Можно ли снимать сеть для этого источника. */
    fun canSniff(
        spec: SiteSpec,
        host: String = spec.host,
        paused: Boolean = false,
        hasEpisodes: Boolean = true,
    ): Boolean {
        if (spec.video.sniff.modeOrDefault == SniffSpec.MODE_NEVER) return false
        if (readOnly(host) || readOnly(spec.host)) return false
        if (!hasEpisodes) return false
        if (paused) return false
        return true
    }

    /** Разрешён ли конкретный слой. */
    fun layerAllowed(spec: SiteSpec, layer: String): Boolean {
        if (spec.video.sniff.modeOrDefault == SniffSpec.MODE_NEVER) return false
        if (layer == SniffLayer.SNIFF) return false
        return true
    }

    /** Домен в списке «только чтение». */
    fun readOnly(host: String): Boolean {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isEmpty()) return false
        return READ_ONLY.any { clean == it || clean.endsWith("." + it) }
    }
}
