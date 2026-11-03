package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Правила повтора без сети: что повторяем и с какими паузами. */
class RetryPolicyTest {
    private val policy = RetryPolicy()

    @Test
    fun `повторяются только перегрузка и сбои сервера`() {
        policy.shouldRetry(429) shouldBe true
        policy.shouldRetry(500) shouldBe true
        policy.shouldRetry(502) shouldBe true
        policy.shouldRetry(503) shouldBe true
        policy.shouldRetry(504) shouldBe true
    }

    @Test
    fun `обычные ответы и переходы не повторяются`() {
        policy.shouldRetry(200) shouldBe false
        policy.shouldRetry(301) shouldBe false
        policy.shouldRetry(304) shouldBe false
    }

    @Test
    fun `паузы идут лестницей пять тридцать сто двадцать`() {
        policy.delayMs(1, null) shouldBe FetchLimits.retryDelaysMs[0]
        policy.delayMs(2, null) shouldBe FetchLimits.retryDelaysMs[1]
        policy.delayMs(3, null) shouldBe FetchLimits.retryDelaysMs[2]
        policy.delayMs(9, null) shouldBe FetchLimits.retryDelaysMs.last()
    }

    @Test
    fun `срок сайта важнее собственной паузы`() {
        policy.delayMs(1, 42_000L) shouldBe 42_000L
    }

    @Test
    fun `попыток не больше трёх`() {
        FetchLimits.RETRY_ATTEMPTS shouldBe 3
        FetchLimits.retryDelaysMs.size shouldBe 3
    }
}
