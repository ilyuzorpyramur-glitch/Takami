package eu.kanade.tachiyomi.takami

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Генератор: смотрит на живую страницу и собирает [SiteSpec].
 *
 * Порядок как у AniLabX-парсеров: сначала определяем движок по признакам в разметке,
 * потом подставляем проверенные для этого движка селекторы, потом — если движок
 * не опознан — перебираем варианты карточек и выбираем тот, что даёт больше всего
 * ссылок с постерами.
 */
class Forge(private val engine: SpecEngine = SpecEngine()) {

    suspend fun build(rawUrl: String): ForgeResult = withContext(Dispatchers.IO) {
        val url = normalize(rawUrl)
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val baseUrl = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/')

        val html = engine.fetch(url)
        val doc = Jsoup.parse(html, url)
        val detected = detectEngine(html)
        val card = pickCardSelector(doc) ?: return@withContext ForgeResult.Failure(
            "Не нашёл карточки тайтлов на главной: сайт отдаёт разметку, которую я пока не разбираю",
        )

        val title = siteName(doc, host)
        val pagination = paginationFor(detected, doc)
        val detail = pickDetailPage(doc, url, card) ?: url
        val detailHtml = if (detail == url) html else runCatching { engine.fetch(detail) }.getOrDefault("")
        val detailDoc = Jsoup.parse(detailHtml, detail)

        val episodes = pickEpisodes(detailDoc)
        val hasIframe = detailDoc.selectFirst("iframe[src], iframe[data-src]") != null

        val spec = SiteSpec(
            host = host,
            baseUrl = baseUrl,
            name = title,
            engine = detected,
            iconUrl = iconOf(doc, url),
            catalog = CatalogSpec(
                path = pathOf(url),
                itemSelector = card.selector,
                linkSelector = card.linkSelector,
                titleSelector = card.titleSelector,
                posterSelector = card.posterSelector,
                pagination = pagination,
                search = searchFor(detected),
            ),
            details = DetailsSpec(
                title = if (detailDoc.selectFirst("h1") != null) "h1" else "meta[property=og:title]",
                poster = "meta[property=og:image]",
                description = "meta[property=og:description]",
                status = null,
            ),
            episodes = EpisodesSpec(
                itemSelector = episodes.selector,
                linkSelector = episodes.linkSelector,
                numberRegex = "(\\d{1,4})",
            ),
            video = VideoSpec(
                kind = if (hasIframe) "auto" else "links",
                iframeSelector = null,
            ),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        val checks = SelfCheck(engine).run(spec)
        ForgeResult.Built(spec, checks, detail)
    }

    /** Признаки движка прямо в разметке — то же, что делает AniLabX при разборе новых сайтов. */
    fun detectEngine(html: String): String = when {
        html.contains("/engine/classes/") || html.contains("engine/ajax") ||
            html.contains("dle_") || html.contains("DataLife Engine") -> SiteSpec.ENGINE_DLE

        html.contains("wp-content") || html.contains("wp-json") -> SiteSpec.ENGINE_WORDPRESS

        else -> SiteSpec.ENGINE_GENERIC
    }

    private fun siteName(doc: Document, host: String): String {
        val og = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
        if (!og.isNullOrBlank()) return og

        val title = doc.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isNotBlank()) {
            return title.split("—", "|", "·", " - ").first().trim().take(40).ifBlank { host }
        }
        return host
    }

    private fun iconOf(doc: Document, pageUrl: String): String? {
        val link = doc.selectFirst("link[rel=apple-touch-icon], link[rel~=icon]")
            ?.let { it.absUrl("href").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }
        if (link != null) return link

        return doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
    }

