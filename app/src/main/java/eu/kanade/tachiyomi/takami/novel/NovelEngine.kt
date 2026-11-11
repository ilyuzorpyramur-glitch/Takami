package eu.kanade.tachiyomi.takami.novel

import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
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
 * Исполнитель ранобэ-спеки: каталог, карточка, главы, текст.
 *
 * Разбор отделён от сети: `parse*` — чистые функции, их проверяют
 * тесты без сети. Сеть идёт через [Fetcher]: там лимиты и повторы.
 */
class NovelEngine(
    private val fetcherOverride: Fetcher? = null,
) {

    private val fetcher: Fetcher
        get() = fetcherOverride ?: Fetcher.get()

    suspend fun catalogue(spec: NovelSpec, page: Int): Pair<List<SNovel>, Boolean> {
        val url = pageUrl(spec.baseUrl, spec.catalog.path, spec.catalog.pagination, page)
        val fetched = fetchPage(url, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    suspend fun latest(spec: NovelSpec, page: Int): Pair<List<SNovel>, Boolean> {
        val path = spec.catalog.latestPath ?: spec.catalog.path
        val url = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val fetched = fetchPage(url, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    suspend fun search(spec: NovelSpec, page: Int, query: String): Pair<List<SNovel>, Boolean> {
        val search = spec.catalog.search ?: return catalogue(spec, page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val path = search.template.replace("{q}", encoded)
        val url = pageUrl(spec.baseUrl, path, spec.catalog.pagination, page)
        val fetched = fetchPage(url, spec.baseUrl)
        return parseCatalog(spec, fetched.html, fetched.url)
    }

    /** Чистый разбор каталога: ни одного запроса. */
    fun parseCatalog(spec: NovelSpec, html: String, pageUrl: String): Pair<List<SNovel>, Boolean> {
        val doc = Jsoup.parse(html, pageUrl)
        val items = doc.select(spec.catalog.itemSelector)
            .mapNotNull { item -> toNovel(spec, item) }
            .distinctBy { it.url }
        return items to hasNextPage(doc, pageUrl)
    }

    private fun toNovel(spec: NovelSpec, item: Element): SNovel? {
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

        return SNovel.create().apply {
            url = href
            this.title = title.trim()
            thumbnail_url = poster
        }
    }

    suspend fun details(spec: NovelSpec, novel: SNovel): SNovel {
        val fetched = fetchPage(novel.url, spec.baseUrl)
        return parseDetails(spec, fetched.html, novel)
    }

    /** Чистый разбор карточки: дополняет уже собранный тайтл. */
    fun parseDetails(spec: NovelSpec, html: String, novel: SNovel): SNovel {
        val doc = Jsoup.parse(html, novel.url)
        val result = novel.copy()
        text(doc, spec.details.title).takeIf { it.isNotBlank() }?.let { result.title = it }
        picture(doc, spec.details.poster).takeIf { it.isNotBlank() }
            ?.let { result.thumbnail_url = it }
        text(doc, spec.details.author).takeIf { it.isNotBlank() }?.let { result.author = it }
        text(doc, spec.details.description).takeIf { it.isNotBlank() }
            ?.let { result.description = it }
        genres(doc, spec.details.genre).takeIf { it.isNotEmpty() }
            ?.let { result.genre = it.joinToString(", ") }
        result.status = statusOf(text(doc, spec.details.status))
        return result
    }

    suspend fun chapters(spec: NovelSpec, novel: SNovel): List<SNovelChapter> {
        val fetched = fetchPage(novel.url, spec.baseUrl)
        return parseChapters(spec, fetched.html, fetched.url)
    }

    /** Чистый разбор глав: порядок из спеки, номер из регулярки. */
    fun parseChapters(spec: NovelSpec, html: String, pageUrl: String): List<SNovelChapter> {
        val doc = Jsoup.parse(html, pageUrl)
        val attr = spec.chapters.linkAttr.ifBlank { "href" }
        val numberRx = runCatching { Regex(spec.chapters.numberRegex) }.getOrNull()
        val chapters = doc.select(spec.chapters.itemSelector).mapNotNull { item ->
            val link = spec.chapters.linkSelector
                ?.takeIf { it.isNotBlank() }
                ?.let { item.selectFirst(it) }
                ?: item.takeIf { it.tagName() == "a" }
                ?: item.selectFirst("a[" + attr + "]")
                ?: return@mapNotNull null
            val href = link.absUrl(attr).ifBlank { link.attr(attr) }
            if (href.isBlank()) return@mapNotNull null
            if (href.startsWith("javascript", ignoreCase = true)) return@mapNotNull null

            val raw = link.text().trim().ifBlank { link.attr("title") }
            if (raw.isBlank()) return@mapNotNull null
            val number = numberRx?.find(raw)?.groupValues?.getOrNull(1)
                ?.replace(',', '.')
                ?.toFloatOrNull()
                ?: -1f
            val name = if (spec.chapters.nameFromText) raw else "Глава " + number.toString()
            val date = dateOf(spec.chapters, item, link)

            SNovelChapter.create().apply {
                url = href
                this.name = name
                chapter_number = number
                date_upload = date
            }
        }.distinctBy { it.url }
        return if (spec.chapters.newestFirst) chapters.reversed() else chapters
    }

    suspend fun chapterText(spec: NovelSpec, chapter: SNovelChapter): String {
        val fetched = fetchPage(chapter.url, null)
        return parseText(spec, fetched.html, fetched.url)
    }

    /**
     * Чистый разбор текста главы.
     *
     * Служебные блоки выбрасываются до чтения: иначе
     * в главу попадут
     * меню, реклама и подписи. Возвращается HTML абзацев — читалка
     * умеет его разметить.
     */
    fun parseText(spec: NovelSpec, html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        val root = spec.text.containerSelector?.takeIf { it.isNotBlank() }
            ?.let { doc.selectFirst(it) }
            ?: NovelAutoDetect.text(doc)?.let { doc.selectFirst(it.containerSelector) }
            ?: doc.body()
        spec.text.dropSelectors.forEach { selector ->
            runCatching { root.select(selector).remove() }
        }
        val paragraphs = root.select(spec.text.paragraphSelector)
            .map { it.text().trim() }
            .filter { it.length >= spec.text.minParagraph }
        val text = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n") { "<p>" + escape(it) + "</p>" }
        } else {
            val flat = root.text().trim()
            if (flat.length < spec.text.minParagraph) "" else "<p>" + escape(flat) + "</p>"
        }
        return text.take(spec.text.maxChars.coerceAtLeast(1000))
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun dateOf(spec: NovelChaptersSpec, item: Element, link: Element): Long {
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
        return doc.select(selector)
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    private fun hasNextPage(doc: Document, pageUrl: String): Boolean {
        val next = doc.selectFirst("a[rel=next], link[rel=next], a.next, a[href*='page/']")
            ?: return false
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
        raw.isBlank() -> SNovel.UNKNOWN
        raw.contains("онгоинг", true) || raw.contains("выходит", true) ||
            raw.contains("ongoing", true) || raw.contains("продолжается", true) -> SNovel.ONGOING
        raw.contains("заверш", true) || raw.contains("completed", true) -> SNovel.COMPLETED
        raw.contains("заморож", true) || raw.contains("приостанов", true) ||
            raw.contains("hiatus", true) -> SNovel.ON_HIATUS
        else -> SNovel.UNKNOWN
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
