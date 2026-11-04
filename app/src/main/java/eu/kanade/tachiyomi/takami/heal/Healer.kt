package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.CheckResult
import eu.kanade.tachiyomi.takami.Forge
import eu.kanade.tachiyomi.takami.ParseFailures
import eu.kanade.tachiyomi.takami.SelfCheck
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.takami.spec.SpecJournalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Итог одной попытки лечения. */
data class HealResult(
    /** Стало ли лучше и правка осталась. */
    val healed: Boolean,
    val part: SpecPart,
    /** Короткий код причины: пусто при успехе. */
    val reason: String,
    /** Проверки до правки и после. */
    val before: List<CheckResult>,
    val after: List<CheckResult>,
)

/**
 * Оркестрация лечения: правило → самопроверка → план → свежая сборка →
 * слияние → вердикт → сохранение или отказ.
 *
 * Лечение никогда не запускается само: его зовёт экран «Починить» или прогон.
 * Сеть — та же, что у всего приложения: через общий [Forge] и [SelfCheck],
 * без своих клиентов и без повышения бюджета.
 */
class Healer(
    private val forge: Forge = Forge(),
    private val selfCheck: SelfCheck = SelfCheck(),
) {

    suspend fun heal(spec: SiteSpec, trigger: String): HealResult = withContext(Dispatchers.IO) {
        runCatching { run(spec, trigger) }.getOrElse { error ->
            logcat(LogPriority.DEBUG) { "Takami heal: сбой лечения " + error.javaClass.simpleName }
            HealLog.write(
                HealLogEntry(
                    host = spec.host,
                    part = SpecPart.NONE.name,
                    decision = "error",
                    reason = "exception",
                    trigger = trigger,
                ),
            )
            HealResult(false, SpecPart.NONE, "exception", emptyList(), emptyList())
        }
    }

    private suspend fun run(spec: SiteSpec, trigger: String): HealResult {
        // Чужих триггеров нет: только явное действие пользователя или прогон.
        if (trigger != TRIGGER_USER && trigger != TRIGGER_SWEEP) {
            return HealResult(false, SpecPart.NONE, "bad_trigger", emptyList(), emptyList())
        }
        val now = System.currentTimeMillis()

        // Что именно сломалось сейчас: план строится по живой проверке, а не по памяти.
        val before = selfCheck.report(spec)
        val failed = before.checks.filterNot { it.ok }.map { it.key }
        if (failed.isEmpty()) {
            return HealResult(false, SpecPart.NONE, "nothing_broken", before.checks, emptyList())
        }
        val part = HealPlanner.plan(failed)

        // Журнал откатов плюс свежий провал: правило должно видеть, что шаг
        // сломан на самом деле, а не выдумано.
        val observed = SpecJournalEntry(
            host = spec.host,
            fromVersion = spec.version,
            toVersion = spec.version,
            checksBefore = before.checks.count { it.ok },
            checksAfter = before.checks.count { it.ok },
            failedChecks = failed,
            reason = "провал при лечении",
            at = now,
        )
        val state = HealLog.state(spec.host, now).copy(
            checksPassed = spec.checksPassed,
            historyVersions = TakamiForge.historyVersions(spec.host),
        )
        val allow = HealPolicy.allow(spec.host, part, state, TakamiForge.rollbackJournal(spec.host) + observed, now)
        if (allow is HealAllow.No) {
            HealLog.write(
                HealLogEntry(
                    host = spec.host,
                    part = part.name,
                    attempt = state.attemptsToday + 1,
                    decision = "skipped",
                    reason = allow.reason,
                    before = marks(before.checks),
                    trigger = trigger,
                    at = now,
                ),
            )
            return HealResult(false, part, allow.reason, before.checks, emptyList())
        }

        // Свежая сборка с живой страницы: сеть через общий [Forge].
        val fresh = forge.build(spec.baseUrl)
        if (fresh is ForgeResult.Failure) {
            val reason = if (fresh.blocked) "blocked" else "build_failed"
            HealLog.write(
                HealLogEntry(
                    host = spec.host,
                    part = part.name,
                    attempt = state.attemptsToday + 1,
                    decision = if (fresh.blocked) "blocked" else "failed",
                    reason = reason,
                    before = marks(before.checks),
                    trigger = trigger,
                    at = now,
                ),
            )
            ParseFailures.noteFailure(spec.host, "heal", reason, spec.version)
            return HealResult(false, part, reason, before.checks, emptyList())
        }
        val built = fresh as ForgeResult.Built

        val merged = HealMerge.merge(spec, built.spec, part)
        if (merged == spec) {
            // Новой секции нет: чинить нечем, попытку считаем провальной.
            HealLog.write(
                HealLogEntry(
                    host = spec.host,
                    part = part.name,
                    attempt = state.attemptsToday + 1,
                    decision = "failed",
                    reason = "no_alternative",
                    before = marks(before.checks),
                    trigger = trigger,
                    at = now,
                ),
            )
            ParseFailures.noteFailure(spec.host, "heal", "no_alternative", spec.version)
            return HealResult(false, part, "no_alternative", before.checks, emptyList())
        }

        val after = selfCheck.report(merged)
        if (!HealVerdict.accept(before.checks, after.checks, part)) {
            // Правку не сохраняем вовсе: текущая версия и так остаётся рабочей.
            HealLog.write(
                HealLogEntry(
                    host = spec.host,
                    part = part.name,
                    attempt = state.attemptsToday + 1,
                    decision = "rejected",
                    reason = "verdict",
                    before = marks(before.checks),
                    after = marks(after.checks),
                    trigger = trigger,
                    at = now,
                ),
            )
            ParseFailures.noteFailure(spec.host, "heal", "verdict", spec.version)
            return HealResult(false, part, "rejected", before.checks, after.checks)
        }

        val failedAfter = after.checks.filterNot { it.ok }.map { it.key }
        TakamiForge.save(merged.copy(checksPassed = after.checks.count { it.ok }), failedAfter)
        HealLog.write(
            HealLogEntry(
                host = spec.host,
                part = part.name,
                attempt = state.attemptsToday + 1,
                decision = "accepted",
                reason = "",
                before = marks(before.checks),
                after = marks(after.checks),
                trigger = trigger,
                at = now,
            ),
        )
        return HealResult(true, part, "", before.checks, after.checks)
    }

    /** Плюс-минус по шагам: «catalog+;details-;episodes-». */
    private fun marks(checks: List<CheckResult>): String = checks.joinToString(";") { it.key + mark(it.ok) }

    private fun mark(ok: Boolean): String = if (ok) "+" else "-"

    private companion object {
        const val TRIGGER_USER = "user"
        const val TRIGGER_SWEEP = "sweep"
    }
}
