package eu.kanade.tachiyomi.takami.report

import java.security.MessageDigest

/**
 * Очистка всего, что уходит с устройства.
 *
 * Из адреса остаются схема и хост: путь содержит идентификатор тайтла, а
 * query — подписанные адреса плееров. Название тайтла и название серии в
 * отчёт не попадают никогда, их просто некуда положить.
 */
object Sanitizer {

    /** Длина текстовых полей: длиннее в отчёт не нужно. */
    private const val MAX_FIELD = 64

    /** Схема и хост, без пути и без query. */
    fun originOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val text = url.trim()
        val mark = text.indexOf("://")
        if (mark <= 0) return ""
        val scheme = text.substring(0, mark).lowercase()
        val rest = text.substring(mark + 3)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = if (cut < 0) rest else rest.substring(0, cut)
        if (host.isBlank()) return ""
        return scheme + "://" + host.lowercase()
    }

    /** Хост без схемы и без www: по нему различаем сайты. */
    fun hostOf(url: String?): String =
        originOf(url).substringAfter("://").removePrefix("www.")

    /**
     * Обезличенный номер установки.
     *
     * Считается как sha256 от случайного значения: разные установки
     * остаются различимыми, а сам номер обратно не восстанавливается.
     */
    fun installIdHash(raw: String): String {
        if (raw.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Поле отчёта: одна строка, без переводов и лишней длины. */
    fun field(value: String?): String = (value ?: "")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(MAX_FIELD)
}
