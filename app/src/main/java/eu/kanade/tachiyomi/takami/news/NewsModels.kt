package eu.kanade.tachiyomi.takami.news

import kotlinx.serialization.Serializable

/**
 * Необязательный раздел новостей источника: описан в спеке, разбирается без кода.
 *
 * Появляется только если детект доказал, что раздел живой: не меньше трёх
 * однотипных карточек со ссылками и заголовками.
 */
@Serializable
data class NewsSpec(
    /** Путь раздела относительно baseUrl, например «/news/». */
    val path: String,
    val itemSelector: String,
    val linkSelector: String = "a[href]",
    val titleSelector: String? = null,
    val dateSelector: String? = null,
    /** Не больше десяти записей: больше человек всё равно не читает. */
    val maxItems: Int = 10,
)

/** Одна запись ленты: заголовок, анонс, дата и адрес — полного текста здесь нет. */
@Serializable
data class NewsItem(
    val url: String,
    val title: String,
    /** Текст анонса: без тегов, переносы схлопнуты, обрезан по словам. */
    val snippet: String = "",
    /** Время публикации; null — запись без даты, в конце списка. */
    val publishedAt: Long? = null,
    /** Имя источника для карточки. */
    val source: String,
)

/** Снимок ленты источника в кэше: записи и когда они сняты. */
@Serializable
data class NewsSnapshot(
    val savedAt: Long = 0L,
    val items: List<NewsItem> = emptyList(),
)
