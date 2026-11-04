package eu.kanade.tachiyomi.takami.video

/**
 * Приведение адреса из разметки к абсолютному виду.
 *
 * Плееры прячут адрес по-разному: с экранированием `\/`, с `\u0026`
 * вместо `&`, с HTML-мнемониками, с `%2F` и относительным путём. Вся эта
 * нормализация живёт здесь: второй копии логики быть не должно.
 */
object MediaUrl {

    private const val HTTP = "http"

    private const val ORIGIN_MARK = "://"

    /** Приводит адрес к абсолютному; пусто — адрес непригоден. */
    fun normalize(raw: String, baseUrl: String? = null): String {
        val text = unescape(raw)
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("about:") || lower.startsWith("javascript:") ||
            lower.startsWith("data:") || lower.startsWith("blob:")
        ) {
            return ""
        }
        if (lower.startsWith(HTTP) && (text.contains("%2F", true) || text.contains("%3A", true))) {
            return absolute(text)
        }
        if (text.startsWith("//")) return absolute("https:" + text)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return absolute(text)
        // Относительный путь считаем от адреса страницы: своего домена у него нет.
        val base = baseUrl?.let { originOf(it) }.orEmpty()
        if (base.isEmpty()) return ""
        if (text.startsWith("/")) return absolute(base + text)
        if (text.contains(':') || text.contains(' ')) return ""
        return absolute(base + "/" + text.trimStart('/'))
    }

    /** Снимает экранирование и мнемоники, убирает обрамляющие кавычки. */
    private fun unescape(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&")
        .replace("&#x26;", "&", ignoreCase = true)
        .trim()
        .trim('"', '\'', ',', ';', ' ', '\u00a0')

    /** Абсолютный адрес: один раз раскодируем `%2F`, если это всё ещё http. */
    private fun absolute(text: String): String {
        var url = text.trim()
        if (url.contains("%2F", ignoreCase = true) || url.contains("%3A", ignoreCase = true)) {
            val decoded = url
                .replace("%2F", "/", ignoreCase = true)
                .replace("%3A", ":", ignoreCase = true)
            if (decoded.startsWith(HTTP, ignoreCase = true)) url = decoded
        }
        val host = url.substringAfter(ORIGIN_MARK, "").substringBefore('/')
        return if (host.isBlank() || url.contains(' ')) "" else url
    }

    /** Схема и хост адреса страницы: от него считаются относительные пути. */
    private fun originOf(url: String): String {
        val mark = url.indexOf(ORIGIN_MARK)
        if (mark <= 0) return ""
        val scheme = url.substring(0, mark).lowercase()
        if (scheme != "http" && scheme != "https") return ""
        val rest = url.substring(mark + ORIGIN_MARK.length)
        val host = rest.substringBefore('/').substringBefore('?')
        return if (host.isBlank()) "" else scheme + ORIGIN_MARK + host
    }
}
