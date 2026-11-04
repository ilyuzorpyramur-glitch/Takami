package eu.kanade.tachiyomi.takami.video

/**
 * Ссылка на видео с двумя независимыми осями.
 *
  * Озвучка и качество не смешиваются: у каждой ссылки своя
  * пара. Если ось
  * определить не удалось, она остаётся пустой строкой —
  * подставлять первое
 * попавшееся значение нельзя, иначе отчёт покажет выдумку.
 */
data class QualityLink(
    val url: String,
    /** Название озвучки; пусто — сайт дорожки не различает. */
    val dubbing: String = "",
    /** Метка качества вида 720p; пусто — метки нет. */
    val quality: String = "",
    /** hls, map, label, mp4 — откуда взялась ссылка. */
    val kind: String = "",
    /** Качество единственное: размножать его на список нельзя. */
    val single: Boolean = false,
)

/** Нормализация метки качества и порядок вариантов. */
object Quality {

    /** Что пишем в интерфейсе и отчёте, когда метки нет. */
    const val UNDEFINED = "не определена"

    /** Порядок по умолчанию: от лучшего к худшему. */
    val PREFERRED = listOf("1080p", "720p", "480p", "360p")

    private val DIGITS = Regex("""(\d{3,4})""")

    /**
     * Высоты кадра, которые действительно бывают у качества.
     *
      * Год выпуска и номер серии в этот набор не попадают,
     * поэтому «2024» не превращается в 2024p.
     */
    private val HEIGHTS = setOf(144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160)

    /**
     * Приводит подпись к одному словарю: «HD» → 720p, «720» → 720p.
     *
      * Мусорная подпись («видео», «плеер») даёт пустую
      * строку: это честнее,
     * чем приписать ссылке качество, которого сайт не называл.
     */
    fun normalize(label: String?): String {
        val clean = (label ?: "").trim().trim('[', ']', '(', ')', '"', '\'')
        if (clean.isEmpty()) return ""
        val digits = DIGITS.find(clean)?.groupValues?.get(1)?.toIntOrNull()
        if (digits != null && digits in HEIGHTS) return digits.toString() + "p"
        return when (clean.uppercase()) {
            "HD" -> "720p"
            "FHD", "FULLHD" -> "1080p"
            "SD" -> "480p"
            "LQ" -> "360p"
            "UHD", "4K" -> "2160p"
            else -> ""
        }
    }

    /** Число из метки: по нему сортируем и выбираем максимум. */
    fun rank(quality: String): Int =
        DIGITS.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * Убирает повторы по паре (озвучка, качество), оставляя
     * первую ссылку. */
    fun dedupe(links: List<QualityLink>): List<QualityLink> {
        val seen = HashSet<String>()
        return links.filter { seen.add(it.dubbing + "|" + it.quality) }
    }

    /**
     * Дедуп и сортировка: сначала порядок из спеки, затем по числу.
     *
      * Если качество одно, оно помечается единственным —
      * список из одной
     * строки не должен выглядеть как выбор.
     */
    fun arrange(links: List<QualityLink>, preferred: List<String> = PREFERRED): List<QualityLink> {
        val unique = dedupe(links)
        val order = preferred.ifEmpty { PREFERRED }
        val single = unique.map { it.quality }.filter { it.isNotBlank() }.distinct().size == 1
        return unique
            .sortedWith(
                compareBy<QualityLink> { it.dubbing }
                    .thenBy { order.indexOf(it.quality).let { i -> if (i < 0) order.size else i } }
                    .thenByDescending { rank(it.quality) },
            )
            .map { if (single) it.copy(single = true) else it }
    }

    /**
     * Текст для интерфейса: либо метка, либо честное «не
     * определена». */
    fun text(quality: String): String = quality.ifBlank { UNDEFINED }
}
