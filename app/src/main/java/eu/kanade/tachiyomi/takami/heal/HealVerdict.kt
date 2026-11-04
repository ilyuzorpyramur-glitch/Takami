package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.CheckResult

/**
 * Вердикт по правке: строже, чем общий счёт проверок.
 *
 * Общий счёт мог бы принять правку, при которой сломанный шаг остался сломанным,
 * а другой починился. Лечение принимает правку, только если шаг, который чинили,
 * починился, и ни один другой шаг не стал хуже.
 */
object HealVerdict {

    fun accept(before: List<CheckResult>, after: List<CheckResult>, part: SpecPart): Boolean {
        if (after.isEmpty()) return false
        val target = HealPlanner.stepOf(part) ?: return false
        // Шаг, который лечили, обязан починиться: равенство счёта при сломанном
        // шаге — это и есть дыра в общем счёте.
        if (after.firstOrNull { it.key == target }?.ok != true) return false
        return before.filter { it.ok }.all { step ->
            after.firstOrNull { it.key == step.key }?.ok == true
        }
    }
}
