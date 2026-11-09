package eu.kanade.tachiyomi.takami.manga

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.net.URI

/**
 * Сборка манга-спеки по ссылке: перебирает известные формы
 * вёрстки, оставляет ту, что даёт карточки, и проверяет главы.
 *
 * Ничего не выдумывает: если шаг не подтвердился разметкой, спека
 * собирается, но итог говорит, какой шаг провалился.
 */
class MangaForge(
    private val engine: MangaEngine = MangaEngine(),
) {

    data class Built(
        val spec: MangaSpec,
        val check: MangaCheck,
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

        val best = pickCatalog(base, host, name)
        if (best == null) {
            return@withContext Failure(
                reason = MangaCheck.NO_CATALOG.key,
                message = "ни одна форма карточки не дала тайтлов",
            )
        }

        val spec = best.spec
        val first = best.items.firstOrNull()
        val chapters = if (first == null) {
            emptyList()
        } else {
            runCatching { engine.chapters(spec, first) }.getOrDefault(emptyList())
        }
        val pages = if (chapters.isEmpty()) {
            emptyList()
        } else {
            runCatching { engine.pages(spec, chapters.last()) }.getOrDefault(emptyList())
        }
        val check = MangaCheck.of(
            catalog = best.items.size,
            chapters = chapters.size,
            pages = pages.size,
        )
        val passed = listOf(best.items.size, chapters.size, pages.size).count { it > 0 }
        Built(
            spec = spec.copy(checksPassed = passed),
            check = check,
            catalogCount = best.items.size,
            chaptersCount = chapters.size,
            pagesCount = pages.size,
        )
    }

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
            if (best == null || items.size > best!!.items.size) {
                best = Candidate(spec, items)
            }
            if (items.size >= STRONG) break
        }
        val candidate = best ?: return null
        if (candidate.items.size < MINIMUM) {
            logcat { "[MangaForge] каталог не подтверждён: " + candidate.items.size }
            return null
        }
        return candidate.copy(spec = refine(candidate.spec))
    }

    /** Уточнение ссылки внутри карточки по видимой разметке. */
    private fun refine(spec: MangaSpec): MangaSpec {
        val catalog = spec.catalog
        val link = if (catalog.itemSelector.contains("a[href]")) "a[href]" else "a[href]"
        return spec.copy(catalog = catalog.copy(linkSelector = link))
    }

    private data class Candidate(val spec: MangaSpec, val items: List<eu.kanade.tachiyomi.source.model.SManga>)

    companion object {
        /** Сколько карточек достаточно, чтобы форма считалась рабочей. */
        const val MINIMUM = 3

        /** Уверенное попадание: дальше формы не перебираем. */
        const val STRONG = 12

        /** Формы карточки, которые встречаются чаще всего. */
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

        /** Формы ссылки на главу: подсказка, когда глав не нашли. */
        val CHAPTER_FORMS = listOf(
            "a[href*='chapter']",
            "a[href*='glava']",
            "li.chapter a",
            "div.chapter a",
            "a.chapter",
        )
    }
}
