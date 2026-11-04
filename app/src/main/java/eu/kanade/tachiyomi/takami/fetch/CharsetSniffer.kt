package eu.kanade.tachiyomi.takami.fetch

import java.nio.charset.Charset

/**
 * Определение кодировки страницы.
 *
  * Порядок как у браузера: BOM, затем charset из заголовка, затем meta в
  * первых
  * килобайтах. Половина русских сайтов отдаёт windows-1251 только в meta,
  * и без
   * этого шага весь русский текст превращается в мусор, а
   * селекторы
  * по словам
 * перестают совпадать.
 */
object CharsetSniffer {
    /**
      * Сколько байт просматриваем в поисках meta: у крупных
      * сайтов она
     * в шапке.
     */
    private const val SNIFF_BYTES = 4_096

    private val HEADER = Regex("""charset\s*=\s*["']?([a-zA-Z0-9_\-:.]+)""", RegexOption.IGNORE_CASE)

    private val META = Regex(
        """<meta[^>]+charset\s*=\s*["']?\s*([a-zA-Z0-9_\-:.]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun detect(contentType: String?, bytes: ByteArray): Charset {
        fromBom(bytes)?.let { return it }
        contentType?.let { HEADER.find(it) }?.groupValues?.get(1)?.let { name ->
            charsetOrNull(name)?.let { return it }
        }
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        META.find(head)?.groupValues?.get(1)?.let { name ->
            charsetOrNull(name)?.let { return it }
        }
        return Charsets.UTF_8
    }

    private fun fromBom(bytes: ByteArray): Charset? {
        if (bytes.size < 2) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE
            b0 == 0xFE && b1 == 0xFF -> Charsets.UTF_16BE
            bytes.size >= 3 && b0 == 0xEF && b1 == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF -> Charsets.UTF_8
            else -> null
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name.trim())
    } catch (e: Exception) {
        null
    }
}
