package eu.kanade.tachiyomi.takami.video

/**
 * Карта качеств внутри скрипта плеера.
 *
  * Плееры кладут список файлов двумя способами: строкой
  * «[360p]адрес,[720p]адрес»
 * и объектом { "360": "адрес", "720": "адрес" }. Оба вида дают один словарь.
 */
object PlayerMap {

    private val PAIR = Regex("""\[([^\]]{1,16})]\s*((?:https?:)?//[^\s,"'\]]+)""")

    private val OBJECT = Regex("""["']?(\d{3,4}p?)["']?\s*:\s*["']((?:https?:)?//[^"']+)["']""")

    private val PLAIN = Regex("""["']((?:https?:)?//[^"']+\.(?:m3u8|mp4|webm)[^"']*)["']""")

    /** Ссылки из поля file; мусор и пустое поле дают пустой список. */
    fun parse(field: String, dubbing: String = ""): List<QualityLink> {
        val text = field.trim()
        if (text.isEmpty()) return emptyList()
        return when {
            text.startsWith("{") -> fromObject(text, dubbing)
            text.contains('[') -> fromPairs(text, dubbing)
            else -> fromPlain(text, dubbing)
        }
    }

    private fun fromPairs(text: String, dubbing: String): List<QualityLink> =
        PAIR.findAll(text).mapNotNull { match ->
            val url = match.groupValues[2].trim()
            if (url.isEmpty()) return@mapNotNull null
            QualityLink(
                url = url,
                dubbing = dubbing,
                quality = Quality.normalize(match.groupValues[1]),
                kind = "map",
            )
        }.toList()

    private fun fromObject(text: String, dubbing: String): List<QualityLink> =
        OBJECT.findAll(text).map { match ->
            QualityLink(
                url = match.groupValues[2].trim(),
                dubbing = dubbing,
                quality = Quality.normalize(match.groupValues[1]),
                kind = "map",
            )
        }.toList()

    /** Один адрес без метки: качество остаётся неопределённым. */
    private fun fromPlain(text: String, dubbing: String): List<QualityLink> =
        PLAIN.findAll(text).map { match ->
            QualityLink(url = match.groupValues[1].trim(), dubbing = dubbing, kind = "map")
        }.toList()
}
