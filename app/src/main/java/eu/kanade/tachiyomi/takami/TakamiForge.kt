package eu.kanade.tachiyomi.takami

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import eu.kanade.tachiyomi.takami.fetch.NetworkDiagnostics
import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import eu.kanade.tachiyomi.takami.spec.SpecHistory
import eu.kanade.tachiyomi.takami.spec.SpecJournal
import eu.kanade.tachiyomi.takami.spec.SpecMigration
import eu.kanade.tachiyomi.takami.spec.SpecRollback
import eu.kanade.tachiyomi.takami.spec.stamped
import eu.kanade.tachiyomi.takami.spec.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import rx.Observable
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

    // Плеер в этом приложении спрашивает не видео напрямую, а хостеры.
    // Отдаём один хостер — сам сайт — с уже разрешённым списком видео.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val videos = videos(episode)
        if (videos.isEmpty()) return emptyList()
        return listOf(
            Hoster(
                hosterUrl = episode.url,
                hosterName = HOSTER_NAME,
                videoList = videos,
            ),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.let { return it }
        return videos(SEpisode.create().apply { url = hoster.hosterUrl })
    }

    private val videosByEpisode = java.util.concurrent.ConcurrentHashMap<String, List<Video>>()

    private suspend fun videos(episode: SEpisode): List<Video> {
        videosByEpisode[episode.url]?.let { return it }
        val resolved = engine.videos(spec, episode)
        if (resolved.isNotEmpty()) videosByEpisode[episode.url] = resolved
        return resolved
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = videos(episode)

    private companion object {
        private const val HOSTER_NAME = "Плеер сайта"
    }

    // Устаревший Rx-путь: приложение ходит через корутины, но интерфейс требует реализацию.
    @Deprecated("Use getPopularAnime")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { runBlocking { getPopularAnime(page) } }

    @Deprecated("Use getLatestUpdates")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { runBlocking { getLatestUpdates(page) } }

    @Deprecated("Use getSearchAnime")
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        Observable.fromCallable { runBlocking { getSearchAnime(page, query, filters) } }
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

    private var dir: File? = null
    private val _specs = MutableStateFlow<List<SiteSpec>>(emptyList())
    val specs: StateFlow<List<SiteSpec>> = _specs.asStateFlow()

    fun init(context: Context) {
        // Сетевой слой готовим здесь же: до первого запроса к сайту он уже настроен.
        Fetcher.init(context)
        val target = File(context.filesDir, DIR).apply { mkdirs() }
        val first = dir == null
        dir = target
        val loaded = read(target)
        // Перечитываем при каждом старте: файл мог появиться после первого вызова.
        if (first || loaded.size != _specs.value.size) {
            logcat(LogPriority.DEBUG) { "Takami: источников на устройстве — " + loaded.size }
            _specs.value = loaded
        }
    }

    val engine: SpecEngine by lazy { SpecEngine() }

    fun sources(): List<AnimeSource> = _specs.value.map { VirtualAnimeSource(it, engine) }

    /** Иконка источника: своя картинка, если выбрана, иначе иконка сайта. */
    fun iconModelOf(sourceId: Long): Any? {
        val spec = _specs.value.firstOrNull { it.sourceId == sourceId } ?: return null
        val local = spec.iconPath?.let { java.io.File(it) }
        if (local != null && local.exists()) return local
        val url = spec.iconUrl?.takeIf { it.isNotBlank() } ?: return null
        // Относительный адрес из og:image достраиваем до полного.
        return if (url.startsWith("/")) spec.baseUrl.trimEnd('/') + url else url
    }

    fun source(host: String): VirtualAnimeSource? = _specs.value
        .firstOrNull { it.host == host }
        ?.let { VirtualAnimeSource(it, engine) }

    /**
     * Сохраняет спеку и решает, оставить ли новую версию.
     *
     * Правка считается удачной, только если источник проходит не меньше проверок,
     * чем до неё. Иначе возвращается прошлая рабочая версия, а причина уходит в
     * журнал: пользователь ничего не замечает, источник просто продолжает работать.
     */
    suspend fun save(spec: SiteSpec, failedChecks: List<String> = emptyList()): SiteSpec =
        withContext(Dispatchers.IO) {
            val history = SpecHistory(dir)
            val candidate = spec.stamped()
            val previous = _specs.value.firstOrNull { it.host == candidate.host }
            // Новая версия сначала становится рабочей: сравнить можно только с тем,
            // что уже записано.
            history.record(candidate)
            val decision = SpecRollback.apply(previous, candidate, failedChecks, SpecJournal(dir))
            val accepted = if (decision.rolledBack) {
                // Откат: снимаем неудачную версию и возвращаем прошлую.
                history.rollback(candidate.host)
                    ?: previous?.also { history.record(it) }
                    ?: candidate
            } else {
                candidate
            }
            val next = _specs.value.filterNot { it.host == accepted.host } + accepted
            _specs.value = next
            write(next)
            // Список источников фильтруется по включённым языкам: без этого шага
            // добавленный источник просто не появится в «Источниках».
            enableLanguage(accepted.lang)
            NetworkDiagnostics.noteSpecCheck(accepted.host, failedChecks.isEmpty(), failedChecks.size)
            accepted
        }

    suspend fun remove(host: String) = withContext(Dispatchers.IO) {
        val next = _specs.value.filterNot { it.host == host }
        _specs.value = next
        write(next)
        SpecHistory(dir).forget(host)
    }

    private fun enableLanguage(lang: String) {
        if (lang.isBlank()) return
        runCatching {
            val preferences = Injekt.get<SourcePreferences>()
            val current = preferences.enabledLanguages().get()
            if (lang !in current) {
                preferences.enabledLanguages().set(current + lang)
            }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Takami: не включил язык " + lang } }
    }

    /** Стабильный id: хост не меняется — не меняется и источник в библиотеке. */
    fun sourceIdOf(host: String): Long {
        val crc = CRC32().apply { update(("takami:" + host).toByteArray()) }
        return ID_BASE + (crc.value and 0x0000_FFFF_FFFFL)
    }

    /** Чтение спек с подъёмом файлов старой установки до текущей версии схемы. */
    private fun read(target: File): List<SiteSpec> {
        val file = File(target, FILE)
        if (!file.exists()) return emptyList()
        val result = SpecMigration.migrate(file.readText())
        result.reason?.let { reason ->
            logcat(LogPriority.ERROR) { "Takami: " + reason }
        }
        if (result.changed) {
            // Файл без версии переписываем сразу: иначе миграция повторялась бы
            // при каждом запуске.
            runCatching { writeAtomically(file, SpecMigration.encode(result.specs)) }
        }
        return result.specs
    }

    /**
     * Запись через временный файл: при сбое старый список остаётся целым.
     *
     * Версия схемы пишется рядом со спеками — по ней чтение понимает, что перед ним.
     */
    private fun write(specs: List<SiteSpec>) {
        val target = dir ?: return
        val current = specs.map { spec ->
            if (spec.version == SPEC_VERSION) spec else spec.copy(version = SPEC_VERSION)
        }
        runCatching { writeAtomically(File(target, FILE), SpecMigration.encode(current)) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Takami: не записал спеки" } }
    }
}
