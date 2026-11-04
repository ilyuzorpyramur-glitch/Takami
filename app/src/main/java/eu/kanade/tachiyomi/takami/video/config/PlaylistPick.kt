package eu.kanade.tachiyomi.takami.video.config

/**
 * Отбор из длинного плейлиста по номеру серии.
 *
 * Плееры отдают плейлист на весь сезон: сотни адресов подряд. Брать первый
 * нельзя — это чужая серия. Если номер не нашёлся ни в странице, ни в
 * адресах, честнее вернуть пусто.
 */
object PlaylistPick {

    /** Словесные метки номера серии в адресах. */
    private val MARKED = Regex(
        """(?:episode|epizod|seriya|seria|serie|series|ep|e)[-_/]?(\d{1,4})""",
        RegexOption.IGNORE_CASE,
    )

    private val ANY = Regex("""(\d{1,4})""")

    /** Номер серии из адреса страницы; 0 — номер не определяется. */
    fun pageNumber(pageUrl: String): Int {
        if (pageUrl.isBlank()) return 0
        MARKED.find(pageUrl)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return ANY.findAll(pageUrl).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Адреса нужной серии.
     *
     * Короткий список отдаём целиком: там перечислены качества одной серии.
     * Длинный — только совпавшие по номеру.
     */
    fun pick(urls: List<String>, pageUrl: String, small: Int = 6): List<String> {
        val unique = urls.filter { it.isNotBlank() }.distinct()
        if (unique.size <= small) return unique
        val number = pageNumber(pageUrl)
        if (number <= 0) return emptyList()
        return unique.filter { numberIn(it) == number }
    }

    /** Первое число адреса: номер серии в пути. */
    fun numberIn(url: String): Int {
        val path = url.substringBefore('?')
        MARKED.find(path)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return ANY.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
