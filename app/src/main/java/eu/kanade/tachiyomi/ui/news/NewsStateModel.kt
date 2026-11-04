package eu.kanade.tachiyomi.ui.news

import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import eu.kanade.tachiyomi.takami.fetch.FetchPriority
import eu.kanade.tachiyomi.takami.news.NewsCache
import eu.kanade.tachiyomi.takami.news.NewsDetector
import eu.kanade.tachiyomi.takami.news.NewsFeed
import eu.kanade.tachiyomi.takami.news.NewsItem
import eu.kanade.tachiyomi.takami.news.NewsPolicy
import eu.kanade.tachiyomi.takami.news.dedupNews
import eu.kanade.tachiyomi.takami.news.sortNews
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние новостей: кэш всех источников плюс тихий детект разделов.
 *
 * Модель одна и для блока на главной, и для экрана «Все новости».
 * Лечение, детект и сеть запускаются только здесь, по явному пути.
 */
class NewsStateModel {

    private val _items = MutableStateFlow<List<NewsItem>>(emptyList())
    val items: StateFlow<List<NewsItem>> = _items.asStateFlow()

    /** Сколько источников уже проверено за это открытие главной. */
    private var detectedThisOpening = 0

    /** Тихий проход: детект неразобранных источников и обновление протухшего кэша. */
    suspend fun sync(opening: Boolean, force: Boolean = false) {
        // Детект: только никогда не проверявшиеся источники, не больше двух
        // за одно открытие главной. Неудачная проверка тоже отмечается.
        if (opening && detectedThisOpening < NewsPolicy.MAX_DETECT_PER_OPENING) {
            val pending = TakamiForge.specs.value
                .filter { NewsPolicy.canDetect(it.host) && it.news == null && it.newsCheckedAt == 0L }
                .take(NewsPolicy.MAX_DETECT_PER_OPENING - detectedThisOpening)
            for (spec in pending) {
                detectedThisOpening++
                detectAndSave(spec)
            }
        }
        val specs = TakamiForge.specs.value
        val shown = specs.filter { source ->
            NewsPolicy.canShow(source.host) && (source.news != null || NewsPolicy.feedFor(source.host) != null)
        }
        val collected = shown.map { source ->
            runCatching { NewsFeed.refresh(source, force) }.getOrDefault(emptyList())
        }
        _items.value = sortNews(dedupNews(collected.flatten()))
    }

    /** Один детект: главная и раздел добираются в фоне, через общий Fetcher. */
    private suspend fun detectAndSave(spec: eu.kanade.tachiyomi.takami.SiteSpec) {
        val checkedAt = System.currentTimeMillis()
        val news = runCatching {
            val homeHtml = Fetcher.get().get(spec.baseUrl, null, FetchPriority.BACKGROUND)
            NewsDetector.detect(spec.baseUrl, homeHtml) { url ->
                Fetcher.get().get(url, spec.baseUrl, FetchPriority.BACKGROUND)
            }
        }.getOrNull()
        TakamiForge.save(spec.copy(news = news, newsCheckedAt = checkedAt), emptyList())
    }
}
