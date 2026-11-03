package eu.kanade.tachiyomi.takami.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Часы, подменяемые в тестах: ожидания лимитов не занимают
 * реальное время.
 */
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
  * Внутри: два общих слота, один запрос на домен, минимальный
  * интервал со
  * случайной добавкой, средний темп со всплеском, дневной бюджет и
  * пауза после
  * ответа 429. Фоновые запросы уступают экрану: пока человек
  * смотрит список,
 * обновление библиотеки стоит в стороне.
 */
class HostGuard(
    private val budget: RequestBudget,
    private val clock: FetchClock = SystemFetchClock,
    private val jitter: () -> Long = { Random.nextLong(0L, FetchLimits.MIN_INTERVAL_JITTER_MS + 1) },
) {
    private val slots = Semaphore(FetchLimits.MAX_CONCURRENT_TOTAL)
    private val backgroundSlots = Semaphore(FetchLimits.MAX_BACKGROUND_CONCURRENT)
    private val interactive = AtomicInteger(0)
    private val hosts = ConcurrentHashMap<String, HostState>()
    private val pruneLock = Mutex()

    fun now(): Long = clock.now()

    /**
     * Те же часы, что у лимитов: паузы повторов не должны ждать в
     * обход.
     */
    internal val timeSource: FetchClock
        get() = clock

    internal suspend fun budgetHosts(): List<String> = budget.hosts()

    internal suspend fun used(host: String): Int = budget.used(host)

    internal suspend fun remaining(host: String): Int = budget.remaining(host)

    internal val dailyLimit: Int
        get() = FetchLimits.DAILY_PER_HOST

    internal suspend fun resetBudget() = budget.reset()

    suspend fun <T> run(
        host: String,
        priority: FetchPriority = FetchPriority.INTERACTIVE,
        block: suspend () -> T,
    ): T {
        // Потолок проверяем до очереди: ждать слот ради отказа смысла
        // нет.
        if (budget.remaining(host) <= 0) throw DailyLimitReached(host, FetchLimits.DAILY_PER_HOST)

        val takeBackground = priority == FetchPriority.BACKGROUND
        if (takeBackground) {
            backgroundSlots.acquire()
            try {
                while (interactive.get() > 0) clock.sleep(FetchLimits.BACKGROUND_POLL_MS)
            } catch (e: Throwable) {
                backgroundSlots.release()
                throw e
            }
        }
        if (!takeBackground) interactive.incrementAndGet()

        try {
            while (true) {
                prune(clock.now())
                val state = hosts.getOrPut(host) { HostState(clock.now()) }
                val done = slots.withPermit {
                    state.mutex.withLock {
                        // Пока ждали слот, запись могли вычистить: берём
                        // актуальную.
                        if (hosts[host] !== state) return@withLock null
                        state.busy = true
                        var sent = false
                        try {
                            state.awaitTurn(clock)
                            if (!budget.consume(host)) {
                                throw DailyLimitReached(host, FetchLimits.DAILY_PER_HOST)
                            }
                            sent = true
                            Outcome(block())
                        } finally {
                            // Паузу до следующего запроса считаем от конца
                            // текущего.
                            if (sent) state.markSent(clock.now(), jitter())
                            state.busy = false
                        }
                    }
                }
                if (done != null) return done.value
            }
        } finally {
            if (!takeBackground) interactive.decrementAndGet()
            if (takeBackground) backgroundSlots.release()
        }
    }

    /**
     * Ответ 429: следующий запрос к домену ждёт не раньше указанного
     * срока.
     */
    fun retryAfter(host: String, waitMs: Long) {
        hosts.getOrPut(host) { HostState(clock.now()) }.postpone(clock.now(), waitMs)
    }

    /**
     * Чистка карты доменов.
     *
      * Запускается только при превышении потолка: пока доменов
      * мало, удалять
      * нечего. Занятые записи не трогаем, иначе два запроса к одному
      * домену могли
     * бы пойти одновременно.
     */
    private suspend fun prune(now: Long) {
        if (hosts.size <= FetchLimits.MAX_HOSTS) return
        pruneLock.withLock {
            val stale = hosts.entries.filter { entry ->
                !entry.value.busy && now - entry.value.lastUsedAt >= FetchLimits.HOST_TTL_MS
            }
            stale.forEach { hosts.remove(it.key, it.value) }

            while (hosts.size > FetchLimits.MAX_HOSTS) {
                val oldest = hosts.entries
                    .filter { !it.value.busy }
                    .minByOrNull { it.value.lastUsedAt }
                    ?: return@withLock
                hosts.remove(oldest.key, oldest.value)
            }
        }
    }

    /**
     * Результат внутри лока: [null] означает «запись вычистили,
     * повторим».
     */
    private class Outcome<T>(val value: T)

    private class HostState(startedAt: Long) {
        val mutex = Mutex()

        @Volatile
        var lastUsedAt: Long = startedAt
            private set

        @Volatile
        var busy: Boolean = false

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
                lastUsedAt = now
                val wait = maxOf(
                    (nextAllowedAt - now).coerceAtLeast(0L),
                    (pausedUntil - now).coerceAtLeast(0L),
                    tokensWaitMs(),
                )
                if (wait <= 0L) return
                clock.sleep(wait)
            }
        }

        fun markSent(now: Long, jitterMs: Long) {
            refill(now)
            tokens = (tokens - 1.0).coerceAtLeast(0.0)
            nextAllowedAt = now + FetchLimits.MIN_INTERVAL_MS + jitterMs.coerceAtLeast(0L)
            lastUsedAt = now
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
