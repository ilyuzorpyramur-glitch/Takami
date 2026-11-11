package eu.kanade.tachiyomi.takami.novel

/**
 * Постоянный номер источника по домену.
 *
 * К номеру привязаны закладки, история и скачанные
 * главы, поэтому он
 * считается хешем, а не порядковым счётчиком.
 */
object NovelSourceId {

    /**
    * Диапазон ранобэ-генератора: не пересекается с
    * аниме и мангой.
     */
    private const val BASE = 7_200_000_000_000L

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
