package eu.kanade.tachiyomi.takami.video

/**
 * Декодер страницы плеера playerjs.
 *
 * Плеер кладёт адреса в поле `file`, и это поле бывает шести видов:
 * плейлист объектами, строковая карта, объектная карта, массив адресов,
 * одиночный адрес и base64 от любого из них. Разбор чистой функцией, без
 * сети: страницу приносит вызывающий.
 */
object Playerjs {

    private const val MIN_BASE64 = 24

    private val KEY = Regex("""(?i)["']?file["']?\s*[:=]\s*""")

    private val PLAYLIST_FIRST = Regex(
        """\{[^{}]*?["']title["']\s*:\s*["']([^"']*)["'][^{}]*?["']file["']\s*:\s*["']([^"']+)["'][^{}]*?\}""",
    )

    private val PLAYLIST_LAST = Regex(
        """\{[^{}]*?["']file["']\s*:\s*["']([^"']+)["'][^{}]*?["']title["']\s*:\s*["']([^"']*)["'][^{}]*?\}""",
    )

    private val QUOTED_URL = Regex("""["']((?:https?:)?//[^"']+)["']""")

    private val LABEL_MAP = Regex("""\[[^\]]{1,16}\]\s*(?:https?:)?//""")

    /** Ссылки из страницы плеера: карта, плейлист, одиночный адрес, base64. */
    fun parse(html: String, dubbing: String = ""): List<QualityLink> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<QualityLink>()
        for (value in values(html)) {
            val text = unquote(value)
            if (text.isEmpty()) continue
            var found: List<QualityLink> = parseValue(text, dubbing)
            if (found.isEmpty()) {
                val decoded = decodeIfBase64(text)
                if (decoded != null) found = parseValue(decoded, dubbing)
            }
            result.addAll(found)
        }
        return result.filter { it.url.isNotBlank() }.distinctBy { it.url }
    }

    /** Значение поля `file` в любом из поддерживаемых видов. */
    private fun parseValue(text: String, dubbing: String): List<QualityLink> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        return when {
            // «[360p]адрес» начинается с той же скобки, что и массив.
            clean.startsWith("[") && LABEL_MAP.containsMatchIn(clean) -> map(clean, dubbing)
            clean.startsWith("[") && clean.contains('{') -> playlist(clean, dubbing)
            clean.startsWith("[") -> arrayOfUrls(clean, dubbing)
            clean.startsWith("{") -> map(clean, dubbing)
            clean.contains('[') -> map(clean, dubbing)
            else -> single(clean, dubbing)
        }
    }

    /** Карта качеств: адреса приводим к абсолютному виду. */
    private fun map(text: String, dubbing: String): List<QualityLink> =
        PlayerMap.parse(text, dubbing).mapNotNull { link ->
            val url = MediaUrl.normalize(link.url)
            if (url.isEmpty()) null else link.copy(url = url)
        }

    /** Плейлист объектами: качество из title, адрес из file. */
    private fun playlist(text: String, dubbing: String): List<QualityLink> {
        val result = mutableListOf<QualityLink>()
        PLAYLIST_FIRST.findAll(text).forEach { match ->
            link(match.groupValues[2], dubbing, match.groupValues[1])?.let { result += it }
        }
        PLAYLIST_LAST.findAll(text).forEach { match ->
            link(match.groupValues[1], dubbing, match.groupValues[2])?.let { result += it }
        }
        return result.distinctBy { it.url }
    }

    /** Массив адресов без метки: качество остаётся неопределённым. */
    private fun arrayOfUrls(text: String, dubbing: String): List<QualityLink> =
        QUOTED_URL.findAll(text).mapNotNull { match ->
            val url = MediaUrl.normalize(match.groupValues[1])
            if (url.isEmpty()) null else QualityLink(url, dubbing, "", "playerjs")
        }.toList()

    /** Один адрес: качество не выдумываем. */
    private fun single(text: String, dubbing: String): List<QualityLink> {
        val url = MediaUrl.normalize(text)
        return if (url.isEmpty()) emptyList() else listOf(QualityLink(url, dubbing, "", "playerjs"))
    }

    private fun link(raw: String, dubbing: String, label: String): QualityLink? {
        val url = MediaUrl.normalize(raw)
        if (url.isEmpty()) return null
        return QualityLink(url, dubbing, Quality.normalize(label), "playerjs")
    }

    /** Снимает кавычки и двойные кавычки внутри значения. */
    private fun unquote(value: String): String {
        var text = value.trim()
        if (text.length >= 2 && text.first() == text.last() && text.first() in "\"'") {
            text = text.substring(1, text.length - 1)
        }
        return text.replace("\"\"", "\"").replace("\\\"", "\"")
    }

    /** Все значения поля file; мусор после ключа просто пропускается. */
    private fun values(html: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < html.length) {
            val match = KEY.find(html, index) ?: break
            val start = match.range.last + 1
            val read = readValue(html, start)
            if (read == null) {
                index = start
                continue
            }
            result += read.first
            index = (start + read.second).coerceAtLeast(start + 1)
        }
        return result
    }

    /** Значение и его длина: кавычки, массив, объект или голый токен. */
    private fun readValue(html: String, start: Int): Pair<String, Int>? {
        var at = start
        while (at < html.length && html[at].isWhitespace()) at++
        if (at >= html.length) return null
        val first = html[at]
        if (first == '"' || first == '\'') return quoted(html, at, first)
        if (first == '[' || first == '{') return balanced(html, at)
        var end = at
        while (end < html.length && html[end] != ',' && html[end] != '\n' && html[end] != ';') end++
        return html.substring(at, end).trim().takeIf { it.isNotEmpty() }?.let { it to (end - at) }
    }

    /** Строка в кавычках: двойная кавычка внутри пишется как `""` или `\"`. */
    private fun quoted(html: String, at: Int, quote: Char): Pair<String, Int> {
        val builder = StringBuilder()
        var index = at + 1
        while (index < html.length) {
            val ch = html[index]
            if (ch == '\\' && index + 1 < html.length) {
                builder.append(ch).append(html[index + 1])
                index += 2
                continue
            }
            if (ch == quote) {
                if (index + 1 < html.length && html[index + 1] == quote && builder.isNotEmpty()) {
                    builder.append(quote)
                    index += 2
                    continue
                }
                return builder.toString() to (index + 1 - at)
            }
            builder.append(ch)
            index++
        }
        return builder.toString() to (html.length - at)
    }

    /** Массив или объект: считаем скобки, кавычки внутри не учитываем. */
    private fun balanced(html: String, at: Int): Pair<String, Int>? {
        var depth = 0
        var index = at
        var quote: Char? = null
        while (index < html.length) {
            val ch = html[index]
            when {
                quote != null && ch == '\\' -> index++
                quote != null && ch == quote -> quote = null
                quote != null -> Unit
                ch == '"' || ch == '\'' -> quote = ch
                ch == '[' || ch == '{' -> depth++
                ch == ']' || ch == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(at, index + 1) to (index + 1 - at)
                }
            }
            index++
        }
        return null
    }

    /** base64 без `/`, `:` и `http`, длиной от 24: раскодируем ровно один раз. */
    private fun decodeIfBase64(text: String): String? {
        val clean = text.trim()
        if (clean.length < MIN_BASE64) return null
        if (clean.contains('/') || clean.contains(':') || clean.contains(' ')) return null
        if (clean.contains("http", ignoreCase = true)) return null
        val decoded = decodeBase64(clean) ?: return null
        if (!decoded.contains("//") && !decoded.contains("http", ignoreCase = true)) return null
        return decoded
    }

    /** Минимальный декодер: битый вход даёт null, исключений нет. */
    private fun decodeBase64(text: String): String? {
        val body = text.trim().trimEnd('=')
        if (body.isEmpty()) return null
        val builder = StringBuilder()
        var buffer = 0
        var bits = 0
        for (ch in body) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                builder.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return builder.toString()
    }
}
