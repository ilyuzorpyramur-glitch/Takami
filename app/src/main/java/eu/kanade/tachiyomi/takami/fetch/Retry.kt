package eu.kanade.tachiyomi.takami.fetch

/**
 * Политика повторов.
 *
   * Повторяем только то, что имеет смысл повторить: сайт
   * перегружен
  * или моргнул.
  * Ответы вида «страницы нет» или «доступ закрыт» повтором не
  * лечатся — они
 * повторяются одинаково и только тратят чужой лимит.
 */
class RetryPolicy(
    private val attempts: Int = FetchLimits.RETRY_ATTEMPTS,
    private val delays: LongArray = FetchLimits.retryDelaysMs,
) {
    /** Стоит ли пробовать ещё раз после такого ответа. */
    fun shouldRetry(statusCode: Int): Boolean = statusCode == 429 || statusCode in 500..599

    /**
     * Пауза перед следующей попыткой.
     *
      * Если сайт сам сказал, когда возвращаться, ждём его срок: он
      * знает лучше.
     * Иначе берём согласованную лестницу пауз.
     */
    fun delayMs(attempt: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null && retryAfterMs > 0L) return retryAfterMs
        val index = (attempt - 1).coerceIn(0, delays.size - 1)
        return delays[index]
    }

    /**
     * Выполняет [block], повторяя его по правилам политики.
     *
       * Паузы идут через переданные часы: в тестах время
       * виртуальное
      * и запросы не
      * ждут по-настоящему. Своих сетевых вызовов здесь нет — [block]
      * уже ходит
     * через общий диспетчер.
     */
    suspend fun <T> execute(clock: FetchClock, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: HttpStatusError) {
                if (!shouldRetry(e.code) || attempt >= attempts) throw e
                clock.sleep(delayMs(attempt, e.retryAfterMs))
            }
        }
    }
}
