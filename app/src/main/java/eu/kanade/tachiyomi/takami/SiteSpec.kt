package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import kotlinx.serialization.Serializable

/**
 * Описание источника, собранное генератором на устройстве.
 *
  * Это не код и не APK: набор селекторов и шаблонов, который
  * интерпретирует [SpecEngine].
 * Хранится в filesDir, никуда не отправляется.
 */
@Serializable
data class SiteSpec(
    val host: String,
    val baseUrl: String,
    val name: String,
    val lang: String = "ru",
    val engine: String = ENGINE_GENERIC,
    val iconUrl: String? = null,
    /**
     * Иконка, выбранная пользователем: путь к файлу на
     * устройстве. */
    val iconPath: String? = null,
    /** auto | max — как выбирать качество из доступных. */
    val qualityPreference: String = "auto",
    /** Озвучки, которые нужны пользователю; пусто — все. */
    val dubbings: List<String> = emptyList(),
    val nsfw: Boolean = false,
    val catalog: CatalogSpec,
    val details: DetailsSpec = DetailsSpec(),
    val episodes: EpisodesSpec = EpisodesSpec(),
    val video: VideoSpec = VideoSpec(),
    /** Фильтры источника: описаны в спеке, экран строит их без кода. */
    val filters: List<FilterSpec> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * Версия схемы, на которой записана спека. Файл без неё
     * — первая версия. */
    val version: Int = SPEC_VERSION,
    /**
     * Сколько проверок источника прошло при последней
     * сборке спеки. */
    val checksPassed: Int = 0,
    /** Озвучки, найденные в разметке: их показывает экран выбора. */
    val dubbingSpecs: List<DubbingSpec> = emptyList(),
) {
    val sourceId: Long
        get() = TakamiForge.sourceIdOf(host)

    /**
     * Озвучки, которые видит пользователь.
     *
      * Пусто — сайт дорожки не различает, и экран говорит
      * об этом прямо,
     * а не показывает пустой список.
     */
    fun availableDubbings(): List<String> = dubbingSpecs.map { it.name }.distinct()

    companion object {
        const val ENGINE_DLE = "dle"
        const val ENGINE_WORDPRESS = "wordpress"
        const val ENGINE_GENERIC = "generic"
    }
}

@Serializable
data class CatalogSpec(
    /** Путь популярного каталога относительно [SiteSpec.baseUrl]. */
    val path: String = "/",
    /** Путь ленты обновлений; null — берём тот же каталог. */
    val latestPath: String? = null,
    /** Контейнер карточки: внутри ищем ссылку и постер. */
    val itemSelector: String,
    /** Ссылка на тайтл внутри карточки. */
    val linkSelector: String = "a[href]",
    val titleSelector: String? = null,
    val posterSelector: String? = "img",
    val pagination: PaginationSpec = PaginationSpec(),
    val search: SearchSpec? = null,
)

@Serializable
data class PaginationSpec(
    /** path | query | none */
    val kind: String = "path",
    /** Шаблон с {n}: «page/{n}/» или «?page={n}». */
    val template: String = "page/{n}/",
)

@Serializable
data class SearchSpec(
    /** query | path */
    val kind: String = "query",
    /** Шаблон с {q}: «/?do=search&subaction=search&story={q}». */
    val template: String = "/?s={q}",
)

@Serializable
data class DetailsSpec(
    val title: String = "h1",
    val poster: String? = "meta[property=og:image]",
    val description: String? = "meta[property=og:description]",
    val genre: String? = null,
    val status: String? = null,
)

@Serializable
data class EpisodesSpec(
    /** Блок, внутри которого лежат серии. */
    val itemSelector: String = "a[href]",
    /** Ссылка серии внутри блока; null — сама карточка. */
    val linkSelector: String? = null,
    /** Имя атрибута с адресом: href, data-iframe, data-src… */
    val linkAttr: String = "href",
    /** Номер серии из текста. */
    val numberRegex: String = "(\\d+)",
    /** Подпись берём из текста; если пусто — «Серия N». */
    val nameFromText: Boolean = true,
)

@Serializable
data class VideoSpec(
    /** auto | iframe | links */
    val kind: String = "auto",
    val iframeSelector: String? = null,
    /** Пары «метка + адрес» в разметке плеера. */
    val labelRegex: String = "\\[([^\\]]{1,16})]\\s*(https?://[^\\s\"',]+)",
    /** Сколько раз разворачивать вложенные iframe. */
    val iframeDepth: Int = 2,
    /**
     * Известные плееры: по адресу понятно, как читать карту
     * файлов. */
    val hosters: List<HosterSpec> = emptyList(),
    /** Откуда брать качество: label | url | hls | map | auto. */
    val qualityFrom: String = "auto",
    /** Читать мастер-плейлист HLS, чтобы получить список качеств. */
    val hlsMaster: Boolean = true,
    /** Порядок качеств в списке: сначала лучшее. */
    val preferred: List<String> = listOf("1080p", "720p", "480p", "360p"),
    /** Как искать озвучки: tabs | labels | none | auto. */
    val dubbingMode: String = "auto",
    /** Выбранное качество для запуска: auto, max или метка. */
    val chosen: String = "auto",
)

/** Известный плеер: имя, адрес и способ разбора. */
@Serializable
data class HosterSpec(
    /** playerjs, alloha, sibnet — без чужих брендов сайтов. */
    val name: String,
    /** Шаблон адреса, по которому узнаём плеер. */
    val urlPattern: String,
    /** playerjs | hls | iframe */
    val kind: String,
    /** Шаблон запроса к плееру, если он отдаёт карту отдельно. */
    val apiTemplate: String? = null,
)

/** Озвучка в разметке источника. */
@Serializable
data class DubbingSpec(
    val name: String,
    val selector: String,
    val episodesSelector: String? = null,
    val language: String? = null,
)
