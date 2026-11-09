package eu.kanade.tachiyomi.takami.manga

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Реестр манга-спек на устройстве.
 *
 * Хранит спеки в filesDir, отдаёт готовые источники менеджеру и
 * принимает новые по ссылке. Ничего не отправляет наружу.
 */
object MangaForgeSources {

    private const val DIR = "takami-manga"

    private var store: MangaStore? = null
    private val engine = MangaEngine()
    private val forge = MangaForge(engine)

    private val _specs = MutableStateFlow<List<MangaSpec>>(emptyList())
    val specs: StateFlow<List<MangaSpec>> = _specs.asStateFlow()

    /* Готовая спека последней удачной
    /* сборки: экран показывает итог. */
    private val _ready = MutableStateFlow<MangaSpec?>(null)
    val ready: StateFlow<MangaSpec?> = _ready.asStateFlow()

    /* Причина отказа последней сборки:
    /* экран показывает её текстом. */
    private val _failure = MutableStateFlow<MangaForge.Failure?>(null)
    val failure: StateFlow<MangaForge.Failure?> = _failure.asStateFlow()

    fun init(context: Context) {
        if (store != null) return
        val dir = File(context.filesDir, DIR)
        store = MangaStore(dir)
        _specs.value = MangaStore(dir).list()
        logcat(LogPriority.DEBUG) { "MangaForge: спек " + _specs.value.size }
    }

    /** Источники для менеджера: по одному на спеку. */
    fun sources(): List<VirtualMangaSource> = _specs.value.map { VirtualMangaSource(it, engine) }

    fun source(host: String): VirtualMangaSource? = _specs.value
        .firstOrNull { it.host == host }
        ?.let { VirtualMangaSource(it, engine) }

    /**
     * Сборка по ссылке и установка.
     *
     * Отказ не прячется: экран покажет причину (`js_only`, `anti_bot`,
     * `no_catalog`), а реестр останется как был.
     */
    suspend fun install(rawUrl: String): MangaSpec? = withContext(Dispatchers.IO) {
        when (val built = forge.build(rawUrl)) {
            is MangaForge.Built -> {
                save(built.spec)
                _ready.value = built.spec
                _failure.value = null
                built.spec
            }
            is MangaForge.Failure -> {
                _ready.value = null
                _failure.value = built
                null
            }
        }
    }

    /** Запись спеки на диск и обновление списка источников. */
    suspend fun save(spec: MangaSpec): MangaSpec = withContext(Dispatchers.IO) {
        val stamped = spec.copy(updatedAt = System.currentTimeMillis())
        val target = store ?: error("MangaForgeSources.init не вызван")
        runCatching { target.save(stamped) }
            .onFailure { logcat(LogPriority.ERROR, it) { "MangaForge: запись " + spec.host } }
        _specs.value = target.list()
        stamped
    }

    suspend fun remove(host: String): Boolean = withContext(Dispatchers.IO) {
        val removed = store?.delete(host) == true
        _specs.value = store?.list().orEmpty()
        removed
    }

    fun resetMessages() {
        _ready.value = null
        _failure.value = null
    }
}
