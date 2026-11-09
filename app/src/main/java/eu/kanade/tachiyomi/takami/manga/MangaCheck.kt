package eu.kanade.tachiyomi.takami.manga

/**
 * Итог проверки манга-источника: отказ с причиной вместо нуля.
 *
 * Ноль без объяснения — худший ответ: непонятно, сайт закрылся,
 * вёрстка сменилась или ссылка просто неверная.
 */
enum class MangaCheck(val key: String) {
    OK("ok"),
    NO_CATALOG("no_catalog"),
    NO_CHAPTERS("no_chapters"),
    NO_PAGES("no_pages"),
    BLOCKED("blocked"),
    /** Сайт собирает страницу в браузере: селекторами её не прочитать. */
    JS_ONLY("js_only"),
    /** Сайт отдал проверку браузера вместо страницы. */
    ANTI_BOT("anti_bot"),
    FAILED("failed"),
    ;

    companion object {
        /** Итог по трём шагам: каталог, главы, страницы. */
        fun of(
            catalog: Int,
            chapters: Int,
            pages: Int,
            blocked: Boolean = false,
            markup: MangaMarkup = MangaMarkup.SERVER,
        ): MangaCheck = when {
            blocked -> BLOCKED
            markup == MangaMarkup.JS_ONLY -> JS_ONLY
            markup == MangaMarkup.ANTI_BOT -> ANTI_BOT
            catalog <= 0 -> NO_CATALOG
            chapters <= 0 -> NO_CHAPTERS
            pages <= 0 -> NO_PAGES
            else -> OK
        }

        /** Подпись для экрана: коротко и без выдуманных чисел. */
        fun text(check: MangaCheck, catalog: Int, chapters: Int, pages: Int): String = when (check) {
            OK -> "каталог: " + catalog + ", глав: " + chapters + ", страниц: " + pages
            NO_CATALOG -> "каталог пуст: карточки не найдены"
            NO_CHAPTERS -> "главы не найдены"
            NO_PAGES -> "страницы главы не найдены"
            BLOCKED -> "сайт не отдаёт страницу"
            JS_ONLY -> "сайт собирает страницу в браузере"
            ANTI_BOT -> "сайт закрылся проверкой браузера"
            FAILED -> "проверка не удалась"
        }
    }
}
