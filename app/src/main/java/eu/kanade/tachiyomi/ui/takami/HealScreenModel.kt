package eu.kanade.tachiyomi.ui.takami

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.takami.CheckResult
import eu.kanade.tachiyomi.takami.SelfCheck
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.takami.heal.HealAllow
import eu.kanade.tachiyomi.takami.heal.HealLog
import eu.kanade.tachiyomi.takami.heal.HealLogEntry
import eu.kanade.tachiyomi.takami.heal.HealPlanner
import eu.kanade.tachiyomi.takami.heal.HealPolicy
import eu.kanade.tachiyomi.takami.heal.HealResult
import eu.kanade.tachiyomi.takami.heal.HealState
import eu.kanade.tachiyomi.takami.heal.SpecPart
import eu.kanade.tachiyomi.takami.spec.SpecJournalEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Экранное состояние починки: выбор источника, проверка, работа, итог. */
sealed interface HealUiState {
    data object Pick : HealUiState

    /** Смотрим живую страницу: что именно сломалось. */
    data class Checking(val spec: SiteSpec) : HealUiState

    /** Диагноз готов: можно решать. */
    data class Ready(
        val spec: SiteSpec,
        val failed: List<CheckResult>,
        val part: SpecPart,
        val allow: HealAllow,
    ) : HealUiState

    data class Running(val spec: SiteSpec) : HealUiState
    data class Done(val spec: SiteSpec, val result: HealResult) : HealUiState
}

/**
 * Экран «Починить»: диагноз из самопроверки, разрешение из [HealPolicy],
 * лечение через явный вызов [TakamiForge.heal]. Ничего не запускается само.
 */
class HealScreenModel : ScreenModel {

    private val _state = MutableStateFlow<HealUiState>(HealUiState.Pick)
    val state: StateFlow<HealUiState> = _state.asStateFlow()

    private val selfCheck = SelfCheck()

    /** Диагноз источника: живая проверка, план, разрешение правила. */
    fun open(host: String) {
        val spec = TakamiForge.specs.value.firstOrNull { it.host == host } ?: return
        _state.value = HealUiState.Checking(spec)
        screenModelScope.launch {
            val report = runCatching { selfCheck.report(spec) }
                .getOrElse { return@launch refresh(spec, emptyList()) }
            refresh(spec, report.checks)
        }
    }

    private suspend fun refresh(spec: SiteSpec, checks: List<CheckResult>) {
        val failed = checks.filterNot { it.ok }.map { it.key }
        val part = HealPlanner.plan(failed)
        val now = System.currentTimeMillis()
        val observed = SpecJournalEntry(
            host = spec.host,
            fromVersion = spec.version,
            toVersion = spec.version,
            checksBefore = checks.count { it.ok },
            checksAfter = checks.count { it.ok },
            failedChecks = failed,
            reason = "провал при лечении",
            at = now,
        )
        val healState = HealLog.state(spec.host, now).copy(
            checksPassed = spec.checksPassed,
            historyVersions = TakamiForge.historyVersions(spec.host),
        )
        val allow = HealPolicy.allow(
            spec.host,
            part,
            healState,
            TakamiForge.rollbackJournal(spec.host) + observed,
            now,
        )
        _state.value = HealUiState.Ready(spec, checks.filterNot { it.ok }, part, allow)
    }

    /** Кнопка «Починить»: одна попытка, одна часть. */
    fun fix() {
        val ready = _state.value as? HealUiState.Ready ?: return
        _state.value = HealUiState.Running(ready.spec)
        screenModelScope.launch {
            val result = TakamiForge.heal(ready.spec.host, "user")
            _state.value = HealUiState.Done(ready.spec, result)
        }
    }

    /** Возврат к диагнозу после итога. */
    fun reopen() {
        val done = _state.value as? HealUiState.Done ?: return
        open(done.spec.host)
    }

    fun back() {
        _state.value = HealUiState.Pick
    }

    /** Последние попытки из журнала: шаг, решение, время. */
    fun journal(host: String): List<HealLogEntry> = HealLog.recent(host)

    /** Полное состояние источника для правила — экран показывает его же. */
    fun healState(host: String, now: Long): HealState = HealLog.state(host, now)
}
