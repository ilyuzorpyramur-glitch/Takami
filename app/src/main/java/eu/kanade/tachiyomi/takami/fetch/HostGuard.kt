package eu.kanade.tachiyomi.takami.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Часы, которые в тестах подменяются, чтобы ожидания не занимали реальное время. */
interface FetchClock {
    fun now(): Long

    suspend fun sleep(ms: Long)
}

object SystemFetchClock : FetchClock {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun sleep(ms: Long) {
        if (ms > 0L) delay(ms)
    }
}

/**
 * Пропускает запросы через лимиты домена.
 *
 * Внутри: два общих слота на приложение, по одному запросу на домен, минимальный интервал,
 * средний темп со всплеском, дневной бюджет и пауза после ответа 429. Сам запрос выполняет
 * вызывающий код внутри [run]: обойти лимиты, обратившись к сети напрямую, нельзя.
 */
class HostGuard(
    private val budget: RequestBudget,
    private val clock: FetchClock = SystemFetchClock,
) {
    private val slots = Semaphore(FetchLimits.MAX_CONCURRENT_TOTAL)
    private val hosts = ConcurrentHashMap<String, HostState>()

    fun now(): Long = clock.now()

    suspend fun <T> run(host: String, block: suspend () -> T): T {
        // Потолок проверяем до очереди: смысла ждать слот, чтобы потом получить отказ, нет.
        if (budget.remaining(host) <= 0) throw DailyLimitReached(host, FetchLimits.DAILY_PER_HOST)

        val state = hosts.getOrPut(host) { HostState(clock.now()) }
        return slots.withPermit {
            state.mutex.withLock {
                state.awaitTurn(clock)
                if (!budget.consume(host)) throw DailyLimitReached(host, FetchLimits.DAILY_PER_HOST)
                state.markSent(clock.now())
                block()
            }
        }
    }

    /** Ответ 429: следующий запрос к домену ждёт не раньше указанного срока. */
    fun retryAfter(host: String, waitMs: Long) {
        hosts.getOrPut(host) { HostState(clock.now()) }.postpone(clock.now(), waitMs)
    }

    private class HostState(startedAt: Long) {
        val mutex = Mutex()

        private var nextAllowedAt = 0L
        private var pausedUntil = 0L
        private var tokens = FetchLimits.BURST.toDouble()
        private var lastRefillAt = startedAt

        fun postpone(now: Long, waitMs: Long) {
            val until = now + waitMs.coerceAtLeast(0L)
            if (until > pausedUntil) pausedUntil = until
        }

        suspend fun awaitTurn(clock: FetchClock) {
            while (true) {
                val now = clock.now()
                refill(now)
                val wait = maxOf(
                    (nextAllowedAt - now).coerceAtLeast(0L),
                    (pausedUntil - now).coerceAtLeast(0L),
                    tokensWaitMs(),
                )
                if (wait <= 0L) return
                clock.sleep(wait)
            }
        }

        fun markSent(now: Long) {
            refill(now)
            tokens = (tokens - 1.0).coerceAtLeast(0.0)
            nextAllowedAt = now + FetchLimits.MIN_INTERVAL_MS
        }

        private fun tokensWaitMs(): Long {
            if (tokens >= 1.0) return 0L
            val missing = 1.0 - tokens
            return kotlin.math.ceil(missing * FetchLimits.AVERAGE_INTERVAL_MS).toLong()
        }

        private fun refill(now: Long) {
            if (now <= lastRefillAt) return
            val gained = (now - lastRefillAt).toDouble() / FetchLimits.AVERAGE_INTERVAL_MS.toDouble()
            tokens = (tokens + gained).coerceAtMost(FetchLimits.BURST.toDouble())
            lastRefillAt = now
        }
    }
}
