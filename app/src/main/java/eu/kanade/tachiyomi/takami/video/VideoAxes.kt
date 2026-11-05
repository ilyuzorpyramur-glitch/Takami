package eu.kanade.tachiyomi.takami.video

/**
 * Оси, которые знает сам плеер: озвучки и число серий.
 *
 * Нужны там, где сайт их не показывает: у плеера Kodik список переводов и
 * серий лежит внутри кадра, а в разметке страницы их нет. Пустые значения
 * остаются пустыми — приписать «одна озвучка» нельзя.
 */
data class VideoAxes(
    /** Названия озвучек в порядке страницы плеера. */
    val dubbings: List<String> = emptyList(),
    /** Сколько серий у открытого перевода: 0 — плеер числа не сказал. */
    val episodes: Int = 0,
    /** Озвучка, открытая на странице плеера. */
    val current: String = "",
) {

    /** Склейка двух находок: озвучки без повторов, серии — большее число. */
    fun plus(other: VideoAxes): VideoAxes = VideoAxes(
        dubbings = (dubbings + other.dubbings).distinct(),
        episodes = maxOf(episodes, other.episodes),
        current = current.ifBlank { other.current },
    )

    /** Подпись для отчёта: пусто — плеер осей не называл. */
    fun text(): String {
        val parts = mutableListOf<String>()
        if (dubbings.isNotEmpty()) parts += "озвучек " + dubbings.size
        if (episodes > 0) parts += "серий " + episodes
        return if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
    }
}
