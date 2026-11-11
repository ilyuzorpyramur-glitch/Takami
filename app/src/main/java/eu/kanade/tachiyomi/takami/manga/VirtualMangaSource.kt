package eu.kanade.tachiyomi.takami.manga

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Источник, который живёт на устройстве: [CatalogueSource] поверх
 * [MangaSpec].
 *
 * Для приложения он неотличим от APK-расширения: тот же интерфейс,
 * та же карточка в источниках, та же читалка и загрузки.
 */
@Suppress("OVERRIDE_DEPRECATION")
class VirtualMangaSource(
    private val spec: MangaSpec,
    private val engine: MangaEngine = MangaEngine(),
) : CatalogueSource {

    override val id: Long = MangaSourceId.of(spec.host)
    override val name: String = spec.name
    override val lang: String = spec.lang
    override val supportsLatest: Boolean = true

    override fun getFilterList(): FilterList = MangaFilters.build(spec)

    override suspend fun getPopularManga(page: Int): MangasPage =
        engine.catalogue(spec, page).let { MangasPage(it.first, it.second) }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        engine.latest(spec, page).let { MangasPage(it.first, it.second) }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        if (query.isBlank()) {
            getPopularManga(page)
        } else {
            engine.search(spec, page, query).let { MangasPage(it.first, it.second) }
        }

    override suspend fun getMangaDetails(manga: SManga): SManga = engine.details(spec, manga)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = engine.chapters(spec, manga)

    override suspend fun getPageList(chapter: SChapter): List<Page> = engine.pages(spec, chapter)
}
