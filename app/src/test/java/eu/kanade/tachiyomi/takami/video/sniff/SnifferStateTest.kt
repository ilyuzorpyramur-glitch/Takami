package eu.kanade.tachiyomi.takami.video.sniff

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Решения снимка по времени: срок, «дозор», число страниц. */
class SnifferStateTest {

    private val request = SniffRequest(url = "https://site.example/ep/1", timeoutMs = 12_000, settleMs = 1_500)

    @Test
    fun `дозор начинается с первой находки`() {
        val state = SnifferState(request)

        state.hit(1_000L) shouldBe true
        state.hit(1_100L) shouldBe false
        state.settled(2_000L) shouldBe false
        state.settled(2_500L) shouldBe true
    }

    @Test
    fun `срок ожидания истекает без находок`() {
        val state = SnifferState(request)

        state.expired(11_999L, startedAt = 0L) shouldBe false
        state.expired(12_000L, startedAt = 0L) shouldBe true
        state.found() shouldBe false
    }

    @Test
    fun `страниц не больше, чем разрешено`() {
        val state = SnifferState(request.copy(maxPages = 2))

        state.beginPage() shouldBe true
        state.beginPage() shouldBe true
        state.beginPage() shouldBe false
        state.pages shouldBe 2
    }
}
