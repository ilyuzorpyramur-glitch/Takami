package eu.kanade.tachiyomi.takami.fetch

/**
 * Виртуальные часы: ожидания лимитов не занимают реальное время.
 *
 * sleep сдвигает время вперёд, поэтому тест проверяет не «сколько ждали», а «когда пошли».
 */
class FakeFetchClock(start: Long = 1_700_000_000_000L) : FetchClock {
    val sleeps = mutableListOf<Long>()

    @Volatile
    private var value: Long = start

    override fun now(): Long = value

    override suspend fun sleep(ms: Long) {
        if (ms <= 0L) return
        sleeps += ms
        value += ms
    }

    fun advance(ms: Long) {
        value += ms
    }
}
