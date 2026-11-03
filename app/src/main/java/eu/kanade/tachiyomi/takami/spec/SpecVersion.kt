package eu.kanade.tachiyomi.takami.spec

import eu.kanade.tachiyomi.takami.SiteSpec

/**
 * Текущая версия схемы спеки.
 *
 * Растёт при каждом изменении набора полей. Файл, записанный сборкой без поля
 * версии, считается первой версией: его поднимает миграция, а не разбор.
 */
const val SPEC_VERSION = 2

/** Версия, которой помечаются спеки, записанные до появления поля версии. */
const val SPEC_VERSION_LEGACY = 1

/**
 * Отметка записи: версия схемы, число пройденных проверок и время сохранения.
 *
 * Ставится при каждой записи, чтобы историю можно было сравнить: правка
 * считается удачной, только если проверок стало не меньше, чем было.
 */
fun SiteSpec.stamped(
    checksPassed: Int = this.checksPassed,
    now: Long = System.currentTimeMillis(),
): SiteSpec = copy(
    version = SPEC_VERSION,
    checksPassed = checksPassed,
    updatedAt = now,
)
