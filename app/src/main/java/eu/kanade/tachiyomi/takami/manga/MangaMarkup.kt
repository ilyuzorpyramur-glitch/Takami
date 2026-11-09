package eu.kanade.tachiyomi.takami.manga

/**
 * Что отдал сайт: готовую разметку или только оболочку.
 *
 * Часть манга-сайтов собирает карточки в браузере: сервер присылает
 * пустой каркас и скрипт. Разбирать такой ответ бессмысленно, и
 * говорить «каталог пуст» нельзя — это разные вещи, и лечатся они
 * по-разному.
 */
enum class MangaMarkup(val key: String) {
    /** Карточки есть в ответе сервера: разбираем селекторами. */
    SERVER("server"),

    /** Страницу собирает браузер: разметки в ответе нет. */
    JS_ONLY("js_only"),

    /** Сайт закрылся проверкой браузера. */
    ANTI_BOT("anti_bot"),

    /** Ответ пустой или не похож на страницу. */
    EMPTY("empty"),
    ;

    companion object {
        /** Признаки каркаса: имя фреймворка или пустой корень приложения. */
        private val JS_MARKERS = listOf(
            "window.__NUXT__",
            "__NEXT_DATA__",
            "window.__INITIAL_STATE__",
            "data-server-rendered",
            "ng-version=",
        )

        private val SPA_ROOT = Regex(
            "<div[^>]+id=\"(app|root|__nuxt|__next)\"",
            RegexOption.IGNORE_CASE,
        )

        private val ANTI_BOT = listOf(
            "just a moment",
            "cf-browser-verification",
            "checking your browser",
            "ddos-guard",
            "captcha",
            "attention required",
        )

        /** Пустая разметка: тегов меньше, чем нужно для любой карточки. */
        fun detect(html: String): MangaMarkup {
            val text = html.trim()
            if (text.isEmpty() || text.length < 200) return EMPTY
            val lower = text.lowercase()
            if (ANTI_BOT.any { lower.contains(it) }) return ANTI_BOT
            if (JS_MARKERS.any { lower.contains(it.lowercase()) }) return JS_ONLY
            if (SPA_ROOT.containsMatchIn(text) && !hasCards(text)) return JS_ONLY
            return SERVER
        }

        /** Признак карточки: ссылка вместе с картинкой в одном блоке. */
        private fun hasCards(html: String): Boolean =
            Regex("<(a|div)[^>]*href=\"[^\"]+\"[^>]*>[\\s\\S]{0,400}?<img",
                RegexOption.IGNORE_CASE).containsMatchIn(html)

        /** Подпись для экрана: причина, а не ноль. */
        fun text(markup: MangaMarkup): String = when (markup) {
            SERVER -> "страница отдана сервером"
            JS_ONLY -> "сайт собирает страницу в браузере"
            ANTI_BOT -> "сайт закрылся проверкой браузера"
            EMPTY -> "ответ пустой"
        }
    }
}
