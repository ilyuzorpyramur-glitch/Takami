package eu.kanade.tachiyomi.takami.news

import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import eu.kanade.tachiyomi.takami.fetch.FetchPriority

/**
 * Обновление ленты источника: раздел новостей или проверенная RSS-лента.
 *
 * Сеть — только через [Fetcher]: один запрос на источник за обновление,
 * паузы и бюджет домена внутри. Сеть упала — отдаём прошлый снимок;
 * пустой ответ считается мёртвой лентой и кэш не затирает.
 */
object NewsFeed {

    /** Максимум записей с одного источника. */
    private const val MAX_PER_SOURCE = 10

    /**
     * Записи источника из кэша или с сайта.
     *
     * [force] — только для кнопки «Обновить»: человек у экрана, запрос идёт
     * с приоритетом INTERACTIVE. Без него — тихое обновление в фоне.
     */
    suspend fun refresh(source: SiteSpec, force: Boolean = false): List<NewsItem> {
        val now = System.currentTimeMillis()
        val cached = NewsCache.load(source.sourceId)
        if (!force && cached != null && now - cached.savedAt < NewsPolicy.TTL_MS) return cached.items
        if (!NewsPolicy.canShow(source.host)) return cached?.items ?: emptyList()
        if (NewsPolicy.isDeadFeed(source.host, now)) return cached?.items ?: emptyList()

        val url = sectionUrl(source) ?: rssUrl(source.host, source.baseUrl) ?: return cached?.items ?: emptyList()
        val priority = if (force) FetchPriority.INTERACTIVE else FetchPriority.BACKGROUND
        val html = runCatching { Fetcher.get().get(url, source.baseUrl, priority) }.getOrNull()

        if (html == null) {
            // Сеть упала: кэш живёт дальше, ошибки на главной не бывает.
            return cached?.items ?: emptyList()
        }
        if (isStubPage(html)) {
            NewsPolicy.markDeadFeed(source.host, now)
            return cached?.items ?: emptyList()
        }

        val parsed = source.news
            ?.let { NewsParser.parseSection(html, source.baseUrl, it) }
            ?: NewsParser.parseFeed(html)
        if (parsed.isEmpty()) {
            NewsPolicy.markDeadFeed(source.host, now)
            return cached?.items ?: emptyList()
        }
        val items = sortNews(dedupNews(parsed).take(MAX_PER_SOURCE))
            .map { it.copy(source = source.name) }
        NewsCache.save(source.sourceId, NewsSnapshot(savedAt = now, items = items))
        return items
    }

    /** Адрес раздела из спеки, если детект его нашёл. */
    private fun sectionUrl(source: SiteSpec): String? = source.news
        ?.takeIf { it.path.isNotBlank() }
        ?.let { source.baseUrl.trimEnd('/') + (if (it.path.startsWith("/")) it.path else "/" + it.path) }

    /** Адрес проверенной ленты, если домен в списке. */
    private fun rssUrl(host: String, baseUrl: String): String? = NewsPolicy.feedFor(host)
        ?.takeIf { it.isNotBlank() }
        ?.let { baseUrl.trimEnd('/') + (if (it.startsWith("/")) it else "/" + it) }
}
