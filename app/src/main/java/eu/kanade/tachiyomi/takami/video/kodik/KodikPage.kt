package eu.kanade.tachiyomi.takami.video.kodik

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Одна серия плеера Kodik: у неё свой id и своя подпись. */
data class KodikSeries(
    val number: Int,
    val id: String,
    val hash: String,
    val title: String,
)

/** Один перевод: своя страница плеера со своими сериями. */
data class KodikTranslation(
    val id: String,
    val hash: String,
    val kind: String,
    val title: String,
    val episodes: Int,
)

/** Сезон: отдельная страница со своим набором серий. */
data class KodikSeason(
    val title: String,
    val id: String,
    val hash: String,
)

/** Что за видео на странице: id, hash и тип нужны запросу /ftor. */
data class KodikVideo(
    val id: String,
    val hash: String,
    val type: String,
)

/**
 * Разобранная страница плеера Kodik.
 *
 * Три оси лежат отдельно и не смешиваются: серия ([series]), перевод
 * ([translations]) и качество (появляется только из ответа /ftor).
 * Параметры ([params]) — подпись страницы, без них запрос не примут.
 */
data class KodikPage(
    val params: Map<String, String>,
    val video: KodikVideo,
    val series: List<KodikSeries> = emptyList(),
    val translations: List<KodikTranslation> = emptyList(),
    val seasons: List<KodikSeason> = emptyList(),
    /** Название озвучки, которая открыта на странице; пусто — не подписана. */
    val current: String = "",
    /** Номер серии, открытой на странице; 0 — списка серий нет. */
    val episode: Int = 0,
) {
    /** Адрес страницы перевода: по нему видно, чьи это серии. */
    fun translationUrl(translation: KodikTranslation, base: String = BASE): String =
        base + "/serial/" + translation.id + "/" + translation.hash + "/720p"

    /** Адрес страницы серии внутри перевода. */
    fun seriesUrl(translation: KodikTranslation, series: KodikSeries, base: String = BASE): String =
        base + "/serial/" + translation.id + "/" + series.hash + "/720p"

    companion object {
        const val BASE = "https://kodikplayer.com"
    }
}

/**
 * Разбор страницы плеера Kodik без сети.
 *
 * Всё, что здесь есть, лежит в самой странице: подпись запроса и списки
 * серий, переводов и сезонов. Ничего не додумываем: нет списка — пусто.
 */
object KodikPlayer {

    /** Домены плеера, который отдаёт ссылки только по запросу. */
    val domains = listOf(
        "kodikplayer.com", "kodik.cc", "kodik.biz", "kodik.info",
        "kodikdb.com", "aniqit.com", "anivod.com", "turkcinemka",
    )

    private val PARAMS = Regex("""var\s+urlParams\s*=\s*'(\{[^']*\})'""")

    private val VIDEO = Regex("""vInfo\.(id|hash|type)\s*=\s*'([^']*)'""")

    /** Это страница Kodik: у неё есть подпись запроса и данные видео. */
    fun looks(html: String): Boolean =
        html.contains("urlParams") && html.contains("vInfo.")

    /** Адрес ведёт на плеер Kodik. */
    fun isKodik(url: String): Boolean = domains.any { url.contains(it, ignoreCase = true) }

    /** Разбор страницы; null — это не страница Kodik. */
    fun parse(html: String): KodikPage? {
        if (html.isBlank()) return null
        val params = PARAMS.find(html)?.groupValues?.get(1)?.let { flat(it) }.orEmpty()
        val video = videoOf(html) ?: return null
        if (params.isEmpty()) return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val series = seriesOf(doc)
        val translations = translationsOf(doc)
        return KodikPage(
            params = params,
            video = video,
            series = series,
            translations = translations,
            seasons = seasonsOf(doc),
            current = selected(doc, "translations")?.attr("data-title")?.trim().orEmpty(),
            episode = selected(doc, "series")?.attr("value")?.trim()?.toIntOrNull() ?: 0,
        )
    }

    /** Плоский объект подписи: строки, числа и true/false. */
    private fun flat(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pairs = Regex(""""([^"]+)"\s*:\s*("([^"]*)"|true|false|-?\d+(?:\.\d+)?)""")
        pairs.findAll(text).forEach { match ->
            val value = match.groupValues[3].ifEmpty {
                match.groupValues[2].removeSurrounding("\"")
            }
            if (value.isNotEmpty()) result[match.groupValues[1]] = value
        }
        return result
    }

    private fun videoOf(html: String): KodikVideo? {
        val found = linkedMapOf<String, String>()
        VIDEO.findAll(html).forEach { found[it.groupValues[1]] = it.groupValues[2] }
        val id = found["id"].orEmpty()
        val hash = found["hash"].orEmpty()
        if (id.isEmpty() || hash.isEmpty()) return null
        return KodikVideo(id = id, hash = hash, type = found["type"].orEmpty())
    }

    private fun seriesOf(doc: Document): List<KodikSeries> =
        options(doc, "series").mapIndexedNotNull { index, node ->
            val id = node.attr("data-id").trim()
            val hash = node.attr("data-hash").trim()
            if (id.isEmpty() || hash.isEmpty()) return@mapIndexedNotNull null
            KodikSeries(
                number = node.attr("value").trim().toIntOrNull() ?: (index + 1),
                id = id,
                hash = hash,
                title = node.attr("data-title").ifBlank { node.text() }.trim(),
            )
        }

    private fun translationsOf(doc: Document): List<KodikTranslation> =
        options(doc, "translations").mapNotNull { node ->
            val id = node.attr("data-media-id").trim()
            val hash = node.attr("data-media-hash").trim()
            if (id.isEmpty() || hash.isEmpty()) return@mapNotNull null
            KodikTranslation(
                id = id,
                hash = hash,
                kind = node.attr("data-translation-type").trim(),
                title = node.attr("data-title").ifBlank { node.text() }.trim(),
                episodes = node.attr("data-episode-count").trim().toIntOrNull() ?: 0,
            )
        }

    /** Вариант, который плеер открыл: подпись и номер серии берём у него. */
    private fun selected(doc: Document, kind: String): Element? {
        val box = doc.selectFirst("div.serial-" + kind + "-box") ?: return null
        return box.selectFirst("select option[selected]")
    }

    private fun seasonsOf(doc: Document): List<KodikSeason> =
        options(doc, "seasons").mapNotNull { node ->
            val id = node.attr("data-serial-id").trim()
            val hash = node.attr("data-serial-hash").trim()
            if (id.isEmpty() || hash.isEmpty()) return@mapNotNull null
            KodikSeason(
                title = node.attr("data-title").ifBlank { node.text() }.trim(),
                id = id,
                hash = hash,
            )
        }

    /**
     * Варианты списка: серия, перевод или сезон.
     *
     * Класс ищется у обёртки, а не у самого списка: в разметке он висит на
     * `div`, внутри которого лежит `select`. Мобильная копия списка берётся
     * только если обычной нет.
     */
    private fun options(doc: Document, kind: String): List<Element> {
        val box = doc.selectFirst("div.serial-" + kind + "-box")
            ?: doc.selectFirst(".serial-" + kind + "-box")
            ?: return emptyList()
        return box.select("select option")
    }
}
