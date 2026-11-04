package eu.kanade.tachiyomi.takami

import kotlinx.coroutines.Dispatchers
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import eu.kanade.tachiyomi.takami.video.VideoStream
import org.jsoup.nodes.Element

/**
 * Генератор: смотрит на живую страницу и собирает [SiteSpec].
 *
  * Порядок как у AniLabX-парсеров: сначала определяем движок
  * по признакам в разметке,
  * потом подставляем проверенные для этого движка
  * селекторы, потом — если движок
  * не опознан — перебираем варианты карточек и выбираем
  * тот, что даёт больше всего
 * ссылок с постерами.
 */
class Forge(private val engine: SpecEngine = SpecEngine()) {

    suspend fun build(rawUrl: String): ForgeResult = withContext(Dispatchers.IO) {
        val url = normalize(rawUrl)
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val baseUrl = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/')

        val html = runCatching { engine.fetch(url) }.getOrElse {
            ParseFailures.noteFailure(host, "forge", "empty_body")
            return@withContext ForgeResult.Failure("Страница не загрузилась")
        }
        if (html.isBlank()) {
            ParseFailures.noteFailure(host, "forge", "empty_body")
            return@withContext ForgeResult.Failure("Страница пришла пустой")
        }
        val doc = Jsoup.parse(html, url)
        val detected = detectEngine(html)
        // Ни одного признака движка: селекторы подбираются
        // наугад, и это
        // стоит видеть в калибровке наравне с провалом.
        if (detected == SiteSpec.ENGINE_GENERIC) {
            ParseFailures.noteFailure(host, "forge", "engine_unknown")
        }
        val candidates = reachableCards(doc)
        var card: CardChoice? = null
        var sample: List<eu.kanade.tachiyomi.animesource.model.SAnime> = emptyList()

        for (candidate in candidates) {
            val probe = draftSpec(baseUrl, host, candidate, pathOf(url), detected)
            val parsed = runCatching { engine.parseCatalogue(probe, html, url) }.getOrNull() ?: continue
            val good = parsed.first.count { !it.thumbnail_url.isNullOrBlank() }
            if (parsed.first.size >= MIN_CARDS && good >= MIN_CARDS / 2) {
                card = candidate
                sample = parsed.first
                break
            }
        }

        lastCardSelector = card?.selector ?: "article"
        lastCardChoice = card

        if (card == null) {
            ParseFailures.noteFailure(host, "catalog", "no_cards")
            val reason = "Не нашёл карточки тайтлов на главной:" +
                "сайт отдаёт разметку," +
                "которую я пока не разбираю (проверил " + candidates.size + " вариантов)"
            return@withContext ForgeResult.Failure(reason)
        }
        val chosen: CardChoice = card

        val title = siteName(doc, host)
        val basePath = pathOf(url)
        val pagination = verifyPagination(baseUrl, basePath, paginationFor(detected, doc), sample.firstOrNull()?.url)
        val latestPath = findLatestFeed(baseUrl, basePath, detected, sample.firstOrNull()?.url)
        // Первый тайтл каталога часто оказывается фильмом
        // без серий.
        // Смотрим несколько карточек и берём ту, где серии
        // действительно есть.
        var detail = sample.firstOrNull()?.url ?: pickDetailPage(doc, url, chosen) ?: url
        var detailHtml = if (detail == url) html else runCatching { engine.fetch(detail) }.getOrDefault("")
        var detailDoc = Jsoup.parse(detailHtml, detail)
        var episodes = pickEpisodes(detailDoc)
        var bestCount = countEpisodes(detailDoc, episodes)

        for (candidate in sample.drop(1).take(DETAIL_SAMPLES)) {
            if (bestCount > 0) break
            val page = runCatching { engine.fetch(candidate.url) }.getOrNull() ?: continue
            val parsed = Jsoup.parse(page, candidate.url)
            val choice = pickEpisodes(parsed)
            val count = countEpisodes(parsed, choice)
            if (count > bestCount) {
                detail = candidate.url
                detailHtml = page
                detailDoc = parsed
                episodes = choice
                bestCount = count
            }
        }
        val hasIframe = detailDoc.selectFirst("iframe[src], iframe[data-src]") != null

        val spec = SiteSpec(
            host = host,
            baseUrl = baseUrl,
            name = title,
            engine = detected,
            iconUrl = iconOf(doc, url),
            catalog = CatalogSpec(
                path = basePath,
                latestPath = latestPath,
                itemSelector = chosen.selector,
                linkSelector = chosen.linkSelector,
                titleSelector = chosen.titleSelector,
                posterSelector = chosen.posterSelector,
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
                linkAttr = episodes.linkAttr,
                numberRegex = "(\\d{1,4})",
            ),
            video = VideoSpec(
                kind = if (hasIframe) "auto" else "links",
                iframeSelector = null,
            ),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        logcat(LogPriority.DEBUG) {
            "Takami probe: detail=" + detail + " card=" + chosen.selector +
                " episodes=" + spec.episodes.itemSelector + " attr=" + spec.episodes.linkAttr +
                " found=" + bestCount + " iframe=" + hasIframe
        }

        val report = SelfCheck(engine).report(spec)
        val withDubbings = spec.copy(
            dubbingSpecs = report.dubbings.map { name -> DubbingSpec(name, ".dubbing") },
        )
        ForgeResult.Built(
            spec = withDubbings,
            checks = report.checks,
            sampleDetailUrl = detail,
            dubbings = report.dubbings,
            qualities = report.qualities,
            hoster = report.hoster,
            streams = report.streams,
        )
    }

    /**
     * Признаки движка прямо в разметке — то же, что делает
     * AniLabX при разборе новых сайтов. */
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

    /**
     * Иконка сайта: сначала крупные варианты (apple-touch-icon, og:image),
     * favicon.ico в конце — он мелкий и часто не рисуется.
     */
    private fun iconOf(doc: Document, pageUrl: String): String? {
        doc.selectFirst("link[rel=apple-touch-icon]")
            ?.let { it.absUrl("href").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() && !it.endsWith(".ico") }
            ?.let { return it }

        doc.selectFirst("meta[property=og:image]")
            ?.let { it.absUrl("content").ifBlank { it.attr("content") } }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return doc.selectFirst("link[rel~=icon]")
            ?.let { it.absUrl("href").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Путь каталога: если пользователь дал ссылку на
     * раздел — работаем в нём. */
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
      * Выбор карточки каталога: типовые контейнеры плюс
      * догадка по повторяющимся блокам.
     *
      * Каждый вариант проверяется разбором живой страницы:
      * берём первый, где набирается
      * достаточно тайтлов с заголовком и постером. Пустой
      * результат — честный отказ,
     * а не пустой источник.
     */
    private fun reachableCards(doc: Document): List<CardChoice> {
        val result = mutableListOf<CardChoice>()

        CARD_CANDIDATES.mapTo(result) { CardChoice(it, "a[href]", null, "img") }

        val groups = linkedMapOf<String, MutableList<Element>>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (href.length < 3 || href.startsWith("#")) continue
            val parent = anchor.parent() ?: continue
            for (node in listOfNotNull(parent, parent.parent())) {
                groups.getOrPut(signatureOf(node) ?: continue) { mutableListOf() }.add(anchor)
            }
        }

        groups.entries
            .filter { it.value.size >= MIN_CARDS }
            .sortedByDescending { entry -> entry.value.count { it.selectFirst("img") != null } }
            .take(MAX_GUESSES)
            .mapTo(result) { entry -> CardChoice(entry.key, "a[href]", null, "img") }

        return result.distinctBy { it.selector }
    }

    private fun signatureOf(node: Element): String? {
        val tag = node.tagName()
        if (tag in SKIP_TAGS) return null
        val cls = node.className().trim().split(" ").firstOrNull { it.isNotBlank() }
        return when {
            cls != null -> tag + "." + cls
            node.id().isNotBlank() -> tag + "#" + node.id()
            tag in setOf("ul", "ol", "div", "table", "section") -> tag
            else -> null
        }
    }

    private var lastCardSelector: String = "article"
    private var lastCardChoice: CardChoice? = null

    private fun draftSpec(
        baseUrl: String,
        host: String,
        card: CardChoice,
        path: String,
        engineName: String,
    ): SiteSpec = SiteSpec(
        host = host,
        baseUrl = baseUrl,
        name = host,
        engine = engineName,
        catalog = CatalogSpec(
            path = path,
            itemSelector = card.selector,
            linkSelector = card.linkSelector,
            titleSelector = card.titleSelector,
            posterSelector = card.posterSelector,
        ),
    )

    /**
      * Проверяем пагинацию делом: если вторая страница не
      * отличается,
     * значит сайт её не отдаёт — и обещать «есть ещё» нельзя.
     */
    private suspend fun verifyPagination(
        baseUrl: String,
        path: String,
        pagination: PaginationSpec,
        firstUrl: String?,
    ): PaginationSpec {
        if (pagination.kind == "none") return pagination
        val probe = draftSpec(baseUrl, "probe", CardChoice(".x", "a", null, null), path, SiteSpec.ENGINE_GENERIC)
        val pageUrl = engine.pageUrl(baseUrl, path, pagination, 2)
        val html = runCatching { engine.fetch(pageUrl) }.getOrNull() ?: return PaginationSpec("none", "")
        val items = probe.catalog.copy(itemSelector = lastCardSelector)
        val parsed = runCatching { engine.parseCatalogue(probe.copy(catalog = items), html, pageUrl) }
            .getOrNull() ?: return PaginationSpec("none", "")
        val differs = parsed.first.isNotEmpty() && parsed.first.firstOrNull()?.url != firstUrl
        return if (differs) pagination else PaginationSpec("none", "")
    }

    /**
     * Лента новинок: ищем раздел по годам, который реально
     * отличается от главной. */
    private suspend fun findLatestFeed(
        baseUrl: String,
        path: String,
        engineName: String,
        firstUrl: String?,
    ): String? {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (candidate in listOf("/year/" + year + "/", "/year/" + (year - 1) + "/")) {
            val probe = draftSpec(baseUrl, "probe", lastCardChoice ?: return null, candidate, engineName)
            val pageUrl = baseUrl.trimEnd('/') + candidate
            val html = runCatching { engine.fetch(pageUrl) }.getOrNull() ?: continue
            val parsed = runCatching { engine.parseCatalogue(probe, html, pageUrl) }.getOrNull() ?: continue
            if (parsed.first.size >= MIN_CARDS && parsed.first.firstOrNull()?.url != firstUrl) {
                return candidate
            }
        }
        return null
    }

    private fun countEpisodes(doc: Document, choice: EpisodeChoice): Int {
        return doc.select(choice.selector).count { node ->
            val text = node.text().trim()
            EPISODE_TEXT.containsMatchIn(text) && NUMBER.containsMatchIn(text)
        }
    }

    private fun pickDetailPage(doc: Document, baseUrl: String, card: CardChoice): String? {
        val first = doc.select(card.selector).firstOrNull() ?: return null
        val link = first.selectFirst(card.linkSelector) ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null
        return if (href.startsWith("http")) href else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
    }

    private fun pickEpisodes(doc: Document): EpisodeChoice {
        // Плееры вроде «табов озвучки» держат адрес серии в
        // атрибуте, а не в href.
        for (attr in EPISODE_ATTRS) {
            val nodes = doc.select("[" + attr + "]").filter { node ->
                val text = node.text().trim()
                EPISODE_TEXT.containsMatchIn(text) ||
                    PLAYER_CLASS.containsMatchIn(node.className()) ||
                    PLAYER_CLASS.containsMatchIn(node.parent()?.className().orEmpty())
            }
            if (nodes.isEmpty()) continue
            val node = nodes.first()
            val selector = selectorOf(node) ?: continue
            if (doc.select(selector).size < nodes.size) continue
            return EpisodeChoice(selector, null, attr)
        }

        val numbered = doc.select("a[href]").filter { link ->
            EPISODE_TEXT.containsMatchIn(link.text().trim())
        }
        if (numbered.isNotEmpty()) {
            val parent = numbered.first().parent() ?: return EpisodeChoice("a[href]", null)
            val parentSelector = selectorOf(parent)
            if (parentSelector != null && doc.select(parentSelector).size >= 2) {
                return EpisodeChoice(parentSelector, "a[href]", "href")
            }
            return EpisodeChoice("a[href]", null, "href")
        }
        return EpisodeChoice("a[href]", null, "href")
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

    private data class EpisodeChoice(
        val selector: String,
        val linkSelector: String?,
        val linkAttr: String = "href",
    )

    companion object {
        private const val MIN_CARDS = 6
        private const val DETAIL_SAMPLES = 4
        private const val MAX_GUESSES = 12

        private val SKIP_TAGS = setOf("html", "body", "head", "script", "style", "nav", "footer")

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

        private val EPISODE_ATTRS = listOf("data-iframe", "data-src", "data-player", "data-video")

        private val PLAYER_CLASS = Regex("""(?i)(series|episode|seria|player)""")

        private val NUMBER = Regex("""\d{1,4}""")
        private val PAGE_QUERY = Regex("""[?&]page=\d""")
    }
}

sealed class ForgeResult {
    data class Built(
        val spec: SiteSpec,
        val checks: List<CheckResult>,
        val sampleDetailUrl: String,
        val dubbings: List<String> = emptyList(),
        val qualities: List<String> = emptyList(),
        /** Плеер, отдавший ссылки; пусто — не определён. */
        val hoster: String = "",
        /** Ссылки с двумя осями: качество и озвучка отдельно. */
        val streams: List<VideoStream> = emptyList(),
    ) : ForgeResult()
    data class Failure(val reason: String) : ForgeResult()
}
