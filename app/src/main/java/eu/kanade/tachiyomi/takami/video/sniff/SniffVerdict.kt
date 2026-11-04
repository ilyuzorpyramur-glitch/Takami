package eu.kanade.tachiyomi.takami.video.sniff

/**
 * Честный итог вместо нуля.
 *
 * Ноль без причины — это не ответ: отчёт и проверка должны различать
 * «сайт не дал ссылку», «плеер закрыт», «только DRM» и «недоступно по
 * лицензии».
 */
object SniffVerdict {

    const val OK = "ok"
    const val NO_VIDEO = "no_video"
    const val NO_LINKS = "no_links"
    const val BLOCKED = "blocked"
    const val DRM = "drm"
    const val UNAVAILABLE = "unavailable"

    /** Итог по находкам, причине остановки и проверке потока. */
    fun of(result: SniffResult, links: Int, plays: Boolean): String {
        if (links > 0) return if (plays) OK else NO_VIDEO
        return when (result.stopReason) {
            SniffStop.UNAVAILABLE -> UNAVAILABLE
            SniffStop.DRM -> DRM
            SniffStop.BLOCKED, SniffStop.TIMEOUT -> BLOCKED
            else -> NO_LINKS
        }
    }

    /** Подпись для экрана проверки. */
    fun text(verdict: String, links: Int, layer: String): String = when (verdict) {
        OK -> links.toString() + " ссылок (" + SniffLayer.text(layer) + ")"
        NO_VIDEO -> "ссылка не отдаёт поток"
        BLOCKED -> "плеер не раскрылся"
        DRM -> "только DRM"
        UNAVAILABLE -> "недоступно по лицензии"
        else -> "ссылок нет"
    }
}
