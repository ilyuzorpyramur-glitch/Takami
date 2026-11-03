package eu.kanade.tachiyomi.takami.fetch

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Состояние предохранителя одного домена. */
enum class BreakerState {
    /** Сайт отвечает: запросы идут как обычно. */
    CLOSED,

    /** Сайт в паузе: запросы не отправляются. */
    OPEN,

    /** Пауза кончилась: разрешён ровно один пробный запрос. */
    HALF_OPEN,
}

/** Снимок предохранителя для экрана диагностики. */
data class BreakerInfo(
    val host: String,
    val state: BreakerState,
    val failures: Int,
    val pauseLeftMs: Long,
    val lastError: String?,
)

/**
 * Предохранитель по домену.
 *
  * Три ошибки подряд означают, что сайт лежит или нас не пускает:
  * продолжать
  * стучаться бессмысленно и невежливо. Домен уходит в паузу на
  * пятнадцать минут,
  * после чего разрешается ровно один пробный запрос — чтобы толпа
  * вернувшихся
 * пользователей не обрушилась на только что оживший сайт.
 *
  * Состояние каждого домена отдельное и лежит на диске:
  * перезапуск приложения не
 * должен обнулять паузу, иначе она ничего не значит.
 */
class CircuitBreaker(
    private val file: File? = null,
    private val clock: FetchClock = SystemFetchClock,
) {
    private val hosts = ConcurrentHashMap<String, HostState>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * Разрешён ли запрос к домену.
     *
      * В паузе — нет. После паузы — да, но только одному: он и есть
      * пробный.
     */
    fun allow(host: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val state = hosts.getOrPut(host) { HostState() }
        val now = clock.now()
        when {
            state.openUntil <= 0L -> true
            now < state.openUntil -> false
            state.probing -> false
            else -> {
                state.probing = true
                true
            }
        }
    }

    /** Сайт ответил: счётчик сбрасывается, пауза закрывается. */
    fun onSuccess(host: String) {
        synchronized(lock) {
            val state = hosts[host] ?: return@synchronized
            state.failures = 0
            state.openUntil = 0L
            state.probing = false
            state.lastError = null
            save()
        }
    }

    /**
     * Сайт не ответил или ответил ошибкой.
     *
      * Неудачный пробный запрос сразу возвращает паузу: ждать ещё
      * три ошибки,
      * чтобы снова отступить, значит трижды стучаться в лежащий
      * сайт.
     */
    fun onFailure(host: String, error: String?) {
        synchronized(lock) {
            val state = hosts.getOrPut(host) { HostState() }
            val wasOpen = state.openUntil > 0L
            state.probing = false
            state.lastError = error
            state.failures = if (wasOpen) FetchLimits.BREAKER_FAILURES else state.failures + 1
            if (state.failures >= FetchLimits.BREAKER_FAILURES) {
                state.openUntil = clock.now() + FetchLimits.BREAKER_PAUSE_MS
            }
            save()
        }
    }

    /** Текущее состояние домена для диагностики. */
    fun state(host: String): BreakerInfo = synchronized(lock) {
        ensureLoaded()
        val state = hosts[host] ?: HostState()
        val now = clock.now()
        val left = (state.openUntil - now).coerceAtLeast(0L)
        val current = when {
            state.openUntil <= 0L -> BreakerState.CLOSED
            left > 0L -> BreakerState.OPEN
            else -> BreakerState.HALF_OPEN
        }
        BreakerInfo(host, current, state.failures, left, state.lastError)
    }

    /** Все домены, о которых знает предохранитель. */
    fun hosts(): List<String> = synchronized(lock) {
        ensureLoaded()
        hosts.keys.sorted()
    }

    /** Полный сброс: нужен для отладки и тестов. */
    fun reset() {
        synchronized(lock) {
            hosts.clear()
            loaded = true
            save()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val store = file
        if (store == null) {
            loaded = true
            return
        }
        runCatching { Json.decodeFromString<BreakerFile>(store.readText()) }
            .getOrNull()
            ?.hosts
            ?.forEach { (host, saved) ->
                hosts[host] = HostState(
                    failures = saved.failures,
                    openUntil = saved.openUntil,
                    lastError = saved.lastError,
                )
            }
        loaded = true
    }

    private fun save() {
        val store = file ?: return
        runCatching {
            store.parentFile?.mkdirs()
            val snapshot = hosts.mapValues { (_, state) ->
                SavedState(state.failures, state.openUntil, state.lastError)
            }
            val text = Json.encodeToString(BreakerFile(snapshot))
            val tmp = File(store.parentFile, store.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(store)) {
                store.writeText(text)
                tmp.delete()
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Takami: не сохранил состояние предохранителя" } }
    }

    private class HostState(
        var failures: Int = 0,
        var openUntil: Long = 0L,
        var lastError: String? = null,
        var probing: Boolean = false,
    )

    @Serializable
    private data class SavedState(
        val failures: Int = 0,
        val openUntil: Long = 0L,
        val lastError: String? = null,
    )

    @Serializable
    private data class BreakerFile(
        val hosts: Map<String, SavedState> = emptyMap(),
    )
}
