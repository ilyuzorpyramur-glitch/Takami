package eu.kanade.tachiyomi.takami.video.sniff

/** Что просили у снимка. */
data class SniffRequest(
    val url: String,
    val referer: String = "",
    val timeoutMs: Int = 12_000,
    val settleMs: Int = 1_500,
    val maxPages: Int = 2,
)

/** Что снимок принёс: адрес, тип и канал, по которому он попал. */
data class SniffHit(val url: String, val contentType: String = "", val from: String = "")

/** Итог снимка: находки и причина остановки. */
data class SniffResult(
    val hits: List<SniffHit> = emptyList(),
    val stopReason: String = "",
)

/** Почему снимок закончился. */
object SniffStop {
    const val FOUND = "found"
    const val TIMEOUT = "timeout"
    const val BLOCKED = "blocked"
    const val DRM = "drm"
    const val UNAVAILABLE = "unavailable"
    const val CANCELLED = "cancelled"
}

/** Какой слой дал ссылку. */
object SniffLayer {
    const val STATIC = "static"
    const val CONFIG = "config"
    const val SNIFF = "sniff"

    /** Подпись слоя для экрана проверки. */
    fun text(layer: String): String = when (layer) {
        CONFIG -> "конфиг"
        SNIFF -> "снимок"
        STATIC -> "разметка"
        else -> ""
    }
}

/**
 * Решения снимка без окна: когда стартовать «дозор», когда истёк срок.
 *
 * Вынесено отдельно, чтобы проверять поведение по времени на подставных
 * данных, без WebView и без реальных задержек.
 */
class SnifferState(private val request: SniffRequest) {

    private var firstAt = -1L

    /** Сколько страниц уже открыто. */
    var pages: Int = 0
        private set

    /** Можно ли открыть ещё одну страницу. */
    fun beginPage(): Boolean {
        if (pages >= request.maxPages.coerceAtLeast(1)) return false
        pages++
        return true
    }

    /** Новая находка; true — она первая, с неё начинается «дозор». */
    fun hit(at: Long): Boolean {
        if (firstAt >= 0) return false
        firstAt = at
        return true
    }

    /** Что-то уже поймано. */
    fun found(): Boolean = firstAt >= 0

    /** «Дозор» после находки прошёл. */
    fun settled(at: Long): Boolean = firstAt >= 0 && at - firstAt >= request.settleMs

    /** Срок ожидания первой находки истёк. */
    fun expired(at: Long, startedAt: Long): Boolean =
        firstAt < 0 && at - startedAt >= request.timeoutMs
}
