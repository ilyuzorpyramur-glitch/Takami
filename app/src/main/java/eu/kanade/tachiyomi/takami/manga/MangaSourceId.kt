package eu.kanade.tachiyomi.takami.manga

/**
 * Постоянный номер источника по домену.
 *
 * Номер не должен меняться между запусками: к нему привязаны
 * закладки, история и скачанные главы. Поэтому берём устойчивый
 * хеш строки, а не порядковый счётчик.
 */
object MangaSourceId {

    /** Диапазон, отведённый манга-генератору: не пересекается с аниме. */
    private const val BASE = 7_100_000_000_000L

    fun of(host: String): Long {
        val normalized = host.lowercase().removePrefix("www.")
        var hash = 0x811c9dc5L
        for (char in normalized) {
            hash = hash xor char.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return BASE + (hash % 1_000_000L)
    }
}
