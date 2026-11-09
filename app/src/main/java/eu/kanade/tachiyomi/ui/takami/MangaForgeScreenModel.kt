package eu.kanade.tachiyomi.ui.takami

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.takami.manga.MangaForgeSources
import eu.kanade.tachiyomi.takami.manga.MangaSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Итог мастера: занят, готовая спека или причина отказа. */
data class MangaForgeState(
    val busy: Boolean = false,
    val done: MangaSpec? = null,
    val error: String? = null,
)

class MangaForgeScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(MangaForgeState())
    val state: StateFlow<MangaForgeState> = _state.asStateFlow()

    val specs: StateFlow<List<MangaSpec>> = MangaForgeSources.specs

    /** Сборка по ссылке. Отказ показывается как есть. */
    fun build(link: String) {
        val url = link.trim()
        if (url.isEmpty() || _state.value.busy) return
        _state.value = MangaForgeState(busy = true)
        scope.launch {
            val made = MangaForgeSources.install(url)
            val fail = MangaForgeSources.failure.value
            val text = if (fail == null) null else fail.reason + ": " + fail.message
            _state.value = MangaForgeState(done = made, error = text)
        }
    }

    fun remove(host: String) {
        scope.launch { MangaForgeSources.remove(host) }
    }
}
