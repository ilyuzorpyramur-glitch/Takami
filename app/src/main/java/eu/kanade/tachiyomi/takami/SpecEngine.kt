package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Исполнитель спеки: превращает [SiteSpec] в каталог, карточку, серии и видео.
 *
 * Ничего не знает про конкретные сайты — только про то, как читать селекторы.
 * Это и есть аналог парсеров AniLabX, но без сборки APK: спека лежит в filesDir.
 */
class SpecEngine(
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun catalogue(spec: SiteSpec, page: Int): Pair<List<SAnime>, Boolean> {
        val url = pageUrl(spec.baseUrl, spec.catalog.path, spec.catalog.pagination, page)
        val html = fetch(url)
        return parseCatalogue(spec, html, url)
    }

    suspend fun latest(spec: SiteSpec, page: Int): Pair<List<SAnime>, Boolean> {
        val path = spec.catalog.latestPath ?: spec.catalog.path
        val url = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val html = fetch(url)
        return parseCatalogue(spec, html, url)
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
        val html = fetch(anime.url)
        val doc = Jsoup.parse(html, anime.url)
        val d = spec.details

        return SAnime.create().apply {
            url = anime.url
            title = text(doc, d.title).ifBlank { anime.title }
            thumbnail_url = attr(doc, d.poster).ifBlank { anime.thumbnail_url.orEmpty() }
            description = text(doc, d.description).ifBlank { null }
            genre = text(doc, d.genre).ifBlank { null }
            status = statusOf(text(doc, d.status))
            initialized = true
        }
    }

    suspend fun episodes(spec: SiteSpec, anime: SAnime): List<SEpisode> {
        val html = fetch(anime.url)
        val doc = Jsoup.parse(html, anime.url)
        val e = spec.episodes
        val numberRegex = Regex(e.numberRegex)

        return doc.select(e.itemSelector)
            .mapNotNull { item ->
                val link = e.linkSelector
                    ?.let { sel -> item.selectFirst(sel) }
                    ?: item.takeIf { it.tagName() == "a" }
                    ?: item.selectFirst("a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val label = item.text().trim().ifBlank { link.text().trim() }
                val number = numberRegex.find(label)?.groupValues?.last()?.toFloatOrNull()
                    ?: return@mapNotNull null

                if (label.contains("фильм", true)) return@mapNotNull null

                SEpisode.create().apply {
                    url = href
                    name = if (e.nameFromText && label.isNotBlank()) label else "Серия " + number.toInt()
                    episode_number = number
                }
            }
            .distinctBy { it.episode_number }
            .sortedByDescending { it.episode_number }
    }

    suspend fun videos(spec: SiteSpec, episode: SEpisode): List<Video> {
        val first = fetch(episode.url)
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
            // Часть плееров кладёт адрес в атрибут, а не в текст скрипта.
            val doc = Jsoup.parse(html)
            doc.select("video[src], source[src], video[data-src], source[data-src]").forEach { node ->
                val raw = node.attr("src").ifBlank { node.attr("data-src") }
                val url = normalizeMedia(raw)
                if (url.isNotBlank()) links.putIfAbsent(url, qualityOf(url))
            }
        }

        collect(first)

        // Плеер-обёртка: видео лежит на следующем уровне iframe.
        var html = first
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

        return links.map { (url, label) ->
            Video(url, label.ifBlank { qualityOf(url) }, url)
        }
    }

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

    suspend fun fetch(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru,en;q=0.9")
        if (referer != null) builder.header("Referer", referer)
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code !in 300..399) {
                error("HTTP " + response.code + " " + url)
            }
            response.body.string()
        }
    }

    /**
     * Первые байты медиа: проверяем, что ссылка отдаёт поток, а не страницу.
     * Трафик минимальный — Range 0..256.
     */
    suspend fun probe(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-256")
        if (referer != null) builder.header("Referer", referer)
        runCatching {
            client.newCall(builder.build()).execute().use { it.body.string() }
        }.getOrDefault("")
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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private val MEDIA_URL = Regex(
            """(?:https?:)?//[^\s"'<>]+?\.(?:m3u8|mp4|webm)(?:\?[^\s"'<>]*)?""",
        )
        private val QUALITY = Regex("""[_/](\d{3,4})[_.]""")
        private val PAGE_NUMBER = Regex("""(?:page[/=])(\d+)""")
    }
}
