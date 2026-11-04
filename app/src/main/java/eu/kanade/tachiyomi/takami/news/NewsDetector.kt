package eu.kanade.tachiyomi.takami.news

import eu.kanade.tachiyomi.takami.Forge
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Детект раздела новостей: сначала доказать, что новости есть, потом показывать.
 *
 * Признаки раздела: ссылка со словом-подсказкой, на странице не меньше трёх
 * однотипных карточек со ссылкой и заголовком, адреса карточек ведут на другие
 * страницы. Ни одно условие не выполняется — null, и это нормальный результат.
 * Сеть идёт через переданный лямбдой [Fetcher]-путь: в тестах вместо него подмена.
 */
object NewsDetector {

    /** Слова-подсказки в адресе или тексте ссылки на раздел. */
    private val KEYWORDS = listOf("новост", "news", "блог", "blog", "статьи", "press")

    private const val MIN_CARDS = 3

    /** Найденная ссылка на раздел: адрес и путь относительно базового. */
    data class Candidate(val url: String, val path: String)

    /** Первая ссылка-кандидат на уже загруженной странице; без сети. */
    fun findCandidate(doc: Document, pageUrl: String): Candidate? {
        val homePath = pathOf(pageUrl)
        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            if (!url.startsWith("http")) continue
            val path = pathOf(url)
            if (path == homePath) continue
            val haystack = (a.text() + ' ' + url).lowercase()
            if (KEYWORDS.any { it in haystack }) return Candidate(url, path)
        }
        return null
    }

    /**
     * Проверяет страницу раздела: собирает группу однотипных карточек.
     * Меньше трёх карточек, пустая страница или заглушка — null.
     */
    fun buildSpec(candidate: Candidate, sectionHtml: String, baseUrl: String): NewsSpec? {
        if (isStubPage(sectionHtml)) return null
        val doc = runCatching { Jsoup.parse(sectionHtml, candidate.url) }.getOrNull() ?: return null
        val helpers = Forge()
        val links = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            val title = a.text().trim()
            if (!href.startsWith("http") || title.isEmpty()) null else Card(href, title, a.parent() ?: a)
        }
        // Однотипные карточки: родители с одинаковой сигнатурой повторяются.
        val group = links
            .groupBy { helpers.signatureOf(it.parent) ?: it.parent.tagName().lowercase() }
            .values
            .filter { cards ->
                cards.size >= MIN_CARDS &&
                    cards.distinctBy { it.href }.size >= MIN_CARDS &&
                    cards.none { pathOf(it.href) == candidate.path }
            }
            .maxByOrNull { it.size } ?: return null
        val itemSelector = helpers.selectorOf(group.first().parent) ?: return null
        return NewsSpec(path = candidate.path, itemSelector = itemSelector, linkSelector = "a[href]")
    }

    /**
     * Полный детект: страница главной уже загружена, раздел добирается лямбдой.
     * Любой сбой сети или ответ-заглушка дают null без исключения.
     */
    suspend fun detect(baseUrl: String, homeHtml: String, fetch: suspend (String) -> String?): NewsSpec? {
        val doc = runCatching { Jsoup.parse(homeHtml, baseUrl) }.getOrNull() ?: return null
        val candidate = findCandidate(doc, baseUrl) ?: return null
        val sectionHtml = runCatching { fetch(candidate.url) }.getOrNull()
            ?.takeIf { !isStubPage(it) } ?: return null
        return buildSpec(candidate, sectionHtml, baseUrl)
    }

    private data class Card(val href: String, val title: String, val parent: Element)

    /** Путь внутри адреса: «https://host/news/?p=1» → «/news/». */
    private fun pathOf(url: String): String {
        val tail = url.substringAfter("://", "")
        val path = tail.substringAfter('/', "")
        val clean = "/" + path.substringBefore('?').substringBefore('#')
        return if (clean == "/") "/" else clean.trimEnd('/') + "/"
    }
}
