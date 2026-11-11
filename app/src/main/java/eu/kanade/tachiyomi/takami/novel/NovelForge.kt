package eu.kanade.tachiyomi.takami.novel

import eu.kanade.tachiyomi.takami.fetch.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI

/**
 * Сборка ранобэ-спеки по одной ссылке.
 *
 * Порядок: что отдал сайт, есть ли карточки, есть ли главы, есть ли
 * текст. Каждый шаг может отказать, и отказ
 * возвращается причиной, а
 * не пустой спекой.
 */
class NovelForge(
    private val engine: NovelEngine = NovelEngine(),
) {

    data class Built(
        val spec: NovelSpec,
        val check: NovelCheck,
        val markup: NovelMarkup,
        val catalogCount: Int,
        val chaptersCount: Int,
        val textChars: Int,
    )

    data class Failure(val reason: String, val message: String)

    suspend fun build(rawUrl: String): Any = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return@withContext fail("bad_url", "пустая ссылка")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return@withContext fail("bad_url", "ссылка не разбирается")
        val host = uri.host?.removePrefix("www.")
            ?: return@withContext fail("bad_url", "в ссылке нет домена")
        val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val base = scheme + "://" + uri.host + rootPath(uri.path)

        val page = runCatching { engine.fetchPage(base, null) }.getOrNull()
            ?: return@withContext fail("failed", "главная не открылась")
        if (page.code >= 400) return@withContext fail(MangaReasons.BLOCKED, "код " + page.code)

        val markup = NovelMarkup.detect(page.html)
        val doc = Jsoup.parse(page.html, page.url)

        val detected = NovelAutoDetect.catalog(doc)
        val catalog = detected?.let {
            NovelCatalogSpec(
                path = rootPath(uri.path),
                itemSelector = it.itemSelector,
                linkSelector = it.linkSelector,
                titleSelector = it.titleSelector,
                posterSelector = it.posterSelector,
            )
        } ?: NovelCatalogSpec(itemSelector = "article")

        var spec = NovelSpec(
            host = host,
            baseUrl = base,
            name = siteName(doc, host),
            lang = "ru",
            catalog = catalog,
            createdAt = System.currentTimeMillis(),
        )

        val firstPage = engine.parseCatalog(spec, page.html, page.url)
        val catalogCount = firstPage.first.size
        if (catalogCount == 0) {
            val check = NovelCheck.of(0, 0, 0, markup = markup)
            return@withContext Failure(check.key, NovelCheck.text(check, 0, 0, 0))
        }

        val title = firstPage.first.first()
        val titlePage = runCatching { engine.fetchPage(title.url, base) }.getOrNull()
            ?: return@withContext fail("failed", "карточка тайтла не открылась")

        val chaptersFound = NovelAutoDetect.chapters(Jsoup.parse(titlePage.html, titlePage.url))
        if (chaptersFound != null) {
            spec = spec.copy(
                chapters = spec.chapters.copy(itemSelector = chaptersFound.itemSelector),
            )
        }
        val chapters = engine.parseChapters(spec, titlePage.html, titlePage.url)
        if (chapters.isEmpty()) {
            val check = NovelCheck.of(catalogCount, 0, 0, markup = markup)
            return@withContext Failure(check.key, NovelCheck.text(check, catalogCount, 0, 0))
        }

        val chapterUrl = chapters.last().url
        val chapterPage = runCatching { engine.fetchPage(chapterUrl, base) }.getOrNull()
            ?: return@withContext fail("failed", "глава не открылась")
        val block = NovelAutoDetect.text(Jsoup.parse(chapterPage.html, chapterPage.url))
        if (block != null) {
            spec = spec.copy(text = spec.text.copy(containerSelector = block.containerSelector))
        }
        val chapterText = engine.parseText(spec, chapterPage.html, chapterPage.url)
        val check = NovelCheck.of(catalogCount, chapters.size, chapterText.length, markup = markup)
        if (check != NovelCheck.OK) {
            return@withContext Failure(
                check.key,
                NovelCheck.text(check, catalogCount, chapters.size, chapterText.length),
            )
        }

        spec = spec.copy(checksPassed = 4)
        Built(
            spec = spec,
            check = check,
            markup = markup,
            catalogCount = catalogCount,
            chaptersCount = chapters.size,
            textChars = chapterText.length,
        )
    }

    private fun fail(reason: String, message: String): Failure = Failure(reason, message)

    /**
    * Корень каталога: ссылка на тайтл не должна стать
    * адресом каталога.
     */
    private fun rootPath(path: String?): String {
        val clean = path.orEmpty().trim()
        if (clean.isEmpty() || clean == "/") return "/"
        val parts = clean.split("/").filter { it.isNotBlank() }
        return "/" + parts.take(1).joinToString("/") + "/"
    }

    private fun siteName(doc: org.jsoup.nodes.Document, host: String): String {
        val og = doc.selectFirst("meta[property=og:site_name]")?.attr("content")
        if (!og.isNullOrBlank()) return og.trim()
        val title = doc.title().trim()
        if (title.isNotEmpty()) return title.substringBefore(" — ").substringBefore(" | ").trim()
        return host
    }
}

/**
* Коды причин, общие со снимком манги: экран
* показывает один словарь.
 */
object MangaReasons {
    const val BLOCKED = "blocked"
}
