package eu.kanade.tachiyomi.takami.report

import android.content.Context
import com.tadami.aurora.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID

/**
 * Единственная внешняя точка входа пакета отчётов.
 *
 * Остальной код знает только про этот объект: чтобы после калибровки удалить
 * сбор целиком, достаточно снять вызовы здесь и здесь же.
 */
object TakamiReports {

    private const val DIR = "takami"
    private const val INSTALL_FILE = "install-id"
    private const val TICK_MS = 10L * 60L * 1_000L

    @Volatile
    private var reporter: Reporter? = null

    @Volatile
    private var dir: File? = null

    /** Готовит отправку. Без адреса в сборке она просто ничего не делает. */
    fun start(context: Context) {
        if (reporter != null) return
        synchronized(this) {
            if (reporter != null) return
            val app = context.applicationContext
            val home = File(app.filesDir, DIR).apply { mkdirs() }
            dir = home
            val endpoint = BuildConfig.TELEMETRY_URL
            val active = Reporter(
                dir = home,
                endpoint = endpoint,
                appVersion = BuildConfig.VERSION_NAME,
            )
            reporter = active
            logcat(LogPriority.DEBUG) {
                "Takami: сбор отчётов — " + if (endpoint.isBlank()) "адрес не задан" else "включён"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                while (true) {
                    runCatching { active.sendIfDue() }
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Сбой сетевого шага: тип ошибки и шаг, без текстов и адресов. */
    fun noteFailure(
        host: String,
        step: String,
        error: String,
        health: String,
        code: Int?,
        durationMs: Long,
        specVersion: Int,
    ) {
        val active = reporter ?: return
        val cleanHost = Sanitizer.hostOf(host)
        if (cleanHost.isBlank()) return
        runCatching {
            active.note(
                ReportEvent(
                    host = cleanHost,
                    step = Sanitizer.field(step),
                    error = Sanitizer.field(error),
                    health = Sanitizer.field(health),
                    code = code,
                    durationMs = durationMs.coerceIn(0L, MAX_DURATION_MS),
                    appVersion = Sanitizer.field(BuildConfig.VERSION_NAME),
                    specVersion = specVersion,
                    installId = installId(),
                    at = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Смена состояния источника: открылась или закрылась пауза. */
    fun noteHealth(host: String, health: String) {
        noteFailure(
            host = host,
            step = "health",
            error = "state",
            health = health,
            code = null,
            durationMs = 0L,
            specVersion = 0,
        )
    }

    /** Проверка спеки после сохранения: прошла целиком или нет. */
    fun noteSpecCheck(host: String, passed: Boolean, failed: Int) {
        noteFailure(
            host = host,
            step = "spec-check",
            error = if (passed) "ok" else "checks-failed",
            health = if (passed) "healthy" else "degraded",
            code = null,
            durationMs = failed.toLong(),
            specVersion = 0,
        )
    }

    fun status(): ReportStatus = reporter?.status() ?: ReportStatus(active = false)

    fun setEnabled(value: Boolean) {
        reporter?.setEnabled(value)
    }

    fun clearQueue() {
        reporter?.clearQueue()
    }


    /**
     * Обезличенный номер установки.
     *
     * Хранится локально как случайное значение, наружу уходит только хеш:
     * по нему видно, что отчёты с одного устройства, но само устройство не
     * определяется.
     */
    private fun installId(): String {
        return runCatching { Sanitizer.installIdHash(readInstall()) }.getOrDefault("").also {
            if (it.isBlank()) logcat(LogPriority.DEBUG) { "Takami: номер установки недоступен" }
        }
    }

    private fun readInstall(): String {
        val home = dir ?: return ""
        val file = File(home, INSTALL_FILE)
        if (file.exists()) {
            val stored = runCatching { file.readText().trim() }.getOrDefault("")
            if (stored.isNotBlank()) return stored
        }
        val fresh = UUID.randomUUID().toString()
        runCatching { file.writeText(fresh) }
        return fresh
    }

    private const val MAX_DURATION_MS = 10L * 60L * 1_000L
}
