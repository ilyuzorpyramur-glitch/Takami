package eu.kanade.tachiyomi.takami.video

/**
 * Разбор мастер-плейлиста HLS.
 *
  * Качается ровно один текст — мастер. Сегменты не
  * запрашиваются никогда:
  * плеер сам возьмёт нужный вариант, а нам нужен только
  * список качеств.
 */
object HlsMaster {

    private const val STREAM_INF = "#EXT-X-STREAM-INF:"

    /**
     * Варианты из мастера: метка из NAME или из RESOLUTION.
     *
      * Адрес варианта — следующая значимая строка после
      * строки с атрибутами,
     * как описано в спецификации HLS.
     */
    fun parse(text: String, dubbing: String = ""): List<QualityLink> {
        if (text.isBlank() || !text.contains(STREAM_INF)) return emptyList()
        val lines = text.lineSequence().map { it.trim() }.toList()
        val links = mutableListOf<QualityLink>()
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith(STREAM_INF)) continue
            val attributes = line.removePrefix(STREAM_INF)
            val label = attribute(attributes, "NAME")
            val height = attribute(attributes, "RESOLUTION")?.substringAfter('x', "")?.toIntOrNull()
            val quality = Quality.normalize(label).ifBlank { Quality.normalize(height?.toString()) }
            val url = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            if (url.isNullOrBlank()) continue
            links += QualityLink(url = url, dubbing = dubbing, quality = quality, kind = "hls")
        }
        return links
    }

    /** Атрибут вида RESOLUTION=1280x720 или NAME="720p". */
    private fun attribute(attributes: String, name: String): String? {
        val pattern = Regex("""(?:^|,)\s*""" + name + """=("[^"]*"|[^,]*)""")
        val value = pattern.find(attributes)?.groupValues?.get(1) ?: return null
        return value.trim().trim('"').trim()
    }
}
