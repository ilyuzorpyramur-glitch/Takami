package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.spec.SpecJournalEntry

/**
 * Состояние лечения источника: сколько попыток было и когда был последний провал.
 *
 * Поля `checksPassed`, `historyVersions` и `blockedAt` заполняет тот, кто зовёт
 * правило: журнал лечения про них не знает, а правилу без них не обойтись
 * (источник, который ни разу не работал, лечить нечего).
 */
data class HealState(
    val attemptsToday: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastFailedAt: Long = 0L,
    val failedInRow: Int = 0,
    /** Сколько проверок проходит текущая спека; ноль вместе с пустой историей — источник не работал. */
    val checksPassed: Int = 0,
    /** Сколько рабочих версий в истории источника. */
    val historyVersions: Int = 0,
    /** Когда последняя попытка упёрлась в защиту сайта: сутки после неё лечение закрыто. */
    val blockedAt: Long = 0L,
)

/** Разрешение на лечение: да либо нет с причиной и сроком ожидания. */
sealed interface HealAllow {
    data object Yes : HealAllow
    data class No(val reason: String, val retryAfterMs: Long = 0L) : HealAllow
}

/**
 * Можно ли лечить источник прямо сейчас.
 *
 * Функция чистая: только состояние, время и журнал — ни сети, ни спеки.
 * Каждый запрет — отдельная причина, у каждой свой тест.
 */
object HealPolicy {

    /** Попыток в сутки на источник. */
    const val MAX_ATTEMPTS_PER_DAY = 2

    /** Пауза после провала. */
    const val COOLDOWN_MS = 6L * 60 * 60 * 1000

    /** Пауза после двух провалов подряд и после упора в защиту сайта. */
    const val HARD_COOLDOWN_MS = 24L * 60 * 60 * 1000

    /** Источник только для чтения: домен и ключ спеки. */
    const val READ_ONLY_DOMAIN = "dreamerscast.com"
    const val READ_ONLY_KEY = "kodikdreamcast"

    fun allow(
        host: String,
        part: SpecPart,
        state: HealState,
        journal: List<SpecJournalEntry>,
        now: Long,
    ): HealAllow {
        val domain = host.trim().removePrefix("www.").lowercase()
        if (domain == READ_ONLY_DOMAIN || READ_ONLY_KEY in domain) return HealAllow.No("read_only")
        if (part == SpecPart.NONE) return HealAllow.No("video_owned")
        if (state.historyVersions == 0 && state.checksPassed == 0) return HealAllow.No("never_worked")
        if (state.blockedAt > 0 && now - state.blockedAt < HARD_COOLDOWN_MS) {
            return HealAllow.No("blocked", HARD_COOLDOWN_MS - (now - state.blockedAt))
        }
        if (state.attemptsToday >= MAX_ATTEMPTS_PER_DAY) return HealAllow.No("attempts_today")
        val sinceFail = now - state.lastFailedAt
        if (state.failedInRow >= 2 && state.lastFailedAt > 0 && sinceFail < HARD_COOLDOWN_MS) {
            return HealAllow.No("cool_down_hard", HARD_COOLDOWN_MS - sinceFail)
        }
        if (state.lastFailedAt > 0 && sinceFail < COOLDOWN_MS) {
            return HealAllow.No("cooldown", COOLDOWN_MS - sinceFail)
        }
        val step = HealPlanner.stepOf(part)
        if (step == null || journal.none { step in it.failedChecks }) {
            return HealAllow.No("no_breakage")
        }
        return HealAllow.Yes
    }
}