    /** Путь каталога: если пользователь дал ссылку на раздел — работаем в нём. */
    private fun pathOf(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "")
        return "/" + path.trim('/') + if (path.isBlank()) "" else "/"
    }

    private fun paginationFor(engineName: String, doc: Document): PaginationSpec {
        val pager = doc.select("a[href]").map { it.attr("href") }
        if (pager.any { PAGE_QUERY.containsMatchIn(it) }) return PaginationSpec("query", "page={n}")

        return when (engineName) {
            SiteSpec.ENGINE_WORDPRESS -> PaginationSpec("path", "page/{n}/")
            SiteSpec.ENGINE_DLE -> PaginationSpec("path", "page/{n}/")
            else -> PaginationSpec("path", "page/{n}/")
        }
    }

    private fun searchFor(engineName: String): SearchSpec = when (engineName) {
        SiteSpec.ENGINE_DLE -> SearchSpec("query", "/?do=search&subaction=search&story={q}")
        SiteSpec.ENGINE_WORDPRESS -> SearchSpec("query", "/?s={q}")
        else -> SearchSpec("query", "/?s={q}")
    }

    /**
     * Выбор карточки каталога: перебираем типовые контейнеры и оставляем тот,
     * где больше всего ссылок с постерами. Пустой результат — честный отказ,
     * а не пустой источник.
     */
    private fun pickCardSelector(doc: Document): CardChoice? {
        for (selector in CARD_CANDIDATES) {
            val items = doc.select(selector)
            if (items.size < MIN_CARDS) continue

            val usable = items.count { item ->
                val link = item.selectFirst("a[href]") ?: return@count false
                val href = link.attr("href")
                href.length > 3 && !href.startsWith("#") && link.text().trim().length > 1
            }
            if (usable < MIN_CARDS) continue

            val withPoster = items.count { it.selectFirst("img") != null }
            if (withPoster < usable / 2) continue

            val titleSelector = if (items.firstOrNull()?.selectFirst("h3 a") != null) "h3" else null
            return CardChoice(selector, "a[href]", titleSelector, "img")
        }
        return null
    }

    private fun pickDetailPage(doc: Document, baseUrl: String, card: CardChoice): String? {
        val first = doc.select(card.selector).firstOrNull() ?: return null
        val link = first.selectFirst(card.linkSelector) ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null
        return if (href.startsWith("http")) href else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
    }

    private fun pickEpisodes(doc: Document): EpisodeChoice {
        val numbered = doc.select("a[href]").filter { link ->
            EPISODE_TEXT.containsMatchIn(link.text().trim())
        }
        if (numbered.isNotEmpty()) {
            val parent = numbered.first().parent() ?: return EpisodeChoice("a[href]", null)
            val parentSelector = selectorOf(parent)
            if (parentSelector != null && doc.select(parentSelector).size >= 2) {
                return EpisodeChoice(parentSelector, "a[href]")
            }
            return EpisodeChoice("a[href]", null)
        }
        return EpisodeChoice("a[href]", null)
    }

    private fun selectorOf(element: Element): String? {
        val cls = element.className().trim().split(" ").firstOrNull { it.isNotBlank() }
        return when {
            cls != null -> "." + cls
            element.id().isNotBlank() -> "#" + element.id()
            element.tagName() in setOf("ul", "ol", "div", "table") -> element.tagName()
            else -> null
        }
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://" + trimmed
        }
    }

    private data class CardChoice(
        val selector: String,
        val linkSelector: String,
        val titleSelector: String?,
        val posterSelector: String?,
    )

    private data class EpisodeChoice(val selector: String, val linkSelector: String?)

    companion object {
        private const val MIN_CARDS = 6

        private val CARD_CANDIDATES = listOf(
            "article",
            ".shortstory",
            "#dle-content > article",
            ".movie-item",
            ".anime-card",
            ".card",
            ".pmovie",
            ".th-item",
            ".item",
            "li.item",
            "article.post",
        )

        private val EPISODE_TEXT = Regex("""(?i)(серия|эпизод|episode\s*\d)""")
        private val PAGE_QUERY = Regex("""[?&]page=\d""")
    }
}

sealed class ForgeResult {
    data class Built(val spec: SiteSpec, val checks: List<CheckResult>, val sampleDetailUrl: String) : ForgeResult()
    data class Failure(val reason: String) : ForgeResult()
}
