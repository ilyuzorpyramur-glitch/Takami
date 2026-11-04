package eu.kanade.tachiyomi.takami.video.kodik

import eu.kanade.tachiyomi.takami.video.Quality
import eu.kanade.tachiyomi.takami.video.QualityLink

/**
 * Запрос ссылок у плеера Kodik и разбор ответа.
 *
 * Страница плеера ссылок не содержит: она отдаёт их по POST на /ftor, где
 * подпись страницы и данные серии лежат в теле. Ответ — качества, у
 * каждого свой адрес в закодированном виде. Ничего не выдумываем: нет
 * поля — нет значения.
 */
object KodikLinks {

    /** Куда плеер отправляет запрос за ссылками. */
    const val FTOR = "https://kodikplayer.com/ftor"

    /** Порядок подписи: сервер сверяет её целиком. */
    private val SIGNED = listOf("d", "d_sign", "pd", "pd_sign", "ref", "ref_sign")

    private val QUALITY = Regex(""""(\d{3,4})"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)

    private val SOURCE = Regex(""""src"\s*:\s*"([^"]+)"""")

    /**
     * Тело запроса: подпись страницы и данные серии.
     *
     * Серия важна: без её id и hash плеер отдаст ссылку первой серии, а не
     * выбранной. Перевод задаётся страницей, с которой взят [KodikPage].
     */
    fun body(page: KodikPage, series: KodikSeries? = null): String {
        val parts = mutableListOf<String>()
        for (key in SIGNED) {
            val value = page.params[key] ?: continue
            if (value.isNotBlank()) parts += key + "=" + value
        }
        parts += "advert_debug=false"
        parts += "min_age=" + (page.params["min_age"] ?: "18")
        parts += "first_url=false"
        parts += "hash=" + (series?.hash ?: page.video.hash)
        parts += "id=" + (series?.id ?: page.video.id)
        parts += "type=" + page.video.type.ifBlank { "seria" }
        parts += "bad_user=false"
        parts += "cdn_is_working=true"
        return parts.joinToString("&")
    }

    /**
     * Снимает кодировку адреса: сдвиг буквы на 18 и base64.
     *
     * Плеер отдаёт адрес не целиком: буквы сдвинуты внутри своего
     * регистра, а строка завёрнута в base64. Открытый адрес приходит как
     * есть и остаётся собой.
     */
    fun decode(src: String): String {
        val text = src.trim()
        if (text.isEmpty()) return ""
        if (text.contains("//")) return text
        val shifted = buildString(text.length) {
            for (ch in text) {
                if (!ch.isLetter()) {
                    append(ch)
                    continue
                }
                val upper = ch in 'A'..'Z'
                val edge = if (upper) 'Z' else 'z'
                var moved = ch + SHIFT
                if (moved > edge) moved -= ALPHABET
                append(moved)
            }
        }
        val padded = shifted + "=".repeat((4 - shifted.length % 4) % 4)
        val bytes = Base64.decode(padded) ?: return ""
        val url = bytes.toString(Charsets.UTF_8).trim()
        return if (url.startsWith("http")) url else ""
    }

    /**
     * Разбор ответа: качество, адрес и тип.
     *
     * Каждому качеству соответствует свой список адресов; порядок
     * сохраняем, повторяющиеся адреса убираем.
     */
    fun parse(response: String, dubbing: String, referer: String): List<QualityLink> {
        if (response.isBlank() || !response.contains("links")) return emptyList()
        val headers = if (referer.isBlank()) emptyMap() else mapOf("Referer" to referer)
        val result = mutableListOf<QualityLink>()
        for (block in QUALITY.findAll(response)) {
            val quality = Quality.normalize(block.groupValues[1])
            if (quality.isEmpty()) continue
            for (source in SOURCE.findAll(block.groupValues[2])) {
                val url = decode(source.groupValues[1])
                if (url.isEmpty()) continue
                result += QualityLink(
                    url = url,
                    dubbing = dubbing,
                    quality = quality,
                    kind = "kodik",
                    headers = headers,
                )
            }
        }
        return result.distinctBy { it.url + "|" + it.quality }
    }

    /** Шаг сдвига букв в адресе. */
    private const val SHIFT = 18

    /** Длина латинского алфавита. */
    private const val ALPHABET = 26

    /**
     * Свой base64: разборщик из платформы в проверках без устройства
     * недоступен, а поведение должно быть одинаковым.
     */
    private object Base64 {

        private const val PAD = '='

        private val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        fun decode(text: String): ByteArray? {
            if (text.isEmpty()) return null
            val clean = text.filter { it != '\n' && it != '\r' && it != ' ' }
            val body = clean.trimEnd(PAD)
            if (body.length % 4 == 1) return null
            val out = ArrayList<Byte>(body.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (ch in body) {
                val index = ALPHABET.indexOf(ch)
                if (index < 0) {
                    if (ch == '-' || ch == '_') return null
                    continue
                }
                buffer = (buffer shl 6) or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out += ((buffer shr bits) and 0xFF).toByte()
                }
            }
            return out.toByteArray()
        }
    }
}
