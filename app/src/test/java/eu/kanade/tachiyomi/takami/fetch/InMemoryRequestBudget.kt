package eu.kanade.tachiyomi.takami.fetch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Счётчик для тестов: та же семантика, что у файловой версии, но без диска. */
class InMemoryRequestBudget(
    private val limit: Int = FetchLimits.DAILY_PER_HOST,
) : RequestBudget {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun used(host: String): Int = counts[host]?.get() ?: 0

    override suspend fun remaining(host: String): Int = (limit - used(host)).coerceAtLeast(0)

    override suspend fun consume(host: String): Boolean {
        val counter = counts.getOrPut(host) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= limit) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    override suspend fun reset() {
        counts.clear()
    }
}
