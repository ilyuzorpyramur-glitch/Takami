package eu.kanade.tachiyomi.ui.takami

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.takami.novel.NovelForgeSources
import eu.kanade.tachiyomi.takami.novel.NovelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Итог мастера: занят, готовая спека или причина отказа. */
data class NovelForgeState(
    val busy: Boolean = false,
    val done: NovelSpec? = null,
    val error: String? = null,
)

class NovelForgeScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(NovelForgeState())
    val state: StateFlow<NovelForgeState> = _state.asStateFlow()

    val specs: StateFlow<List<NovelSpec>> = NovelForgeSources.specs

    /** Сборка по ссылке. Отказ показывается как есть. */
    fun build(link: String) {
        val url = link.trim()
        if (url.isEmpty() || _state.value.busy) return
        _state.value = NovelForgeState(busy = true)
        scope.launch {
            val made = NovelForgeSources.install(url)
            val fail = NovelForgeSources.failure.value
            val text = if (fail == null) null else fail.reason + ": " + fail.message
            _state.value = NovelForgeState(done = made, error = text)
        }
    }

    fun remove(host: String) {
        scope.launch { NovelForgeSources.remove(host) }
    }
}
