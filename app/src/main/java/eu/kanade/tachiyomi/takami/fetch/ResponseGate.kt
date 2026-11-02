package eu.kanade.tachiyomi.takami.fetch

/**
 * Страница после ворот: расшифрованный текст и кодировка, в
 * которой он пришёл.
 */
data class GatedPage(
    val html: String,
    val charset: String,
)

/**
 * Ворота перед разбором.
 *
  * Ответ принимается, только если это действительно HTML: иначе Jsoup
  * получит
  * картинку или JSON и выдаст «ноль тайтлов», что неотличимо от
  * поломки парсера.
  * Заглушки защиты распознаются отдельно: их бессмысленно
  * разбирать и вредно
 * повторять запросом — именно повтор и приводит к блокировке.
 */
object ResponseGate {
    /** Сколько байт просматриваем в поисках признаков заглушки. */
    private const val SCAN_BYTES = 16_384

    private val MARKERS = listOf(
        "just a moment",
        "checking your browser",
        "verify you are human",
        "attention required",
        "cf-chl",
        "cf_chl_opt",
        "ddos-guard",
        "ddos guard",
        "enable javascript and cookies to continue",
        "проверка браузера",
        "подтвердите, что вы не робот",
        "сайт на техническом обслуживании",
    )

    fun check(contentType: String?, bytes: ByteArray, url: String): GatedPage {
        if (bytes.isEmpty()) throw NotHtmlError(url, contentType.orEmpty())
        if (bytes.size > FetchLimits.MAX_HTML_BYTES) throw PageTooLargeError(url, bytes.size.toLong())
        if (!claimsHtml(contentType) && !looksLikeHtml(bytes)) {
            throw NotHtmlError(url, contentType.orEmpty())
        }

        val charset = CharsetSniffer.detect(contentType, bytes)
        val html = String(bytes, charset).removePrefix("\uFEFF")
        if (isChallenge(html)) throw ChallengePageError(url)
        if (!looksLikeHtml(html)) throw NotHtmlError(url, contentType.orEmpty())
        return GatedPage(html, charset.name())
    }

    /** Заглушка антибота или страница обслуживания. */
    fun isChallenge(html: String): Boolean {
        val head = html.take(SCAN_BYTES).lowercase()
        return MARKERS.any { head.contains(it) }
    }

    private fun claimsHtml(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "text/html" || type == "application/xhtml+xml"
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1_024), Charsets.ISO_8859_1).lowercase()
        return head.contains("<html") || head.contains("<!doctype")
    }

    private fun looksLikeHtml(html: String): Boolean {
        val head = html.take(1_024).lowercase()
        return head.contains("<html") || head.contains("<!doctype")
    }
}
