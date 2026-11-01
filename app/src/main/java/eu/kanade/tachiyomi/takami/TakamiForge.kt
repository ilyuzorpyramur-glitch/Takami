package eu.kanade.tachiyomi.takami

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.zip.CRC32

/**
 * Источник, который живёт на устройстве: реализация [AnimeSource] поверх [SiteSpec].
 *
 * Для приложения он неотличим от APK-расширения: тот же интерфейс, та же карточка
 * источника, те же списки и плеер.
 */
class VirtualAnimeSource(
    private val spec: SiteSpec,
    private val engine: SpecEngine = SpecEngine(),
) : eu.kanade.tachiyomi.animesource.AnimeCatalogueSource {

    override val id: Long = spec.sourceId
    override val name: String = spec.name
    override val lang: String = spec.lang
    override val supportsLatest: Boolean = true

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val (animes, hasNext) = engine.catalogue(spec, page)
        return AnimesPage(animes, hasNext)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val (animes, hasNext) = engine.latest(spec, page)
        return AnimesPage(animes, hasNext)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val (animes, hasNext) = engine.search(spec, page, query)
        return AnimesPage(animes, hasNext)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = engine.details(spec, anime)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = engine.episodes(spec, anime)

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = engine.videos(spec, episode)
}

/**
 * Реестр источников, созданных в приложении.
 *
 * Спеки лежат одним JSON-файлом в filesDir: никуда не уходят, живут только у пользователя.
 */
object TakamiForge {

    private const val DIR = "takami"
    private const val FILE = "sources.json"
    private const val ID_BASE = 0x7A_0000_0000L

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private var dir: File? = null
    private val _specs = MutableStateFlow<List<SiteSpec>>(emptyList())
    val specs: StateFlow<List<SiteSpec>> = _specs.asStateFlow()

    fun init(context: Context) {
        if (dir != null) return
        val target = File(context.filesDir, DIR).apply { mkdirs() }
        dir = target
        _specs.value = read(target)
    }

    val engine: SpecEngine by lazy { SpecEngine() }

    fun sources(): List<AnimeSource> = _specs.value.map { VirtualAnimeSource(it, engine) }

    fun source(host: String): VirtualAnimeSource? = _specs.value
        .firstOrNull { it.host == host }
        ?.let { VirtualAnimeSource(it, engine) }

    suspend fun save(spec: SiteSpec) = withContext(Dispatchers.IO) {
        val updated = spec.copy(updatedAt = System.currentTimeMillis())
        val next = _specs.value.filterNot { it.host == updated.host } + updated
        _specs.value = next
        write(next)
        updated
    }

    suspend fun remove(host: String) = withContext(Dispatchers.IO) {
        val next = _specs.value.filterNot { it.host == host }
        _specs.value = next
        write(next)
    }

    /** Стабильный id: хост не меняется — не меняется и источник в библиотеке. */
    fun sourceIdOf(host: String): Long {
        val crc = CRC32().apply { update(("takami:" + host).toByteArray()) }
        return ID_BASE + (crc.value and 0x0000_FFFF_FFFFL)
    }

    private fun read(target: File): List<SiteSpec> {
        val file = File(target, FILE)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<SiteSpec>>(file.readText()) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Takami: не прочитал спеки" } }
            .getOrDefault(emptyList())
    }

    private fun write(specs: List<SiteSpec>) {
        val target = dir ?: return
        runCatching {
            File(target, FILE).writeText(json.encodeToString(specs))
        }.onFailure { logcat(LogPriority.ERROR, it) { "Takami: не записал спеки" } }
    }
}
