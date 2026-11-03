package eu.kanade.tachiyomi.takami.fetch

import eu.kanade.tachiyomi.takami.report.TakamiReports

/**
 * Строка отчёта по одному домену: сколько потрачено и как себя
 * чувствует сайт.
 */
data class HostReport(
    val host: String,
    val used: Int,
    val remaining: Int,
    val limit: Int,
    val breaker: BreakerState,
    val pauseLeftMs: Long,
    val lastError: String?,
)

/**
 * Сводка по расходу для экрана диагностики.
 *
  * Отдельная точка входа нужна, чтобы экран не держал в руках
  * сетевой клиент:
  * он читает только счётчики и состояние предохранителя, но сам
  * ничего не
 * запрашивает у сайтов.
 */
/** Сводка по сбору отчётов для экрана: без сетевых типов наружу. */
data class ReportsView(
    val queued: Int = 0,
    val totalSent: Int = 0,
    val lastSentAt: Long = 0L,
    val enabled: Boolean = true,
    val active: Boolean = true,
    val deadline: Long = 0L,
)

object NetworkDiagnostics {
    suspend fun report(): List<HostReport> = Fetcher.get().diagnostics()

    /** Сколько отчётов в очереди и когда отправляли в прошлый раз. */
    fun reports(): ReportsView {
        val status = TakamiReports.status()
        return ReportsView(
            queued = status.queued,
            totalSent = status.totalSent,
            lastSentAt = status.lastSentAt,
            enabled = status.enabled,
            active = status.active,
            deadline = status.deadline,
        )
    }

    fun setReportsEnabled(value: Boolean) = TakamiReports.setEnabled(value)

    fun clearReports() = TakamiReports.clearQueue()

    /** Провал проверки спеки после сохранения: точка сбора на стороне правки. */
    fun noteSpecCheck(host: String, passed: Boolean, failed: Int) =
        TakamiReports.noteSpecCheck(host, passed, failed)

    /** Сайты, которые ждут проверки человеком. */
    suspend fun verifications(): List<VerificationStatus> = Fetcher.get().verifications()

    /**
     * Сброс дневных счётчиков. Состояние предохранителя не
     * трогаем.
     */
    suspend fun resetCounters() = Fetcher.get().resetCounters()
}
