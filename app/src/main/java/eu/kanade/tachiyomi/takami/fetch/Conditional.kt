package eu.kanade.tachiyomi.takami.fetch

import java.security.MessageDigest

/**
 * Условные запросы: «я это уже видел, скажи только, изменилось ли».
 *
  * Сайт отвечает 304 и не отдаёт тело — это и экономит трафик, и
  * снимает с него
  * работу по сборке страницы. Для сайтов без ETag остаётся сравнение
  * отпечатка
 * начала страницы и её полного размера.
 */
object Conditional {
    const val IF_NONE_MATCH = "If-None-Match"
    const val IF_MODIFIED_SINCE = "If-Modified-Since"

    /** Ответ «не изменилось»: тела нет, данные берём с диска. */
    const val NOT_MODIFIED = 304

    /**
     * Сколько байт участвует в отпечатке: хвост ловится отдельно по
     * размеру.
     */
    private const val HASH_BYTES = 4_096

    /**
     * Заголовки для повторного запроса. Пустая карта — обычный
     * запрос.
     */
    fun headers(entry: CacheEntry?): Map<String, String> {
        if (entry == null) return emptyMap()
        val result = LinkedHashMap<String, String>(2)
        entry.etag?.takeIf { it.isNotBlank() }?.let { result[IF_NONE_MATCH] = it }
        entry.lastModified?.takeIf { it.isNotBlank() }?.let { result[IF_MODIFIED_SINCE] = it }
        return result
    }

    fun isNotModified(code: Int): Boolean = code == NOT_MODIFIED

    /**
     * Отпечаток начала страницы: дешёвый признак правки для сайтов
     * без ETag.
     */
    fun contentHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, minOf(bytes.size, HASH_BYTES))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Страница не изменилась, хотя сайт прислал её целиком.
     *
      * Сверяем и отпечаток начала, и полный размер: правка только в
      * подвале иначе
     * осталась бы незамеченной.
     */
    fun sameContent(entry: CacheEntry, bytes: ByteArray): Boolean {
        if (entry.bodyHash.isBlank()) return false
        if (entry.sizeBytes != bytes.size.toLong()) return false
        return entry.bodyHash == contentHash(bytes)
    }
}
