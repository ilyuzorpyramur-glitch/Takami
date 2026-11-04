package eu.kanade.tachiyomi.takami.video

/**
 * Готовая ссылка для экрана: озвучка, качество, адрес и тип файла.
 *
  * Четыре поля не смешиваются: экран показывает их
  * отдельно, отчёт считает
  * оси отдельно, и «не определена» остаётся честным
  * значением, а не догадкой.
 */
data class VideoStream(
    val dubbing: String = "",
    val quality: String = "",
    val url: String,
    val kind: String = "",
    val single: Boolean = false,
    /** Заголовки окна: уходят в плеер вместе со ссылкой. */
    val headers: Map<String, String> = emptyMap(),
) {
    /** Подпись озвучки для экрана. */
    val dubbingText: String
        get() = dubbing.ifBlank { Dubbing.NONE }

    /** Подпись качества для экрана. */
    val qualityText: String
        get() = Quality.text(quality)

    /** Тип файла: hls, mp4, webm — из расширения адреса. */
    val kindText: String
        get() = kind.ifBlank { kindOfUrl(url) }

    companion object {
        fun of(link: QualityLink): VideoStream = VideoStream(
            dubbing = link.dubbing,
            quality = link.quality,
            url = link.url,
            kind = link.kind.ifBlank { kindOfUrl(link.url) },
            single = link.single,
            headers = link.headers,
        )

        /** Тип файла по адресу; пусто — тип не определить. */
        fun kindOfUrl(url: String): String {
            val clean = url.substringBefore('?').lowercase()
            return when {
                clean.endsWith(".m3u8") -> "hls"
                clean.endsWith(".mp4") -> "mp4"
                clean.endsWith(".webm") -> "webm"
                clean.contains(".m3u8") -> "hls"
                else -> ""
            }
        }
    }
}
