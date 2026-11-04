package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.HosterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import org.jsoup.Jsoup

/**
 * Плеер, описанный в спеке.
 *
 * Знание «это playerjs, карту надо спросить отдельно» живёт в полях спеки,
 * а не в ветках кода: здесь только сопоставление адреса с описанием.
 */
object Hoster {

    private const val ID = "{id}"

    /** Какой из описанных в спеке плееров это; null — не описан. */
    fun match(spec: SiteSpec, url: String): HosterSpec? {
        if (url.isBlank()) return null
        return spec.video.hosters.firstOrNull { hoster -> matches(hoster.urlPattern, url) }
    }

    /** Адрес запроса карты: подстановка {id} в apiTemplate; null — не спрашиваем. */
    fun apiUrl(hoster: HosterSpec, pageUrl: String): String? {
        val template = hoster.apiTemplate?.trim().orEmpty()
        if (template.isEmpty()) return null
        val id = if (ID in template) idOf(hoster, pageUrl) else ""
        if (ID in template && id.isBlank()) return null
        val url = MediaUrl.normalize(template.replace(ID, id), pageUrl)
        return url.takeIf { it.isNotEmpty() }
    }

    /** Идентификатор плеера из адреса; пусто — не нашли. */
    fun idOf(hoster: HosterSpec, url: String): String {
        val pattern = hoster.idRegex?.takeIf { it.isNotBlank() } ?: hoster.urlPattern
        val match = find(pattern, url) ?: return ""
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun matches(pattern: String, url: String): Boolean =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url) }
            .getOrDefault(false)

    private fun find(pattern: String, url: String): MatchResult? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE).find(url) }.getOrNull()
}

/** Сколько запросов осталось на одну серию. */
class Budget(rest: Int) {

    private var left = rest.coerceAtLeast(0)

    /** Берём один запрос из потолка; false — потолок исчерпан. */
    fun take(): Boolean {
        if (left <= 0) return false
        left--
        return true
    }
}

/** Итог обхода: ссылки и признак «плеер не раскрылся». */
data class ChainResult(val links: List<QualityLink> = emptyList(), val blocked: Boolean = false)

/**
 * Обход цепочки «страница серии → плееры → страница плеера → карта».
 *
 * Идём в ширину и не останавливаемся на первом успехе: рабочим может
 * оказаться второй плеер, а первый — отдать пустую страницу. Потолок
 * запросов общий на серию, поэтому обход не может уйти в бесконечность.
 */
object Chain {

    private const val HLS = "hls"

    suspend fun collect(
        spec: SiteSpec,
        html: String,
        pageUrl: String,
        dubbing: String,
        budget: Budget = Budget(spec.video.maxRequests),
        fetch: suspend (String) -> String?,
    ): ChainResult {
        if (html.isBlank()) return ChainResult(blocked = true)
        val links = mutableListOf<QualityLink>()
        links += Drafts.links(spec, html, pageUrl, dubbing)
        var blocked = false

        val queue = ArrayDeque<Pair<String, Int>>()
        Drafts.candidates(spec, html, pageUrl).forEach { queue.addLast(it to 0) }
        while (queue.isNotEmpty()) {
            val (url, depth) = queue.removeFirst()
            if (!budget.take()) break
            val hoster = Hoster.match(spec, url)
            if (hoster?.kind == HLS) {
                // Плеер сам отдаёт файл: страницу открывать не нужно.
                links += QualityLink(url, dubbing, "", HLS)
                continue
            }
            val page = fetch(url)
            if (page.isNullOrBlank()) {
                blocked = true
                continue
            }
            val found = (mapFrom(spec, hoster, url, dubbing, budget, fetch) +
                Drafts.links(spec, page, url, dubbing)).distinctBy { it.url }
            if (found.isNotEmpty()) {
                links += found
                continue
            }
            val redirect = Drafts.redirect(page, url)
            if (redirect != null && budget.take()) {
                val next = fetch(redirect)
                if (!next.isNullOrBlank()) {
                    links += Drafts.links(spec, next, redirect, dubbing)
                }
                continue
            }
            if (depth >= spec.video.iframeDepth) continue
            Drafts.candidates(spec, page, url).forEach { queue.addLast(it to depth + 1) }
        }
        return ChainResult(Quality.byUrl(links).distinctBy { it.url }, blocked)
    }

