package eu.kanade.tachiyomi.takami.novel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import eu.kanade.domain.source.service.SourcePreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Реестр ранобэ-спек на устройстве.
 *
 * Хранит спеки в filesDir, отдаёт источники менеджеру и принимает
 * новые по ссылке. Ничего не отправляет наружу.
 */
object NovelForgeSources {

    private const val DIR = "takami-novel"

    private var store: NovelStore? = null
    private val engine = NovelEngine()
    private val forge = NovelForge(engine)

    private val _specs = MutableStateFlow<List<NovelSpec>>(emptyList())

    /** Спеки, которые сейчас установлены. */
    val specs: StateFlow<List<NovelSpec>> = _specs.asStateFlow()

    private val _ready = MutableStateFlow<NovelSpec?>(null)

    /** Готовая спека последней удачной сборки. */
    val ready: StateFlow<NovelSpec?> = _ready.asStateFlow()

    private val _failure = MutableStateFlow<NovelOutcome.Failure?>(null)

    /** Причина отказа последней сборки. */
    val failure: StateFlow<NovelOutcome.Failure?> = _failure.asStateFlow()

    fun init(context: Context) {
        if (store != null) return
        val dir = File(context.filesDir, DIR)
        dir.mkdirs()
        store = NovelStore(dir)
        _specs.value = NovelStore(dir).list()
        logcat(LogPriority.DEBUG) { "NovelForge: спек " + _specs.value.size }
    }

    fun sources(): List<VirtualNovelSource> =
        _specs.value.map { VirtualNovelSource(it, engine) }

    fun source(host: String): VirtualNovelSource? = _specs.value
        .firstOrNull { it.host == host }
        ?.let { VirtualNovelSource(it, engine) }

    suspend fun install(rawUrl: String): NovelSpec? = withContext(Dispatchers.IO) {
        when (val built = forge.build(rawUrl)) {
            is NovelOutcome.Built -> {
                save(built.spec)
                _ready.value = built.spec
                _failure.value = null
                built.spec
            }
            is NovelOutcome.Failure -> {
                _ready.value = null
                _failure.value = built
                null
            }
        }
    }

    suspend fun save(spec: NovelSpec): NovelSpec = withContext(Dispatchers.IO) {
        val target = store
        if (target == null) {
            logcat(LogPriority.ERROR) { "NovelForge: реестр не открыт" }
            return@withContext spec
        }
        val stamped = spec.copy(updatedAt = System.currentTimeMillis())
        runCatching { target.save(stamped) }
            .onFailure { logcat(LogPriority.ERROR, it) { "NovelForge: запись" } }
        _specs.value = target.list()
        enableLanguage(stamped.lang)
        stamped
    }

    /**
     * Включить язык источника в настройках.
     *
     * Без этого собранный источник существует, но не показывается в
     * списке: приложение фильтрует источники по включённым языкам.
     */
    private fun enableLanguage(lang: String) {
        if (lang.isBlank()) return
        runCatching {
            val preferences = Injekt.get<SourcePreferences>()
            val current = preferences.enabledLanguages().get()
            if ("all" in current || lang in current) return
            preferences.enabledLanguages().set(current + lang)
        }.onFailure { logcat(LogPriority.ERROR, it) { "Язык источника: " + lang } }
    }

    suspend fun remove(host: String): Boolean = withContext(Dispatchers.IO) {
        val removed = store?.delete(host) == true
        _specs.value = store?.list().orEmpty()
        removed
    }
}
