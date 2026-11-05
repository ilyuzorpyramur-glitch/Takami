package eu.kanade.tachiyomi.takami.video.kodik

import eu.kanade.tachiyomi.takami.video.Budget
import eu.kanade.tachiyomi.takami.video.QualityLink
import eu.kanade.tachiyomi.takami.video.VideoAxes

/** Запрос с телом: плеер отдаёт ссылки только так. */
typealias RelayPost = suspend (url: String, body: String, referer: String?) -> String?

/** Чтение служебного ответа: это JSON, ворота HTML его не пропускают. */
typealias RelayText = suspend (url: String) -> String?

/**
 * Ссылки Kodik: страница плеера плюс один запрос.
 *
 * Разметка страницы ссылок не содержит — в ней только подпись и списки.
 * Ссылки приходят ответом на запрос, поэтому шаг отдельный: сначала
 * разбор, потом запрос, потом раскодирование адресов. Потолок запросов
 * общий на серию, чтобы плеер не мог увести обход в бесконечность.
 */
object KodikChain {

    /** Это страница Kodik: её разбираем и спрашиваем ссылки. */
    fun looks(html: String): Boolean = KodikPlayer.looks(html)

    /**
     * Оси страницы плеера: названия переводов и число серий перевода.
     *
     * Число серий берём у открытого перевода: у разных студий их бывает
     * разное количество, и одно общее число соврало бы.
     */
    fun axes(html: String): VideoAxes {
        val page = KodikPlayer.parse(html) ?: return VideoAxes()
        val current = page.translations.firstOrNull { it.title == page.current }
        return VideoAxes(
            dubbings = page.translations.map { it.title }.filter { it.isNotBlank() },
            episodes = current?.episodes ?: page.series.size,
            current = page.current,
        )
    }

    /**
     * Ссылки открытой серии.
     *
     * [dubbing] — подпись озвучки, если вызывающий её знает; пусто —
     * берём ту, что открыта на странице. Нет подписи ни там, ни там —
     * ось остаётся пустой, а не выдуманной.
     */
    suspend fun links(
        html: String,
        pageUrl: String,
        dubbing: String,
        budget: Budget,
        post: RelayPost,
    ): List<QualityLink> {
        val page = KodikPlayer.parse(html) ?: return emptyList()
        if (!budget.take()) return emptyList()
        val text = post(KodikLinks.FTOR, KodikLinks.body(page), pageUrl) ?: return emptyList()
        return KodikLinks.parse(text, dubbing.ifBlank { page.current }, pageUrl)
    }

    /**
     * Ссылки названной серии того же перевода.
     *
     * Нужны там, где страница открыта на первой серии, а играть надо
     * выбранную: id и hash серии лежат в списке страницы.
     */
    suspend fun linksOf(
        page: KodikPage,
        series: KodikSeries,
        pageUrl: String,
        dubbing: String,
        budget: Budget,
        post: RelayPost,
    ): List<QualityLink> {
        if (!budget.take()) return emptyList()
        val text = post(KodikLinks.FTOR, KodikLinks.body(page, series), pageUrl) ?: return emptyList()
        return KodikLinks.parse(text, dubbing.ifBlank { page.current }, pageUrl)
    }
}
