package eu.kanade.tachiyomi.takami.news

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Правила новостей: источник только для чтения закрыт кодом. */
class NewsPolicyTest {

    @Test
    fun `домен только для чтения не детектится`() {
        NewsPolicy.canDetect("dreamerscast.com") shouldBe false
        NewsPolicy.canDetect("www.dreamerscast.com") shouldBe false
    }

    @Test
    fun `ключ спеки только для чтения не детектится`() {
        NewsPolicy.canDetect("kodikdreamcast") shouldBe false
    }

    @Test
    fun `обычный источник детектится`() {
        NewsPolicy.canDetect("example.test") shouldBe true
        NewsPolicy.canShow("example.test") shouldBe true
    }

    @Test
    fun `для источника только для чтения блок не собирается`() {
        NewsPolicy.canShow("dreamerscast.com") shouldBe false
    }

    @Test
    fun `мёртвая лента оживает через неделю`() {
        val now = 1_800_000_000_000L
        NewsPolicy.markDeadFeed("example.test", now)

        NewsPolicy.isDeadFeed("example.test", now + NewsPolicy.DEAD_FEED_MS - 1) shouldBe true
        NewsPolicy.isDeadFeed("example.test", now + NewsPolicy.DEAD_FEED_MS + 1) shouldBe false
    }

    @Test
    fun `неизвестная лента не мертва`() {
        NewsPolicy.isDeadFeed("other.test", 0L) shouldBe false
    }

    @Test
    fun `непроверенный источник детектится сразу`() {
        NewsPolicy.dueForDetect(0L, 1_800_000_000_000L) shouldBe true
    }

    @Test
    fun `неудачную проверку повторяем через неделю`() {
        val now = 1_800_000_000_000L
        val checked = now - 60_000L

        NewsPolicy.dueForDetect(checked, now) shouldBe false
        NewsPolicy.dueForDetect(checked, checked + NewsPolicy.RETRY_AFTER_MS + 1) shouldBe true
    }

    @Test
    fun `кэш живёт сорок пять минут`() {
        NewsPolicy.TTL_MS shouldBe 45L * 60 * 1000
    }
}
