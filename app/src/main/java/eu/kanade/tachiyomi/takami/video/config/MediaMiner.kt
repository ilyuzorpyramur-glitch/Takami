package eu.kanade.tachiyomi.takami.video.config

import eu.kanade.tachiyomi.takami.VideoSpec
import eu.kanade.tachiyomi.takami.video.HlsMaster
import eu.kanade.tachiyomi.takami.video.MediaUrl
import eu.kanade.tachiyomi.takami.video.Quality

/** Что нашли в данных страницы. */
data class MinedLink(val url: String, val quality: String = "", val kind: String = "")

/**
 * Шахта конфига: адреса в данных страницы, без сети.
 *
 * Плеер часто собирает адрес скриптом, но сам конфиг лежит в разметке как
 * данные: JSON, атрибут, строка скрипта. Здесь только текст на входе и
 * список адресов на выходе — ни одного запроса.
 */
object MediaMiner {

    /** Имена ключей, у которых может лежать адрес. */
    private val KEYS = setOf(
        "file", "files", "src", "sources", "source", "playlist", "manifest",
        "hls", "m3u8", "mp4", "dash", "mpd", "video", "videos", "contenturl",
    )

    private val KEY = Regex("""["']?([A-Za-z_][A-Za-z0-9_-]*)["']?\s*[:=]\s*""")

    private val URLS = Regex("""(?:https?:)?//[^\s"'<>\\]+""")

    private const val MASTER = "#EXT-X-STREAM-INF"

    private const val SMALL = 6

    private const val MIN_BASE64 = 24

    /** Ищет адреса в данных страницы: JSON, атрибуты, скрипты. */
    fun mine(html: String, pageUrl: String, spec: VideoSpec): List<MinedLink> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<MinedLink>()
        var from = 0
        while (from < html.length) {
            val match = KEY.find(html, from) ?: break
            from = match.range.last + 1
            val name = match.groupValues[1].lowercase()
            if (name !in KEYS && !spec.sniff.mediaPatterns.any { it.contains(name) }) continue
            val value = valueAt(html, from) ?: continue
            from = (from + value.second).coerceAtLeast(from + 1)
            result += fromValue(value.first, pageUrl)
        }
        return result.filter { it.url.isNotBlank() }.distinctBy { it.url }
    }

    /** Разбор одного значения: мастер, список адресов или одиночный адрес. */
    private fun fromValue(raw: String, pageUrl: String): List<MinedLink> {
        val text = decoded(raw)
        if (text.isBlank()) return emptyList()
        if (text.contains(MASTER)) {
            return HlsMaster.parse(text).mapNotNull { link ->
                val url = MediaUrl.normalize(link.url, pageUrl)
                if (url.isEmpty()) null else MinedLink(url, link.quality, "hls")
            }
        }
        val urls = findUrls(text, pageUrl)
        if (urls.size > 1) {
            // Длинный плейлист — только своя серия, иначе ничего.
            return PlaylistPick.pick(urls, pageUrl, SMALL).map { MinedLink(it, "", "config") }
        }
        val single = urls.firstOrNull() ?: MediaUrl.normalize(text, pageUrl)
        if (single.isEmpty()) return emptyList()
        val quality = Quality.normalize(text.substringBefore(single).takeLast(16))
        return listOf(MinedLink(single, quality, "config"))
    }

    /** Все http(s)-адреса значения, уже приведённые к абсолютному виду. */
    private fun findUrls(text: String, pageUrl: String): List<String> =
        URLS.findAll(text)
            .map { MediaUrl.normalize(it.value, pageUrl) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /**
     * Однократное снятие кодировок.
     *
     * Проход один, поэтому `&amp;amp;` не превращается в `&` дважды: иначе
     * можно разъесть настоящий адрес.
     */
    private fun decoded(raw: String): String {
        val text = raw.trim().trim('"', '\'').trim()
        if (text.isEmpty()) return ""
        val decoded = ENTITY.replace(text) { match -> code(match.value) }
        val base = decodeIfBase64(decoded)
        return base ?: decoded
    }

    private fun code(value: String): String = when (value.lowercase()) {
        "\\u002f" -> "/"
        "\\/" -> "/"
        "&amp;", "&#38;", "&#x26;" -> "&"
        "%2f" -> "/"
        "%3a" -> ":"
        "%3d" -> "="
        else -> value
    }

    /** base64 пробуем только там, где после раскодирования видно http. */
    private fun decodeIfBase64(text: String): String? {
        val clean = text.trim()
        if (clean.length < MIN_BASE64 || clean.contains('"')) return null
        if (clean.contains('/') || clean.contains(':') || clean.contains(' ')) return null
        val decoded = Base64Text.decode(clean) ?: return null
        return decoded.takeIf { it.contains("http", ignoreCase = true) }
    }

    /** Значение после ключа и его длина. */
    private fun valueAt(html: String, start: Int): Pair<String, Int>? {
        var at = start
        while (at < html.length && html[at].isWhitespace()) at++
        if (at >= html.length) return null
        return when (html[at]) {
            '"', '\'' -> quoted(html, at)
            '[', '{' -> balanced(html, at)
            else -> {
                var end = at
                while (end < html.length && html[end] !in ",;\n") end++
                html.substring(at, end).trim().takeIf { it.isNotEmpty() }?.let { it to (end - at) }
            }
        }
    }

    private fun quoted(html: String, at: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var index = at + 1
        while (index < html.length) {
            val ch = html[index]
            if (ch == '\\' && index + 1 < html.length) {
                builder.append(ch).append(html[index + 1])
                index += 2
                continue
            }
            if (ch == html[at]) return builder.toString() to (index + 1 - at)
            builder.append(ch)
            index++
        }
        return builder.toString() to (html.length - at)
    }

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

    private val ENTITY = Regex("""\\u002[fF]|\\/|&amp;|&#38;|&#x26;|%2F|%3A|%3D""", RegexOption.IGNORE_CASE)
}

/** Декодер base64 без внешних зависимостей: битый вход даёт null. */
internal object Base64Text {

    fun decode(text: String): String? {
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
