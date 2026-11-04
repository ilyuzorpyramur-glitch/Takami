package eu.kanade.tachiyomi.ui.takami

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.takami.Sweep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@Immutable
sealed interface SweepState {
    /** Прогон ещё не начат; [total] — сколько строк в списке. */
    @Immutable
    data class Idle(val total: Int, val done: Int) : SweepState

    @Immutable
    data class Running(val done: Int, val total: Int, val host: String) : SweepState

    @Immutable
    data class Finished(val ok: Int, val failed: Int, val file: String) : SweepState

    /** Списка нет или он пуст: прогонять нечего. */
    @Immutable
    data class NoList(val file: String) : SweepState
}

/**
 * Прогон списка сайтов на устройстве.
 *
  * Список читается из файла в filesDir — его кладёт тот, кто
  * ведёт проверку.
 * Приложение только проходит по строкам и пишет итог рядом, в JSONL.
 */
class SweepScreenModel(
    private val sweep: Sweep = Sweep(),
) : ScreenModel {

    private val _state = MutableStateFlow<SweepState>(SweepState.NoList(Sweep.LIST))
    val state: StateFlow<SweepState> = _state.asStateFlow()

    /**
     * Прогон уже идёт. Флаг ставится до запуска корутины,
     * иначе второе нажатие
      * успевает проскочить проверку состояния и прогон
      * идёт двумя копиями. */
    private var running = false

    fun refresh(dir: File) {
        val list = File(dir, Sweep.LIST)
        val sites = readSites(list)
        val done = readDone(File(dir, Sweep.FILE))
        _state.value = if (sites.isEmpty()) {
            SweepState.NoList(list.absolutePath)
        } else {
            SweepState.Idle(total = sites.size, done = done)
        }
    }

    fun start(dir: File) {
        if (running) return
        val list = File(dir, Sweep.LIST)
        val sites = readSites(list)
        if (sites.isEmpty()) {
            _state.value = SweepState.NoList(list.absolutePath)
            return
        }
        val out = File(dir, Sweep.FILE)
        val total = sites.size
        running = true
        _state.value = SweepState.Running(done = readDone(out), total = total, host = "")
        screenModelScope.launch {
            try {
                var ok = 0
                var failed = 0
                // Итог известен только когда сайт пройден
                // целиком: считаем по ходу.
                val outcomes = sweep.run(
                    sites = sites,
                    out = out,
                    onProgress = { done, all, outcome ->
                        if (outcome.ok) ok++ else failed++
                        _state.value = SweepState.Running(done = done, total = all, host = outcome.host)
                    },
                )
                // Продолжение после прерывания приносит только
                // новые строки: досчитываем
                // по файлу, иначе счётчик покажет меньше, чем
                // реально пройдено.
                if (outcomes.isEmpty()) {
                    val summary = summarize(out)
                    ok = summary.first
                    failed = summary.second
                }
                _state.value = SweepState.Finished(ok = ok, failed = failed, file = out.absolutePath)
            } finally {
                running = false
            }
        }
    }

    private fun readSites(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun readDone(file: File): Int {
        if (!file.exists()) return 0
        return file.readLines().count { it.trim().startsWith("{") }
    }

    private fun summarize(file: File): Pair<Int, Int> {
        if (!file.exists()) return 0 to 0
        var ok = 0
        var failed = 0
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return@forEachLine
            if (trimmed.contains("\"ok\":true")) ok++ else failed++
        }
        return ok to failed
    }
}
