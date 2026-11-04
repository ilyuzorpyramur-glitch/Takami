package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.SiteSpec

/**
 * Правка одной части спеки: свежая сборка подставляет только выбранную секцию.
 *
 * Функция чистая и идемпотентная. То, что лечению не принадлежит, не меняется
 * никогда: имя, язык, адрес, иконки, фильтры, видео, озвучки, поиск, версия
 * и счёт проверок остаются от текущей спеки.
 */
object HealMerge {

    fun merge(current: SiteSpec, fresh: SiteSpec, part: SpecPart): SiteSpec = when (part) {
        SpecPart.CATALOG -> replaceCatalog(current, fresh)
        SpecPart.PAGINATION -> replacePagination(current, fresh)
        SpecPart.CARD -> replaceCard(current, fresh)
        SpecPart.EPISODES -> replaceEpisodes(current, fresh)
        SpecPart.NONE -> current
    }

    /** Каталог целиком: карточки и страницы. Поиск не часть лечения — остаётся прежним. */
    private fun replaceCatalog(current: SiteSpec, fresh: SiteSpec): SiteSpec {
        if (fresh.catalog.itemSelector.isBlank()) return current
        val catalog = fresh.catalog.copy(search = current.catalog.search)
        return current.copy(catalog = catalog)
    }

    /** Только пагинация: селекторы карточек не трогаем. */
    private fun replacePagination(current: SiteSpec, fresh: SiteSpec): SiteSpec {
        if (fresh.catalog.pagination.template.isBlank()) return current
        return current.copy(catalog = current.catalog.copy(pagination = fresh.catalog.pagination))
    }

    /** Карточка тайтла. */
    private fun replaceCard(current: SiteSpec, fresh: SiteSpec): SiteSpec {
        if (fresh.details.title.isBlank()) return current
        return current.copy(details = fresh.details)
    }

    /** Серии. */
    private fun replaceEpisodes(current: SiteSpec, fresh: SiteSpec): SiteSpec {
        if (fresh.episodes.itemSelector.isBlank()) return current
        return current.copy(episodes = fresh.episodes)
    }
}
