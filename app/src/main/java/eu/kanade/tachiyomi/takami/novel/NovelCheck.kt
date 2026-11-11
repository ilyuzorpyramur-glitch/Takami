package eu.kanade.tachiyomi.takami.novel

/**
 * Итог проверки ранобэ-источника: причина вместо нуля.
 */
enum class NovelCheck(val key: String) {
    OK("ok"),
    NO_CATALOG("no_catalog"),
    NO_CHAPTERS("no_chapters"),
    NO_TEXT("no_text"),
    BLOCKED("blocked"),
    JS_ONLY("js_only"),
    ANTI_BOT("anti_bot"),
    FAILED("failed"),
    ;

    companion object {
        fun of(
            catalog: Int,
            chapters: Int,
            textChars: Int,
            blocked: Boolean = false,
            markup: NovelMarkup = NovelMarkup.SERVER,
        ): NovelCheck = when {
            blocked -> BLOCKED
            markup == NovelMarkup.JS_ONLY -> JS_ONLY
            markup == NovelMarkup.ANTI_BOT -> ANTI_BOT
            catalog <= 0 -> NO_CATALOG
            chapters <= 0 -> NO_CHAPTERS
            textChars <= 0 -> NO_TEXT
            else -> OK
        }

        fun text(check: NovelCheck, catalog: Int, chapters: Int, chars: Int): String =
            when (check) {
                OK -> "каталог: " + catalog + ", глав: " + chapters + ", букв: " + chars
                NO_CATALOG -> "каталог пуст: карточки не найдены"
                NO_CHAPTERS -> "главы не найдены"
                NO_TEXT -> "текст главы не найден"
                BLOCKED -> "сайт не отдаёт страницу"
                JS_ONLY -> "сайт собирает страницу в браузере"
                ANTI_BOT -> "сайт закрылся проверкой браузера"
                FAILED -> "проверка не удалась"
            }
    }
}
