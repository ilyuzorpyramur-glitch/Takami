package eu.kanade.tachiyomi.takami.novel

import eu.kanade.tachiyomi.takami.PaginationSpec
import eu.kanade.tachiyomi.takami.SearchSpec
import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import kotlinx.serialization.Serializable

/**
 * Описание ранобэ-источника, собранное генератором
 * на устройстве.
 *
 * Кода сайта здесь нет: только селекторы и шаблоны, которые
 * понимает [NovelEngine]. Хранится в filesDir.
 */
@Serializable
data class NovelSpec(
    val host: String,
    val baseUrl: String,
    val name: String,
    val lang: String = "ru",
    val nsfw: Boolean = false,
    val catalog: NovelCatalogSpec,
    val details: NovelDetailsSpec = NovelDetailsSpec(),
    val chapters: NovelChaptersSpec = NovelChaptersSpec(),
    val text: NovelTextSpec = NovelTextSpec(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = SPEC_VERSION,
    val checksPassed: Int = 0,
)

/** Каталог: где карточки и как листать. */
@Serializable
data class NovelCatalogSpec(
    val path: String = "/",
    /** Путь ленты обновлений; null — тот же каталог. */
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
data class NovelDetailsSpec(
    val title: String = "h1",
    val poster: String? = "meta[property=og:image]",
    val author: String? = null,
    val description: String? = "meta[property=og:description]",
    val genre: String? = null,
    val status: String? = null,
)

/** Список глав. */
@Serializable
data class NovelChaptersSpec(
    val itemSelector: String = "a[href]",
    val linkSelector: String? = null,
    val linkAttr: String = "href",
    val numberRegex: String = "([0-9]+([.,][0-9]+)?)",
    val nameFromText: Boolean = true,
    val dateSelector: String? = null,
    val dateAttr: String = "",
    val newestFirst: Boolean = false,
)

/** Текст главы: из чего собирать и что выбросить. */
@Serializable
data class NovelTextSpec(
    /** Блок с текстом; null — самый длинный блок абзацев. */
    val containerSelector: String? = null,
    /** Абзацы внутри блока. */
    val paragraphSelector: String = "p",
    /** Служебные блоки: их текст в главу не идёт. */
    val dropSelectors: List<String> = listOf(
        "script",
        "style",
        "nav",
        "footer",
        "aside",
        ".comments",
        ".advert",
    ),
    /** Короче этого абзац не считается текстом. */
    val minParagraph: Int = 40,
    /** Предел длины главы: защита от «бесконечной» страницы. */
    val maxChars: Int = 400_000,
)