    /** Карта от плеера по шаблону из спеки: отдельный запрос, один раз. */
    private suspend fun mapFrom(
        spec: SiteSpec,
        hoster: HosterSpec?,
        pageUrl: String,
        dubbing: String,
        budget: Budget,
        fetch: suspend (String) -> String?,
    ): List<QualityLink> {
        val api = hoster?.let { Hoster.apiUrl(it, pageUrl) } ?: return emptyList()
        if (!budget.take()) return emptyList()
        val text = fetch(api) ?: return emptyList()
        return (Playerjs.parse(text, dubbing) + PlayerMap.parse(text, dubbing)).distinctBy { it.url }
    }
}

/** Черновики обхода: кандидаты со страницы, прямые ссылки и переход плеера. */
internal object Drafts {

    private const val LIMIT = 512

    private val MEDIA = Regex(
        """(?:https?:)?//[^\s"'<>]+?\.(?:m3u8|mp4|webm)(?:\?[^\s"'<>]*)?""",
    )

    private val REDIRECT = Regex("""location\.href\s*=\s*["']([^"']+)["']""")

    /** Адреса встраивания со страницы: кадры, плееры, объекты, ссылки. */
    fun candidates(spec: SiteSpec, html: String, pageUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, pageUrl)
        val urls = mutableListOf<String>()
        for (attr in spec.video.embedAttrs) {
            if (attr == "href") continue
            doc.select("[$attr]").forEach { node ->
                val url = MediaUrl.normalize(node.attr(attr), pageUrl)
                if (url.isNotEmpty()) urls += url
            }
        }
        doc.select("a[href]").forEach { node ->
            val raw = node.attr("href").ifBlank { node.attr("data-href") }
            val url = MediaUrl.normalize(raw, pageUrl)
            // Ссылку текста берём, только если она похожа на описанный плеер.
            if (url.isNotEmpty() && Hoster.match(spec, url) != null) urls += url
        }
        return urls.distinct().take(spec.video.maxPlayers.coerceAtLeast(1))
    }

    /** Прямые ссылки со страницы: подписи, карта плеера, поток в разметке. */
    fun links(spec: SiteSpec, html: String, pageUrl: String, dubbing: String): List<QualityLink> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<QualityLink>()
        val labelled = runCatching { Regex(spec.video.labelRegex) }.getOrNull()
        labelled?.findAll(html)?.forEach { match ->
            val url = MediaUrl.normalize(match.groupValues[2], pageUrl)
            if (url.isNotEmpty()) {
                result += QualityLink(url, dubbing, Quality.normalize(match.groupValues[1]), "label")
            }
        }
        result += Playerjs.parse(html, dubbing)
        MEDIA.findAll(html).forEach { match ->
            val url = MediaUrl.normalize(match.value, pageUrl)
            if (url.isNotEmpty()) result += QualityLink(url, dubbing, "", "url")
        }
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("video[src], source[src], video[data-src], source[data-src]").forEach { node ->
            val raw = node.attr("src").ifBlank { node.attr("data-src") }
            val url = MediaUrl.normalize(raw, pageUrl)
            if (url.isNotEmpty()) {
                val label = Quality.normalize(node.attr("label"))
                result += QualityLink(url, dubbing, label, "url")
            }
        }
        return result.distinctBy { it.url }
    }

    /** Один переход плеера вида `location.href = "..."`, не больше. */
    fun redirect(page: String, pageUrl: String): String? {
        if (page.isBlank() || page.length > LIMIT * 40) return null
        val raw = REDIRECT.find(page)?.groupValues?.get(1) ?: return null
        return MediaUrl.normalize(raw, pageUrl).takeIf { it.isNotEmpty() }
    }
}
