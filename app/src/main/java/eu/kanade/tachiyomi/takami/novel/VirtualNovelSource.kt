package eu.kanade.tachiyomi.takami.novel

import eu.kanade.tachiyomi.novelsource.NovelCatalogueSource
import eu.kanade.tachiyomi.novelsource.model.NovelFilterList
import eu.kanade.tachiyomi.novelsource.model.NovelsPage
import eu.kanade.tachiyomi.novelsource.model.SNovel
import eu.kanade.tachiyomi.novelsource.model.SNovelChapter
import rx.Observable

/**
 * Источник, который живёт на устройстве: [NovelCatalogueSource]
 * поверх [NovelSpec].
 *
 * Для приложения он неотличим от плагина: та же карточка, та же
 * читалка, та же полка. Сеть идёт через [NovelEngine].
 */
class VirtualNovelSource(
    private val spec: NovelSpec,
    private val engine: NovelEngine = NovelEngine(),
) : NovelCatalogueSource {

    override val id: Long = NovelSourceId.of(spec.host)
    override val name: String = spec.name
    override val lang: String = spec.lang
    override val supportsLatest: Boolean = true

    override fun getFilterList(): NovelFilterList = NovelFilters.build(spec)

    override suspend fun getPopularNovels(page: Int): NovelsPage =
        engine.catalogue(spec, page).let { NovelsPage(it.first, it.second) }

    override suspend fun getLatestUpdates(page: Int): NovelsPage =
        engine.latest(spec, page).let { NovelsPage(it.first, it.second) }

    override suspend fun getSearchNovels(
        page: Int,
        query: String,
        filters: NovelFilterList,
    ): NovelsPage = if (query.isBlank()) {
        getPopularNovels(page)
    } else {
        engine.search(spec, page, query).let { NovelsPage(it.first, it.second) }
    }

    override suspend fun getNovelDetails(novel: SNovel): SNovel = engine.details(spec, novel)

    override suspend fun getChapterList(novel: SNovel): List<SNovelChapter> =
        engine.chapters(spec, novel)

    override suspend fun getChapterText(chapter: SNovelChapter): String =
        engine.chapterText(spec, chapter)

    // Приложение зовёт suspend-версии; старый путь оставлен ради
    // совместимости интерфейса и честно не поддерживается.
    override fun fetchPopularNovels(page: Int): Observable<NovelsPage> =
        throw UnsupportedOperationException()

    override fun fetchSearchNovels(
        page: Int,
        query: String,
        filters: NovelFilterList,
    ): Observable<NovelsPage> = throw UnsupportedOperationException()

    override fun fetchLatestUpdates(page: Int): Observable<NovelsPage> =
        throw UnsupportedOperationException()
}
