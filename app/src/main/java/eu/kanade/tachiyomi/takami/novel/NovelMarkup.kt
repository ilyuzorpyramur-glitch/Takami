package eu.kanade.tachiyomi.takami.novel

/**
 * Что отдал сайт: разметку или пустой каркас.
 *
 * Ранобэ-сайты часто собирают страницу в браузере.
 * Разбирать такой
 * ответ бессмысленно, и говорить «каталог пуст» нельзя: причины
 * разные, лечатся по-разному.
 */
enum class NovelMarkup(val key: String) {
    SERVER("server"),
    JS_ONLY("js_only"),
    ANTI_BOT("anti_bot"),
    EMPTY("empty"),
    ;

    companion object {
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

        private val ANTI_BOT_MARKERS = listOf(
            "just a moment",
            "cf-browser-verification",
            "checking your browser",
            "ddos-guard",
            "captcha",
            "attention required",
        )

        fun detect(html: String): NovelMarkup {
            val text = html.trim()
            if (text.isEmpty() || text.length < 200) return EMPTY
            val lower = text.lowercase()
            if (ANTI_BOT_MARKERS.any { lower.contains(it) }) return ANTI_BOT
            if (JS_MARKERS.any { lower.contains(it.lowercase()) }) return JS_ONLY
            if (SPA_ROOT.containsMatchIn(text) && !hasCards(text)) return JS_ONLY
            return SERVER
        }

        /**
        * Карточка: ссылка и картинка в одном блоке
        * недалеко друг от друга.
         */
        private fun hasCards(html: String): Boolean =
            Regex(
                "<(a|div)[^>]*href=\"[^\"]+\"[^>]*>[\\s\\S]{0,400}?<img",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(html)

        fun text(markup: NovelMarkup): String = when (markup) {
            SERVER -> "страница отдана сервером"
            JS_ONLY -> "сайт собирает страницу в браузере"
            ANTI_BOT -> "сайт закрылся проверкой браузера"
            EMPTY -> "ответ пустой"
        }
    }
}
