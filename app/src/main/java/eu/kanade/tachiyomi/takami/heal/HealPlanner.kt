package eu.kanade.tachiyomi.takami.heal

/**
 * Часть спеки, которую чиним за одну попытку.
 *
 * Видео не входит: ось видео принадлежит другому этапу и здесь не лечится.
 */
enum class SpecPart {
    CATALOG,
    CARD,
    EPISODES,
    PAGINATION,
    NONE,
}

/** План лечения: по провалившимся шагам решает, что чинить. */
object HealPlanner {

    /**
     * Ключи шагов — ровно те, что отдаёт самопроверка: `catalog`, `pagination`,
     * `details`, `episodes`, `video`. Своего словаря шагов здесь нет.
     *
     * Старшинство: каталог, карточка, серии, пагинация. Одна попытка лечит одну часть.
     */
    fun plan(failed: List<String>): SpecPart {
        val steps = failed.toSet()
        return when {
            steps.isEmpty() -> SpecPart.NONE
            "catalog" in steps -> SpecPart.CATALOG
            "details" in steps -> SpecPart.CARD
            "episodes" in steps -> SpecPart.EPISODES
            "pagination" in steps -> SpecPart.PAGINATION
            // Видео и всё прочее: на этом этапе не лечится.
            else -> SpecPart.NONE
        }
    }

    /** Шаг самопроверки, соответствующий части; у NONE шага нет. */
    fun stepOf(part: SpecPart): String? = when (part) {
        SpecPart.CATALOG -> "catalog"
        SpecPart.CARD -> "details"
        SpecPart.EPISODES -> "episodes"
        SpecPart.PAGINATION -> "pagination"
        SpecPart.NONE -> null
    }
}
