package eu.kanade.tachiyomi.takami.video.sniff

import eu.kanade.tachiyomi.takami.SniffSpec

/**
 * Что оставляем из пойманного окном.
 *
 * Окно ловит всё: сегменты, картинки, рекламу, аналитику. Нам нужны только
 * медиа-адреса, причём мастер важнее отдельного файла: из мастера видно все
 * качества.
 */
object MediaFilter {

    /** Типы содержимого, которые считаем медиа. */
    private val CONTENT = mapOf(
        "application/vnd.apple.mpegurl" to 2,
        "application/x-mpegurl" to 2,
        "audio/mpegurl" to 2,
        "application/dash+xml" to 1,
        "video/mp4" to 1,
        "video/webm" to 1,
    )

    /** Что не берём никогда: сегменты, вложения и реклама. */
    private val DROP_EXT = listOf(".ts", ".m4s", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".woff", ".woff2")

    private val DROP_SCHEME = listOf("blob:", "data:", "about:", "javascript:")

    private val ADS = listOf("doubleclick", "/ads/", "adserver", "analytics", "googletagmanager", "hotjar")

    private const val MASTER_TEXT = "#EXT-X-STREAM-INF"

    /**
     * Отбор, ранг и дедуп.
     *
     * @param master адреса, в теле которых видно мастер-плейлист.
     */
    fun keep(hits: List<SniffHit>, spec: SniffSpec, master: Set<String> = emptySet()): List<SniffHit> {
        val deny = spec.denyHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val kept = hits.filter { hit ->
            val url = hit.url.trim()
            url.isNotEmpty() && !dropped(url) && !denied(url, deny) && pattern(url, spec, hit.contentType)
        }
        return kept
            .distinctBy { it.url }
            .sortedByDescending { rank(it, master) }
    }

    /** Ранг: мастер HLS, затем отдельный файл, затем обычный плейлист. */
    fun rank(hit: SniffHit, master: Set<String>): Int {
        val url = hit.url.substringBefore('?').lowercase()
        return when {
            master.contains(hit.url) || master.contains(hit.url.substringBefore('?')) -> 3
            url.endsWith(".mp4") || url.endsWith(".webm") -> 2
            url.endsWith(".m3u8") || contentType(hit).endsWith("mpegurl") -> 1
            url.endsWith(".mpd") -> 1
            else -> 0
        }
    }

    /** Мастер ли это по телу ответа. */
    fun isMaster(body: String): Boolean = body.contains(MASTER_TEXT)

    private fun contentType(hit: SniffHit): String = hit.contentType.substringBefore(';').trim().lowercase()

    private fun dropped(url: String): Boolean {
        val lower = url.lowercase()
        if (DROP_SCHEME.any { lower.startsWith(it) }) return true
        if (ADS.any { lower.contains(it) }) return true
        val path = lower.substringBefore('?')
        return DROP_EXT.any { path.endsWith(it) }
    }

    private fun denied(url: String, deny: List<String>): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return deny.any { host == it || host.endsWith("." + it) }
    }

    /** Годится ли адрес и тип по спеке. */
    private fun pattern(url: String, spec: SniffSpec, contentType: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        val byUrl = spec.mediaPatterns.any { lower.endsWith(it.lowercase()) }
        val byType = CONTENT.containsKey(contentType.substringBefore(';').trim().lowercase())
        return byUrl || byType
    }
}
