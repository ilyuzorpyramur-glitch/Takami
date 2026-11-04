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
}
