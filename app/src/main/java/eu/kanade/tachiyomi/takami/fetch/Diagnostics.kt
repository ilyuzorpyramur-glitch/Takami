package eu.kanade.tachiyomi.takami.fetch

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
object NetworkDiagnostics {
    suspend fun report(): List<HostReport> = Fetcher.get().diagnostics()

    /** Сайты, которые ждут проверки человеком. */
    suspend fun verifications(): List<VerificationStatus> = Fetcher.get().verifications()

    /**
     * Сброс дневных счётчиков. Состояние предохранителя не
     * трогаем.
     */
    suspend fun resetCounters() = Fetcher.get().resetCounters()
}
