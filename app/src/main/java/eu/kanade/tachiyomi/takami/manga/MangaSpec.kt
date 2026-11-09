package eu.kanade.tachiyomi.takami.manga

import eu.kanade.tachiyomi.takami.PaginationSpec
import eu.kanade.tachiyomi.takami.SearchSpec
import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import kotlinx.serialization.Serializable

/**
 * Описание манга-источника, собранное генератором на устройстве.
 *
 * Это не код и не APK: набор селекторов и шаблонов, который
 * интерпретирует [MangaEngine]. Хранится в filesDir и никуда не
 * отправляется.
 */
@Serializable
data class MangaSpec(
    val host: String,
    val baseUrl: String,
    val name: String,
    val lang: String = "ru",
    /** auto | max — как выбирать качество картинки, если оно есть. */
    val qualityPreference: String = "auto",
    val nsfw: Boolean = false,
    val catalog: MangaCatalogSpec,
    val details: MangaDetailsSpec = MangaDetailsSpec(),
    val chapters: ChaptersSpec = ChaptersSpec(),
    val pages: PagesSpec = PagesSpec(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Версия схемы: та же, что у аниме-спеки. */
    val version: Int = SPEC_VERSION,
    /** Сколько проверок прошло при последней сборке. */
    val checksPassed: Int = 0,
)

/** Каталог: где список тайтлов и как листать страницы. */
@Serializable
data class MangaCatalogSpec(
    val path: String = "/",
    /** Путь ленты обновлений; null — берём тот же каталог. */
    val latestPath: String? = null,
    val itemSelector: String,
    val linkSelector: String = "a[href]",
    val titleSelector: String? = null,
    val posterSelector: String? = "img",
    val pagination: PaginationSpec = PaginationSpec(),
    val search: SearchSpec? = null,
)

/** Карточка тайтла. */
@Serializable
data class MangaDetailsSpec(
    val title: String = "h1",
    val poster: String? = "meta[property=og:image]",
    val author: String? = null,
    val artist: String? = null,
    val description: String? = "meta[property=og:description]",
    val genre: String? = null,
    val status: String? = null,
)

/** Список глав. */
@Serializable
data class ChaptersSpec(
    /** Блок, внутри которого лежат главы. */
    val itemSelector: String = "a[href]",
    /** Ссылка главы внутри блока; null — сама карточка. */
    val linkSelector: String? = null,
    /** Имя атрибута с адресом: href, data-href, data-url. */
    val linkAttr: String = "href",
    /** Номер главы из текста; пусто — номер не определяется. */
    val numberRegex: String = "([0-9]+([.,][0-9]+)?)",
    /** Подпись берём из текста; иначе «Глава N». */
    val nameFromText: Boolean = true,
    /** Селектор даты внутри блока; null — даты нет. */
    val dateSelector: String? = null,
    /** Атрибут даты; пусто — текст элемента. */
    val dateAttr: String = "",
    /** Порядок: сначала новые. */
    val newestFirst: Boolean = false,
)

/** Страницы главы. */
@Serializable
data class PagesSpec(
    /** Блок страниц; null — ищем по всему документу. */
    val containerSelector: String? = null,
    val itemSelector: String = "img",
    /** Атрибуты адреса по порядку: ленивые картинки держат data-src. */
    val imageAttrs: List<String> = listOf("data-src", "data-original", "src"),
    /** Что не считаем страницей: логотипы, аватары, баннеры. */
    val dropPatterns: List<String> = listOf("logo", "avatar", "banner", "emoji"),
    /** Сколько страниц считаем разумным пределом одной главы. */
    val maxPages: Int = 400,
)
