package eu.kanade.tachiyomi.takami.news

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import tachiyomi.domain.discovery.model.normalizeDiscoveryTitle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Разбор источников записей: раздел новостей по [NewsSpec] и ленты RSS/Atom.
 *
 * Ничего не знает про конкретные сайты: только инварианты разметки.
 * Полный текст не сохраняется — из описания берётся короткий анонс.
 */
object NewsParser {

    /** Анонс влезает примерно в три строки карточки. */
    private const val SNIPPET_CHARS = 180
    private const val SNIPPET_WORDS = 30

    /** Метки дат лент: RFC-822 у RSS (именованная зона и цифры), ISO у Atom. */
    private val RFC_NAMED = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
    private val RFC_NUMERIC = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
    private val ISO_WITH_ZONE = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** Вырезает теги, схлопывает переносы и обрезает по словам, а не по байтам. */
    fun snippetOf(raw: String): String {
        val text = raw.replace(Regex("(?s)<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.length <= SNIPPET_CHARS) return text
        val words = text.split(' ').take(SNIPPET_WORDS)
        var result = words.joinToString(" ")
        while (result.length > SNIPPET_CHARS && result.contains(' ')) {
            result = result.substringBeforeLast(' ')
        }
        return result.trimEnd(',', ';', ':', '-')
    }

    /** Дата из текста ленты или карточки; кривая дата — null, а не ноль. */
    fun parseDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text, ISO_WITH_ZONE).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text, RFC_NUMERIC).toInstant().toEpochMilli() }
        runCatching {
            return java.time.ZonedDateTime.parse(text, RFC_NAMED).toInstant().toEpochMilli()
        }
        runCatching { return LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        return null
    }

    /** RSS 2.0: item → заголовок, ссылка, анонс, дата. Записи без ссылки или заголовка отбрасываются. */
    fun parseRss(xml: String): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            NewsItem(
                url = link,
                title = title,
                snippet = snippetOf(textOf(item, "description", "content|encoded")),
                publishedAt = parseDate(textOf(item, "pubDate", "dc|date")),
                source = "",
            )
        }
    }

    /** Atom: entry → заголовок, ссылка alternate, анонс, дата updated или published. */
    fun parseAtom(xml: String): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        return doc.select("entry").mapNotNull { entry ->
            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            val link = (entry.selectFirst("link[rel=alternate]") ?: entry.selectFirst("link"))
                ?.attr("href")?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            NewsItem(
                url = link,
                title = title,
                snippet = snippetOf(textOf(entry, "summary", "content")),
                publishedAt = parseDate(textOf(entry, "published", "updated")),
                source = "",
            )
        }
    }

    /** Лента любого из двух форматов: какая разобралась — та и работает. */
    fun parseFeed(xml: String): List<NewsItem> {
        val rss = parseRss(xml)
        if (rss.isNotEmpty()) return rss
        return parseAtom(xml)
    }

    /**
     * Раздел новостей по [NewsSpec]: карточки со ссылкой и непустым заголовком.
     * Относительные адреса склеиваются с [baseUrl], лишние отбрасываются.
     */
    fun parseSection(html: String, baseUrl: String, spec: NewsSpec): List<NewsItem> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val home = doc.location() ?: baseUrl
        return doc.select(spec.itemSelector)
            .mapNotNull { card -> toItem(card, spec, home) }
            .distinctBy { it.url }
            .take(spec.maxItems.coerceAtLeast(1))
    }

    private fun toItem(card: Element, spec: NewsSpec, baseUrl: String): NewsItem? {
        val link = if (spec.linkSelector.isBlank()) card else card.selectFirst(spec.linkSelector) ?: return null
        val url = link.absUrl("href").ifBlank { link.attr("href") }.trim()
        if (!url.startsWith("http")) return null
        val title = spec.titleSelector
            ?.let { card.selectFirst(it)?.text() }
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim()
        if (title.isEmpty()) return null
        val dateRaw = spec.dateSelector?.let { card.selectFirst(it)?.text() }
        var body = card.text()
        if (body.startsWith(title)) body = body.removePrefix(title)
        dateRaw?.takeIf { it.isNotBlank() && body.startsWith(it) }?.let { body = body.removePrefix(it) }
        return NewsItem(
            url = url,
            title = title,
            snippet = snippetOf(body),
            publishedAt = dateRaw?.let { parseDate(it) },
            source = "",
        )
    }

    /** Первое непустое поле из перечисленных: имена с namespace работают. */
    private fun textOf(item: Element, vararg names: String): String =
        names.firstNotNullOfOrNull { name -> item.getElementsByTag(name).firstOrNull()?.text() }.orEmpty()
}

/**
 * Как показать дату записи: сегодня, вчера или число месяца.
 *
 * Кривая дата даёт [DayMark.NONE] — пустая строка, а не «01.01.1970».
 * Слова «сегодня» и «вчера» подставляет экран, здесь — только разметка дня.
 */
enum class DayMark { TODAY, YESTERDAY, DAY, NONE }

fun dayMarkOf(publishedAt: Long?, now: Long): DayMark {
    val at = publishedAt ?: return DayMark.NONE
    if (at <= 0L) return DayMark.NONE
    val zone = ZoneOffset.UTC
    val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
    val day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(at), zone)
    return when (day) {
        today -> DayMark.TODAY
        today.minusDays(1) -> DayMark.YESTERDAY
        else -> DayMark.DAY
    }
}

/** Число месяца для записей старше вчера: дд.мм. */
fun dateTextOf(publishedAt: Long?, now: Long): String = when (dayMarkOf(publishedAt, now)) {
    DayMark.DAY -> DateTimeFormatter.ofPattern("dd.MM").format(
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(publishedAt ?: 0L), ZoneOffset.UTC),
    )
    else -> ""
}

/** Пустая заглушка вместо страницы: лента или раздел отдали пустоту. */
fun isStubPage(html: String): Boolean {
    if (html.isBlank()) return true
    val doc: Document = Jsoup.parse(html)
    if (doc.text().contains("RSS in offline mode", true)) return true
    val body = doc.body() ?: return true
    return body.select("a[href]").isEmpty() && body.text().length < 200
}

/** Дедуп: сначала по адресу записи, затем по нормализованному заголовку. */
fun dedupNews(items: List<NewsItem>): List<NewsItem> {
    val seenUrls = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    return items.filter { item ->
        if (!seenUrls.add(item.url)) return@filter false
        val key = normalizeDiscoveryTitle(item.title)
        seenTitles.add(key)
    }
}

/** Свежие сверху; запись без даты — в конец. */
fun sortNews(items: List<NewsItem>): List<NewsItem> =
    items.sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }
