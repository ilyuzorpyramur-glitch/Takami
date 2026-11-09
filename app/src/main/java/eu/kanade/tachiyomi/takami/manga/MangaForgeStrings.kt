package eu.kanade.tachiyomi.takami.manga

/**
 * Тексты мастера манга-источников.
 *
 * Вынесены из Compose, чтобы экран не держал кириллицу в разметке и
 * чтобы гейт мог проверять подписи отдельно от вёрстки.
 */
object MangaForgeStrings {
    const val TITLE = "Манга-источники"
    const val ADD_BY_LINK = "Добавить по ссылке"
    const val HINT = "Ссылка на каталог манги"
    const val BUILD = "Собрать источник"
    const val BUILDING = "Проверяю сайт…"
    const val EMPTY = "Своих источников пока нет"
    const val DONE = "Источник собран и добавлен"
    const val REMOVE = "Удалить"
    const val BACK = "Назад"
}
