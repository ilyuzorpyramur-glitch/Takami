package eu.kanade.tachiyomi.ui.takami

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.takami.CheckResult
import eu.kanade.tachiyomi.takami.Forge
import eu.kanade.tachiyomi.takami.ForgeResult
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@Immutable
sealed interface ForgeState {
    @Immutable
    data object Idle : ForgeState

    @Immutable
    data class Running(val url: String) : ForgeState

    @Immutable
    data class Failed(val url: String, val reason: String) : ForgeState

    @Immutable
    data class Ready(
        val url: String,
        val spec: SiteSpec,
        val checks: List<CheckResult>,
    ) : ForgeState
}

sealed interface ForgeEvent {
    data class Saved(val name: String) : ForgeEvent
    data class Removed(val name: String) : ForgeEvent
}

class TakamiForgeScreenModel(
    private val forge: Forge = Forge(),
) : ScreenModel {

    private val _state = MutableStateFlow<ForgeState>(ForgeState.Idle)
    val state: StateFlow<ForgeState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(TakamiForge.specs.value)
    val saved: StateFlow<List<SiteSpec>> = _saved.asStateFlow()

    private val _events = Channel<ForgeEvent>(Channel.BUFFERED)
    val events: Flow<ForgeEvent> = _events.receiveAsFlow()

    fun build(url: String) {
        if (url.isBlank()) return
        if (_state.value is ForgeState.Running) return

        _state.value = ForgeState.Running(url)
        screenModelScope.launch {
            val result = runCatching { forge.build(url) }
                .getOrElse { ForgeResult.Failure(it.message ?: it.toString()) }

            _state.value = when (result) {
                is ForgeResult.Built -> ForgeState.Ready(url, result.spec, result.checks)
                is ForgeResult.Failure -> ForgeState.Failed(url, result.reason)
            }
        }
    }

    fun reset() {
        _state.value = ForgeState.Idle
    }

    fun save() {
        val ready = _state.value as? ForgeState.Ready ?: return
        screenModelScope.launch {
            val saved = TakamiForge.save(ready.spec)
            _saved.value = TakamiForge.specs.value
            _events.send(ForgeEvent.Saved(saved.name))
            _state.value = ForgeState.Idle
        }
    }

    fun remove(spec: SiteSpec) {
        screenModelScope.launch {
            TakamiForge.remove(spec.host)
            _saved.value = TakamiForge.specs.value
            _events.send(ForgeEvent.Removed(spec.name))
        }
    }
}
