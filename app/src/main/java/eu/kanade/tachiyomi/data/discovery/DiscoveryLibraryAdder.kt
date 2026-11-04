package eu.kanade.tachiyomi.data.discovery

import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.entries.novel.model.toDomainNovel
import eu.kanade.domain.source.anime.interactor.GetEnabledAnimeSources
import eu.kanade.domain.source.manga.interactor.GetEnabledMangaSources
import eu.kanade.domain.source.novel.interactor.GetEnabledNovelSources
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.novelsource.NovelCatalogueSource
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.discovery.model.DiscoveryMediaType
import tachiyomi.domain.discovery.model.normalizeDiscoveryTitle
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.novel.interactor.NetworkToLocalNovel
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.source.novel.service.NovelSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * «+» на карточке подборки: добавление имеет смысл только для рекомендаций,
 * пришедших из установленного источника (provider = имя источника, а тайтл
 * взят из его же каталога), поэтому поиск идёт точечно в этом источнике и
 * точное совпадение практически гарантировано. Для внешних провайдеров
 * (anilist_trend, jikan_trend, mangaupdates, ...) provider не резолвится в
 * источник, а точный поиск по чужим источникам почти никогда не совпадал —
 * в этих случаях сразу возвращает false, и UI открывает глобальный поиск
 * вместо слепого перебора.
 */
class DiscoveryLibraryAdder {

    companion object {
        const val SOURCE_TIMEOUT_MS = 8_000L
    }

    suspend fun addFromProvider(mediaType: DiscoveryMediaType, title: String, provider: String?): Boolean =
        addWithId(mediaType, title, provider) != null

    /**
     * То же добавление, но с идентификатором записи: свайпу нужно назначить
     * категорию «Посмотреть позже» уже добавленному тайтлу.
     */
    suspend fun addWithId(mediaType: DiscoveryMediaType, title: String, provider: String?): Long? =
        withContext(Dispatchers.IO) {
            if (provider.isNullOrBlank()) return@withContext null
            val clean = normalizeDiscoveryTitle(title)
            try {
                when (mediaType) {
                    DiscoveryMediaType.MANGA -> addManga(provider, clean, title)
                    DiscoveryMediaType.ANIME -> addAnime(provider, clean, title)
                    DiscoveryMediaType.NOVEL -> addNovel(provider, clean, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "[DiscoveryAdd] FAILED '$title' via '$provider': ${e.message}" }
                null
            }
        }

    private suspend fun addManga(provider: String, clean: String, query: String): Long? {
        val sourceId = Injekt.get<GetEnabledMangaSources>().subscribe().first()
            .firstOrNull { it.name == provider }?.id ?: return null
        val source = Injekt.get<MangaSourceManager>().getOrStub(sourceId) as? CatalogueSource ?: return null
        val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            source.getSearchManga(1, query, source.getFilterList())
        } ?: return null
        val match = page.mangas.firstOrNull { normalizeDiscoveryTitle(it.title) == clean } ?: return null
        val added = Injekt.get<NetworkToLocalManga>()
            .await(listOf(match.toDomainManga(sourceId)), autoFavorite = true)
        return added.firstOrNull()?.id
    }

    private suspend fun addAnime(provider: String, clean: String, query: String): Long? {
        val sourceId = Injekt.get<GetEnabledAnimeSources>().subscribe().first()
            .firstOrNull { it.name == provider }?.id ?: return null
        val source = Injekt.get<AnimeSourceManager>().getOrStub(sourceId) as? AnimeCatalogueSource ?: return null
        val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            source.getSearchAnime(1, query, source.getFilterList())
        } ?: return null
        val match = page.animes.firstOrNull { normalizeDiscoveryTitle(it.title) == clean } ?: return null
        val added = Injekt.get<NetworkToLocalAnime>()
            .await(listOf(match.toDomainAnime(sourceId)), autoFavorite = true)
        return added.firstOrNull()?.id
    }

    private suspend fun addNovel(provider: String, clean: String, query: String): Long? {
        val sourceId = Injekt.get<GetEnabledNovelSources>().subscribe().first()
            .firstOrNull { it.name == provider }?.id ?: return null
        val source = Injekt.get<NovelSourceManager>().getOrStub(sourceId) as? NovelCatalogueSource ?: return null
        val page = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            source.getSearchNovels(1, query, source.getFilterList())
        } ?: return null
        val match = page.novels.firstOrNull { normalizeDiscoveryTitle(it.title) == clean } ?: return null
        val added = Injekt.get<NetworkToLocalNovel>()
            .await(listOf(match.toDomainNovel(sourceId)), autoFavorite = true)
        return added.firstOrNull()?.id
    }
}
