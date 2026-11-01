package eu.kanade.tachiyomi.takami

import kotlinx.serialization.Serializable

/**
 * Описание источника, собранное генератором на устройстве.
 *
 * Это не код и не APK: набор селекторов и шаблонов, который интерпретирует [SpecEngine].
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
    val nsfw: Boolean = false,
    val catalog: CatalogSpec,
    val details: DetailsSpec = DetailsSpec(),
    val episodes: EpisodesSpec = EpisodesSpec(),
    val video: VideoSpec = VideoSpec(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val sourceId: Long
        get() = TakamiForge.sourceIdOf(host)

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
)
