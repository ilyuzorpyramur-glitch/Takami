package eu.kanade.tachiyomi.takami.manga

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import tachiyomi.core.common.util.system.logcat
import java.net.URI

/**
 * Сборка манга-спеки по ссылке.
 *
 * Порядок: сначала смотрим, что отдал сайт, затем ищем селекторы по
 * самой разметке ([MangaAutoDetect]), и только если структура не
 * нашлась — перебираем известные формы карточки. Каждый шаг
 * возвращает счётчик, поэтому итог говорит правду: что нашли и где
 * остановились.
 */
class MangaForge(
    private val engine: MangaEngine = MangaEngine(),
) {

    data class Built(
        val spec: MangaSpec,
        val check: MangaCheck,
        val markup: MangaMarkup,
        val catalogCount: Int,
        val chaptersCount: Int,
        val pagesCount: Int,
    )

    data class Failure(val reason: String, val message: String)

    suspend fun build(rawUrl: String): Any = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        val parsed = runCatching { URI(url) }.getOrNull()
        val host = parsed?.host?.removePrefix("www.")
        if (host.isNullOrBlank()) {
            return@withContext Failure(reason = "bad_url", message = "ссылка не разобрана")
        }
        val scheme = parsed?.scheme ?: "https"
        val base = scheme + "://" + parsed?.host.orEmpty()
        val name = host.substringBefore('.').replaceFirstChar { it.uppercase() }

        val home = runCatching { engine.fetchPage(base, null) }.getOrNull()
        if (home == null) {
            return@withContext Failure(reason = MangaCheck.BLOCKED.key, message = "главная не открылась")
        }
        val markup = MangaMarkup.detect(home.html)
        if (markup == MangaMarkup.JS_ONLY || markup == MangaMarkup.ANTI_BOT) {
            return@withContext Failure(reason = markup.key, message = MangaMarkup.text(markup))
        }

        var spec = MangaSpec(host = host, baseUrl = base, name = name, catalog = emptyCatalog())
        val found = MangaAutoDetect.catalog(Jsoup.parse(home.html, home.url))
        var items = emptyList<eu.kanade.tachiyomi.source.model.SManga>()
        if (found != null) {
            spec = spec.copy(
                catalog = MangaCatalogSpec(
                    itemSelector = found.itemSelector,
                    linkSelector = found.linkSelector,
                    titleSelector = found.titleSelector,
                    posterSelector = found.posterSelector,
                ),
            )
            items = runCatching { engine.parseCatalog(spec, home.html, home.url).first }
                .getOrDefault(emptyList())
        }
        if (items.size < MINIMUM) {
            val fallback = pickCatalog(base, host, name)
            if (fallback != null && fallback.items.size > items.size) {
                spec = fallback.spec
                items = fallback.items
            }
        }
        if (items.size < MINIMUM) {
            return@withContext Failure(
                reason = MangaCheck.NO_CATALOG.key,
                message = "карточки не найдены ни по структуре, ни по формам",
            )
        }

        val first = items.first()
        val titleHtml = runCatching { engine.fetchPage(first.url, base).html }.getOrNull()
        if (titleHtml != null) {
            spec = refineChapters(spec, titleHtml, first.url)
        }
        val chapters = runCatching { engine.chapters(spec, first) }.getOrDefault(emptyList())

        var pages = emptyList<eu.kanade.tachiyomi.source.model.Page>()
        val lastChapter = chapters.lastOrNull()
        if (lastChapter != null) {
            val chapterHtml = runCatching { engine.fetchPage(lastChapter.url, base).html }.getOrNull()
            if (chapterHtml != null) {
                spec = refinePages(spec, chapterHtml, lastChapter.url)
            }
            pages = runCatching { engine.pages(spec, lastChapter) }.getOrDefault(emptyList())
        }

        val check = MangaCheck.of(
            catalog = items.size,
            chapters = chapters.size,
            pages = pages.size,
            markup = markup,
        )
        val passed = listOf(items.size, chapters.size, pages.size).count { it > 0 }
        logcat {
            "[MangaForge] " + host + ": каталог " + items.size +
                ", глав " + chapters.size + ", страниц " + pages.size
        }
        Built(
            spec = spec.copy(checksPassed = passed),
            check = check,
            markup = markup,
            catalogCount = items.size,
            chaptersCount = chapters.size,
            pagesCount = pages.size,
        )
    }

    /** Главы: селектор из разметки карточки тайтла. */
    private fun refineChapters(spec: MangaSpec, html: String, pageUrl: String): MangaSpec {
        val doc = Jsoup.parse(html, pageUrl)
        val found = MangaAutoDetect.chapters(doc) ?: return spec
        return spec.copy(
            chapters = spec.chapters.copy(
                itemSelector = found.itemSelector,
                linkSelector = found.linkSelector,
            ),
        )
    }

    /** Страницы: селектор из разметки первой главы. */
    private fun refinePages(spec: MangaSpec, html: String, pageUrl: String): MangaSpec {
        val doc = Jsoup.parse(html, pageUrl)
        val found = MangaAutoDetect.pages(doc) ?: return spec
        return spec.copy(
            pages = spec.pages.copy(
                containerSelector = found.containerSelector,
                itemSelector = found.itemSelector,
            ),
        )
    }

    /**
     * Запасной путь: известные формы карточки.
     *
     * Нужен, когда структура списка неоднородна и поиск по повтору
     * не находит общего блока.
     */
    private suspend fun pickCatalog(base: String, host: String, name: String): Candidate? {
        var best: Candidate? = null
        for (itemSelector in ITEM_FORMS) {
            val spec = MangaSpec(
                host = host,
                baseUrl = base,
                name = name,
                catalog = MangaCatalogSpec(itemSelector = itemSelector),
            )
            val items = runCatching { engine.catalogue(spec, 1) }.getOrNull()?.first.orEmpty()
            if (best == null || items.size > (best?.items?.size ?: 0)) {
                best = Candidate(spec, items)
            }
            if (items.size >= STRONG) break
        }
        val candidate = best ?: return null
        return candidate.takeIf { it.items.size >= MINIMUM }
    }

    private fun emptyCatalog() = MangaCatalogSpec(itemSelector = "article")

    private data class Candidate(
        val spec: MangaSpec,
        val items: List<eu.kanade.tachiyomi.source.model.SManga>,
    )

    companion object {
        /** Сколько карточек достаточно, чтобы структура считалась найденной. */
        const val MINIMUM = 3

        /** Уверенное попадание: дальше формы не перебираем. */
        const val STRONG = 12

        /** Запасные формы карточки, если структура не нашлась. */
        val ITEM_FORMS = listOf(
            "div.card",
            "article",
            "li.item",
            "div.item",
            "div.manga-item",
            "div.post-card",
            "div.book-item",
            "li.manga",
            "div.catalog-item",
        )
    }
}
