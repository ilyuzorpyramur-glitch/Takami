package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.takami.fetch.FetchedPage
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * Исполнитель спеки: превращает [SiteSpec] в каталог, карточку, серии и видео.
 *
 * Ничего не знает про конкретные сайты — только про то, как читать селекторы.
 * Это и есть аналог парсеров AniLabX, но без сборки APK: спека лежит в filesDir.
 */
class SpecEngine(
    private val fetcherOverride: Fetcher? = null,
) {

    private val fetcher: Fetcher
        get() = fetcherOverride ?: Fetcher.get()

    suspend fun catalogue(spec: SiteSpec, page: Int): Pair<List<SAnime>, Boolean> {
        val url = pageUrl(spec.baseUrl, spec.catalog.path, spec.catalog.pagination, page)
        val loaded = fetchPage(url)
        return reportCatalogue(spec, parseCatalogue(spec, loaded.html, loaded.url))
    }

    suspend fun latest(spec: SiteSpec, page: Int): Pair<List<SAnime>, Boolean> {
        val path = spec.catalog.latestPath ?: spec.catalog.path
        val url = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val loaded = fetchPage(url)
        return reportCatalogue(spec, parseCatalogue(spec, loaded.html, loaded.url))
    }

    /** Пустой каталог — это провал разбора, а не пустой источник. */
    private fun reportCatalogue(
        spec: SiteSpec,
        parsed: Pair<List<SAnime>, Boolean>,
    ): Pair<List<SAnime>, Boolean> {
        if (parsed.first.isEmpty()) {
            ParseFailures.noteFailure(spec.host, "catalog", "no_cards", spec.version)
        }
        return parsed
    }

    suspend fun search(spec: SiteSpec, page: Int, query: String): Pair<List<SAnime>, Boolean> {
        val search = spec.catalog.search ?: return emptyList<SAnime>() to false
        val encoded = URLEncoder.encode(query, "UTF-8")
        val base = when (search.kind) {
            "path" -> spec.baseUrl + search.template.replace("{q}", encoded)
            else -> spec.baseUrl + search.template.replace("{q}", encoded)
        }
        val url = if (page <= 1) base else pageUrl(base, "", spec.catalog.pagination, page)
        val html = fetch(url)
        return parseCatalogue(spec, html, url)
    }

    fun parseCatalogue(spec: SiteSpec, html: String, pageUrl: String): Pair<List<SAnime>, Boolean> {
        val doc = Jsoup.parse(html, pageUrl)
        val animes = doc.select(spec.catalog.itemSelector)
            .mapNotNull { item -> toAnime(spec, item, pageUrl) }
            .distinctBy { it.url }
        return animes to hasNextPage(doc, spec, pageUrl)
    }

    private fun toAnime(spec: SiteSpec, item: Element, pageUrl: String): SAnime? {
        val link = spec.catalog.linkSelector
            .takeIf { it.isNotBlank() }
            ?.let { item.selectFirst(it) }
            ?: item.takeIf { it.tagName() == "a" }
            ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank() || href.startsWith("javascript", true)) return null

        val title = spec.catalog.titleSelector
            ?.let { item.selectFirst(it)?.text() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text()
        if (title.isBlank()) return null

        val poster = spec.catalog.posterSelector
            ?.let { sel -> item.selectFirst(sel) ?: link.selectFirst(sel) }
            ?.let { img -> img.absUrl("src").ifBlank { img.absUrl("data-src") } }
            ?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            url = href
            this.title = title.trim()
            thumbnail_url = poster
        }
    }

    suspend fun details(spec: SiteSpec, anime: SAnime): SAnime {
        val animePage = fetchPage(anime.url)
        val doc = Jsoup.parse(animePage.html, animePage.url)
        val d = spec.details

        val result = SAnime.create().apply {
            url = anime.url
            title = text(doc, d.title).ifBlank { anime.title }
            thumbnail_url = attr(doc, d.poster).ifBlank { anime.thumbnail_url.orEmpty() }
            description = text(doc, d.description).ifBlank { null }
            genre = text(doc, d.genre).ifBlank { null }
            status = statusOf(text(doc, d.status))
            initialized = true
        }

        // Ни названия, ни постера: карточка открылась, но разбирать нечего.
        if (result.title.isBlank() && result.thumbnail_url.isNullOrBlank()) {
            ParseFailures.noteFailure(spec.host, "details", "empty_body", spec.version)
        }
        return result
    }

    suspend fun episodes(spec: SiteSpec, anime: SAnime): List<SEpisode> {
        val animePage = fetchPage(anime.url)
        val doc = Jsoup.parse(animePage.html, animePage.url)
        val e = spec.episodes
        val numberRegex = Regex(e.numberRegex)

        val nodes = doc.select(e.itemSelector)
        logcat(LogPriority.DEBUG) {
            "Takami episodes: selector=" + e.itemSelector + " attr=" + e.linkAttr +
                " nodes=" + nodes.size + " url=" + anime.url
        }

        val parsed = nodes
            .mapIndexedNotNull { index, item ->
                val link = e.linkSelector
                    ?.let { sel -> item.selectFirst(sel) }
                    ?: item.takeIf { it.tagName() == "a" }
                    ?: item.selectFirst("a[href]")
                    ?: item.takeIf { it.hasAttr(e.linkAttr) }
                    ?: return@mapIndexedNotNull null

                val href = when {
                    e.linkAttr != "href" -> link.attr(e.linkAttr)
                    else -> link.absUrl("href").ifBlank { link.attr("href") }
                }
                if (href.isBlank()) return@mapIndexedNotNull null

                val label = item.text().trim().ifBlank { link.text().trim() }
                // Без явной привязки к сериям берём только похожие подписи:
                // иначе в список попадают годы, жанры и прочие ссылки с цифрами.
                if (e.linkAttr == "href" && !EPISODE_HINT.containsMatchIn(label)) return@mapIndexedNotNull null

                if (label.contains("фильм", true)) return@mapIndexedNotNull null

                // У блоков плеера подпись часто — название озвучки без номера.
                // Тогда номер берём по порядку, а подпись оставляем человеческой.
                val number = numberRegex.find(label)?.groupValues?.last()?.toFloatOrNull()
                    ?: if (e.linkAttr != "href") (index + 1).toFloat() else return@mapIndexedNotNull null

                val dubbing = dubbingOf(item, doc)
                if (spec.dubbings.isNotEmpty() && dubbing != null && dubbing !in spec.dubbings) {
                    return@mapIndexedNotNull null
                }

                SEpisode.create().apply {
                    url = href
                    name = when {
                        dubbing != null -> dubbing + " (" + number.toInt() + ")"
                        label.isBlank() -> "Серия " + number.toInt()
                        numberRegex.containsMatchIn(label) -> label
                        else -> label + " (" + number.toInt() + ")"
                    }
                    episode_number = number
                }
            }
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }

        if (parsed.isEmpty()) {
            ParseFailures.noteFailure(spec.host, "episodes", "no_episodes", spec.version)
        }
        return parsed
    }

    suspend fun videos(spec: SiteSpec, episode: SEpisode): List<Video> {
        val first = fetchPage(episode.url)
        val links = linkedMapOf<String, String>()
        val labelled = Regex(spec.video.labelRegex)

        fun collect(html: String) {
            labelled.findAll(html).forEach { match ->
                val label = match.groupValues[1].trim()
                val url = normalizeMedia(match.groupValues[2])
                if (url.isNotBlank()) links.putIfAbsent(url, label)
            }
            MEDIA_URL.findAll(html).forEach { match ->
                val url = normalizeMedia(match.value)
                if (url.isNotBlank()) links.putIfAbsent(url, qualityOf(url))
            }
            // playerjs и родственные: адрес, затем метка — «...mp4,[360p]».
            REVERSED.findAll(html).forEach { match ->
                val url = normalizeMedia(match.groupValues[1])
                val label = match.groupValues[2].trim()
                if (url.isNotBlank() && label.isNotBlank()) links.putIfAbsent(url, label)
            }
            // Часть плееров кладёт адрес в атрибут, а не в текст скрипта.
            val doc = Jsoup.parse(html)
            doc.select("video[src], source[src], video[data-src], source[data-src]").forEach { node ->
                val raw = node.attr("src").ifBlank { node.attr("data-src") }
                val url = normalizeMedia(raw)
                if (url.isNotBlank()) links.putIfAbsent(url, qualityOf(url))
            }
        }

        collect(first.html)

        // Плеер-обёртка: видео лежит на следующем уровне iframe.
        var html = first.html
        var depth = 0
        while (links.isEmpty() && depth < spec.video.iframeDepth) {
            val doc = Jsoup.parse(html, episode.url)
            val iframe = spec.video.iframeSelector
                ?.let { doc.selectFirst(it) }
                ?: doc.selectFirst("iframe[src], iframe[data-src]")
                ?: break
            val src = iframe.absUrl("src").ifBlank { iframe.absUrl("data-src") }
            if (src.isBlank()) break
            html = fetch(src, referer = episode.url)
            collect(html)
            depth++
        }

        val videos = links.map { (url, label) ->
            val quality = normalizeQuality(label).ifBlank { qualityOf(url) }
            Video(url, quality, url)
        }

        if (videos.isEmpty()) {
            ParseFailures.noteFailure(spec.host, "video", "no_links", spec.version)
        }

        return when (spec.qualityPreference) {
            "max" -> videos.sortedByDescending { qualityRank(it.quality) }
            else -> videos
        }
    }

    /** Название озвучки: таб плеера, внутри которого лежит серия. */
    private fun dubbingOf(item: Element, doc: Document): String? {
        var node: Element? = item
        while (node != null) {
            val id = node.id()
            if (id.isNotBlank()) {
                val label = doc.selectFirst("a[href=#" + id + "]")?.text()?.trim()
                if (!label.isNullOrBlank()) return label
            }
            node = node.parent()
        }
        return null
    }

    /** «HD» → 720p, «SD» → 480p, «720» → 720p. */
    private fun normalizeQuality(label: String): String {
        val clean = label.trim()
        if (clean.isBlank()) return ""
        val digits = QUALITY.find(clean)?.groupValues?.get(1)?.toIntOrNull()
            ?: DIGITS.find(clean)?.value?.toIntOrNull()
        if (digits != null && digits in 144..2160) return digits.toString() + "p"
        return when (clean.uppercase()) {
            "HD" -> "720p"
            "FHD" -> "1080p"
            "SD" -> "480p"
            "LQ" -> "360p"
            else -> clean
        }
    }

    private fun qualityRank(quality: String): Int =
        DIGITS.find(quality)?.value?.toIntOrNull() ?: 0

    /** Протокол-относительные адреса плееров и разметки приводим к https. */
    private fun normalizeMedia(raw: String): String {
        val url = raw.replace("&amp;", "&").trim().trimEnd(',', '"', '\'')
        return when {
            url.startsWith("//") -> "https:" + url
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> ""
        }
    }

    /** Ссылка страницы каталога для номера страницы. */
    fun pageUrl(base: String, path: String, pagination: PaginationSpec, page: Int): String {
        val root = base.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return root.trimEnd('/') + "/"
        return when (pagination.kind) {
            "query" -> root + (if ('?' in root) "&" else "?") + pagination.template.replace("{n}", page.toString())
            "none" -> root.trimEnd('/') + "/"
            else -> root.trimEnd('/') + "/" + pagination.template.replace("{n}", page.toString()).trimStart('/')
        }
    }

    private fun hasNextPage(doc: Document, spec: SiteSpec, pageUrl: String): Boolean {
        if (spec.catalog.pagination.kind == "none") return false
        val current = PAGE_NUMBER.find(pageUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return doc.select("a[href]").any { a ->
            val n = PAGE_NUMBER.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            n != null && n > current && n <= current + 1
        }
    }

    /** Все запросы идут через [Fetcher]: лимиты, кэш и повторы живут там. */
    suspend fun fetch(url: String, referer: String? = null): String = fetcher.get(url, referer)

    /**
     * Страница вместе с конечным адресом после редиректов.
     *
     * Относительные ссылки нужно считать от него: иначе карточки каталога ведут
     * в несуществующие пути, а вёрстка выглядит «сломанной».
     */
    internal suspend fun fetchPage(url: String, referer: String? = null): FetchedPage =
        fetcher.page(url, referer)

    /**
     * Первые байты медиа: проверяем, что ссылка отдаёт поток, а не страницу.
     * Трафик минимальный — Range 0..256.
     */
    suspend fun probe(url: String, referer: String? = null): String = try {
        fetcher.probe(url, referer)
    } catch (e: IOException) {
        ""
    }

    private fun text(doc: Document, selector: String?): String {
        if (selector.isNullOrBlank()) return ""
        val element = doc.selectFirst(selector) ?: return ""
        val content = element.attr("content")
        return (content.ifBlank { element.text() }).trim()
    }

    private fun attr(doc: Document, selector: String?): String {
        if (selector.isNullOrBlank()) return ""
        val element = doc.selectFirst(selector) ?: return ""
        return element.absUrl("content").ifBlank { element.attr("content") }.ifBlank {
            element.absUrl("src").ifBlank { element.attr("src") }
        }.trim()
    }

    private fun statusOf(text: String): Int = when {
        text.isBlank() -> SAnime.UNKNOWN
        text.contains("онгоинг", true) || text.contains("выходит", true) ||
            text.contains("ongoing", true) -> SAnime.ONGOING
        text.contains("заверш", true) || text.contains("completed", true) ||
            text.contains("full", true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun qualityOf(url: String): String {
        QUALITY.find(url)?.let { return it.groupValues[1] + "p" }
        return "Видео"
    }

    companion object {
        private val MEDIA_URL = Regex(
            """(?:https?:)?//[^\s"'<>]+?\.(?:m3u8|mp4|webm)(?:\?[^\s"'<>]*)?""",
        )
        private val QUALITY = Regex("""[_/](\d{3,4})[_.]""")

        private val DIGITS = Regex("""\d{3,4}""")

        private val EPISODE_HINT = Regex("""(?i)(серия|эпизод|episode|series\s*\d)""")

        private val REVERSED = Regex(
            """((?:https?:)?//[^\s"'<>,\[\]]+?\.(?:m3u8|mp4|webm)(?:\?[^\s"'<>,\[\]]*)?)\s*,\s*\[([^\]]{1,12})]""",
        )
        private val PAGE_NUMBER = Regex("""(?:page[/=])(\d+)""")
    }
}
