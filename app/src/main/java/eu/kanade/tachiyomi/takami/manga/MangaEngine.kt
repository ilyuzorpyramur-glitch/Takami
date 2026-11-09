package eu.kanade.tachiyomi.takami.manga

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.takami.PaginationSpec
import eu.kanade.tachiyomi.takami.fetch.FetchedPage
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Исполнитель манга-спеки: каталог, карточка, главы, страницы.
 *
 * Разбор отделён от сети: `parse*` — чистые функции над готовой
 * разметкой, их проверяют тесты без сети. Сеть живёт в `suspend`
 * функциях и идёт только через [Fetcher]: лимиты, кэш и повторы
 * там.
 */
class MangaEngine(
    private val fetcherOverride: Fetcher? = null,
) {

    private val fetcher: Fetcher
        get() = fetcherOverride ?: Fetcher.get()

    suspend fun catalogue(spec: MangaSpec, page: Int): Pair<List<SManga>, Boolean> {
        val url = pageUrl(spec.baseUrl, spec.catalog.path, spec.catalog.pagination, page)
        val fetched = fetchPage(url, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    suspend fun latest(spec: MangaSpec, page: Int): Pair<List<SManga>, Boolean> {
        val path = spec.catalog.latestPath ?: spec.catalog.path
        val url = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val fetched = fetchPage(url, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    suspend fun search(spec: MangaSpec, page: Int, query: String): Pair<List<SManga>, Boolean> {
        val search = spec.catalog.search ?: return catalogue(spec, page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val path = when (search.kind) {
            "path" -> search.template.replace("{q}", encoded)
            else -> search.template.replace("{q}", encoded)
        }
        val base = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val fetched = fetchPage(base, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    /** Чистый разбор каталога: ни одного запроса. */
    fun parseCatalog(spec: MangaSpec, html: String, pageUrl: String): Pair<List<SManga>, Boolean> {
        val doc = Jsoup.parse(html, pageUrl)
        val items = doc.select(spec.catalog.itemSelector)
            .mapNotNull { item -> toManga(spec, item) }
            .distinctBy { it.url }
        return items to hasNextPage(doc, spec, pageUrl)
    }

    private fun toManga(spec: MangaSpec, item: Element): SManga? {
        val link = spec.catalog.linkSelector
            .takeIf { it.isNotBlank() }
            ?.let { item.selectFirst(it) }
            ?: item.takeIf { it.tagName() == "a" }
            ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank() || href.startsWith("javascript", ignoreCase = true)) return null

        val title = spec.catalog.titleSelector
            ?.let { item.selectFirst(it)?.text() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text()
        if (title.isBlank()) return null

        val poster = spec.catalog.posterSelector
            ?.let { sel -> item.selectFirst(sel) ?: link.selectFirst(sel) }
            ?.let { img -> img.absUrl("src").ifBlank { img.absUrl("data-src") } }
            ?.takeIf { it.isNotBlank() }

        return SManga.create().apply {
            url = href
            this.title = title.trim()
            thumbnail_url = poster
        }
    }

    suspend fun details(spec: MangaSpec, manga: SManga): SManga {
        val fetched = fetchPage(manga.url, spec.baseUrl)
        return parseDetails(spec, fetched.html, manga)
    }

    /** Чистый разбор карточки: дополняет уже собранный тайтл. */
    fun parseDetails(spec: MangaSpec, html: String, manga: SManga): SManga {
        val doc = Jsoup.parse(html, manga.url)
        val result = manga.copy()
        text(doc, spec.details.title).takeIf { it.isNotBlank() }?.let { result.title = it }
        picture(doc, spec.details.poster).takeIf { it.isNotBlank() }?.let { result.thumbnail_url = it }
        text(doc, spec.details.author).takeIf { it.isNotBlank() }?.let { result.author = it }
        text(doc, spec.details.artist).takeIf { it.isNotBlank() }?.let { result.artist = it }
        text(doc, spec.details.description).takeIf { it.isNotBlank() }?.let { result.description = it }
        genres(doc, spec.details.genre).takeIf { it.isNotEmpty() }
            ?.let { result.genre = it.joinToString(", ") }
        result.status = statusOf(text(doc, spec.details.status))
        return result
    }

    suspend fun chapters(spec: MangaSpec, manga: SManga): List<SChapter> {
        val fetched = fetchPage(manga.url, spec.baseUrl)
        return parseChapters(spec, fetched.html, fetched.url)
    }

    /** Чистый разбор глав: порядок из спеки, номера из регулярки. */
    fun parseChapters(spec: MangaSpec, html: String, pageUrl: String): List<SChapter> {
        val doc = Jsoup.parse(html, pageUrl)
        val attr = spec.chapters.linkAttr.ifBlank { "href" }
        val numberRx = runCatching { Regex(spec.chapters.numberRegex) }.getOrNull()
        val chapters = doc.select(spec.chapters.itemSelector).mapNotNull { item ->
            val link = spec.chapters.linkSelector
                ?.takeIf { it.isNotBlank() }
                ?.let { item.selectFirst(it) }
                ?: item.takeIf { it.tagName() == "a" }
                ?: item.selectFirst("a[$attr]")
                ?: return@mapNotNull null
            val href = link.absUrl(attr).ifBlank { link.attr(attr) }
            if (href.isBlank() || href.startsWith("javascript", ignoreCase = true)) return@mapNotNull null

            val raw = link.text().trim().ifBlank { link.attr("title") }
            if (raw.isBlank()) return@mapNotNull null
            val number = numberRx?.find(raw)?.groupValues?.getOrNull(1)
                ?.replace(',', '.')
                ?.toFloatOrNull()
                ?: -1f
            val name = if (spec.chapters.nameFromText) raw else "Глава " + number.toString()
            val date = dateOf(spec.chapters, item, link)

            SChapter.create().apply {
                url = href
                this.name = name
                chapter_number = number
                date_upload = date
            }
        }.distinctBy { it.url }
        return if (spec.chapters.newestFirst) chapters.reversed() else chapters
    }

    suspend fun pages(spec: MangaSpec, chapter: SChapter): List<Page> {
        val fetched = fetchPage(chapter.url, null)
        return parsePages(spec, fetched.html, fetched.url)
    }

    /** Чистый разбор страниц: адрес, отсев служебного, предел. */
    fun parsePages(spec: MangaSpec, html: String, pageUrl: String): List<Page> {
        val doc = Jsoup.parse(html, pageUrl)
        val root = spec.pages.containerSelector?.takeIf { it.isNotBlank() }
            ?.let { doc.selectFirst(it) }
            ?: doc
        val images = root.select(spec.pages.itemSelector)
        val urls = images.mapNotNull { img -> pictureOf(img, spec.pages.imageAttrs) }
            .filterNot { url -> spec.pages.dropPatterns.any { url.contains(it, ignoreCase = true) } }
            .distinct()
            .take(spec.pages.maxPages.coerceAtLeast(1))
        return urls.mapIndexed { index, url -> Page(index = index, url = url, imageUrl = url) }
    }

    private fun pictureOf(img: Element, attrs: List<String>): String? {
        for (name in attrs) {
            val raw = img.attr(name)
            if (raw.isBlank()) continue
            if (raw.startsWith("data:", ignoreCase = true)) continue
            val absolute = img.absUrl(name).ifBlank { raw }
            if (absolute.isNotBlank()) return absolute
        }
        return null
    }

    private fun dateOf(spec: ChaptersSpec, item: Element, link: Element): Long {
        val selector = spec.dateSelector?.takeIf { it.isNotBlank() } ?: return 0L
        val element = item.selectFirst(selector) ?: link.selectFirst(selector) ?: return 0L
        val raw = spec.dateAttr.takeIf { it.isNotBlank() }
            ?.let { element.attr(it) }
            ?: element.text()
        return parseDate(raw) ?: 0L
    }

    private fun text(doc: Document, selector: String?): String {
        if (selector.isNullOrBlank()) return ""
        val element = doc.selectFirst(selector) ?: return ""
        val content = element.attr("content")
        return content.ifBlank { element.text() }.trim()
    }

    private fun picture(doc: Document, selector: String?): String {
        if (selector.isNullOrBlank()) return ""
        val element = doc.selectFirst(selector) ?: return ""
        return element.absUrl("content").ifBlank { element.attr("content") }
            .ifBlank { element.absUrl("src").ifBlank { element.attr("src") } }
            .trim()
    }

    private fun genres(doc: Document, selector: String?): List<String> {
        if (selector.isNullOrBlank()) return emptyList()
        return doc.select(selector).mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    private fun hasNextPage(doc: Document, spec: MangaSpec, pageUrl: String): Boolean {
        val next = doc.selectFirst("a[rel=next], link[rel=next], a.next, a[href*='page/']") ?: return false
        val href = next.absUrl("href").ifBlank { next.attr("href") }
        return href.isNotBlank() && href != pageUrl
    }

    fun pageUrl(base: String, path: String, pagination: PaginationSpec, page: Int): String {
        val root = base.trimEnd('/')
        val clean = if (path.startsWith("http")) path else root + "/" + path.trimStart('/')
        if (page <= 1 || pagination.kind == "none") return clean
        val template = pagination.template.ifBlank { "page/{n}/" }
        val tail = template.replace("{n}", page.toString())
        return if (tail.startsWith("?")) clean + tail else clean.trimEnd('/') + "/" + tail.trimStart('/')
    }

    suspend fun fetchPage(url: String, referer: String?): FetchedPage = fetcher.page(url, referer)

    private fun statusOf(raw: String): Int = when {
        raw.isBlank() -> SManga.UNKNOWN
        raw.contains("онгоинг", true) || raw.contains("выходит", true) ||
            raw.contains("ongoing", true) || raw.contains("продолжается", true) -> SManga.ONGOING
        raw.contains("заверш", true) || raw.contains("completed", true) -> SManga.COMPLETED
        raw.contains("заморож", true) || raw.contains("приостанов", true) ||
            raw.contains("hiatus", true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    companion object {
        private val DOTTED = Regex("([0-9]{1,2})[.]([0-9]{1,2})[.]([0-9]{4})")
        private val ISO = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")

        /** Дата из текста: dd.MM.yyyy, dd/MM/yyyy, yyyy-MM-dd. Иначе null. */
        fun parseDate(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            ISO.find(text)?.let { match ->
                val (y, m, d) = match.destructured
                return millis(y.toIntOrNull(), m.toIntOrNull(), d.toIntOrNull())
            }
            DOTTED.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                return millis(y.toIntOrNull(), m.toIntOrNull(), d.toIntOrNull())
            }
            return null
        }

        private fun millis(year: Int?, month: Int?, day: Int?): Long? {
            if (year == null || month == null || day == null) return null
            if (month !in 1..12 || day !in 1..31 || year !in 1900..2999) return null
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(year, month - 1, day, 0, 0, 0)
            }
            return calendar.timeInMillis
        }
    }
}
