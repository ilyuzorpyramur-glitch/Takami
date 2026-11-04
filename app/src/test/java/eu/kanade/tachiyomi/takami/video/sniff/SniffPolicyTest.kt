package eu.kanade.tachiyomi.takami.video.sniff

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.SniffSpec
import eu.kanade.tachiyomi.takami.VideoSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Кому снимок разрешён. */
class SniffPolicyTest {

    private fun spec(host: String = "site.example", mode: String = "fallback") = SiteSpec(
        host = host,
        baseUrl = "https://" + host,
        name = "Источник",
        catalog = CatalogSpec(itemSelector = "article"),
        video = VideoSpec(sniff = SniffSpec(mode = mode)),
    )

    @Test
    fun `домен только для чтения не снимаем`() {
        SniffPolicy.canSniff(spec("dreamerscast.com")) shouldBe false
        SniffPolicy.canSniff(spec("site.example"), host = "dreamerscast.com") shouldBe false
        SniffPolicy.readOnly("www.dreamerscast.com") shouldBe true
        SniffPolicy.readOnly("kodikdreamcast") shouldBe true
    }

    @Test
    fun `режим never уважается`() {
        SniffPolicy.canSniff(spec(mode = "never")) shouldBe false
        SniffPolicy.canSniff(spec(mode = "unknown")) shouldBe true
    }

    @Test
    fun `без серий и в паузе не снимаем`() {
        SniffPolicy.canSniff(spec(), hasEpisodes = false) shouldBe false
        SniffPolicy.canSniff(spec(), paused = true) shouldBe false
    }

    @Test
    fun `обычный источник снимаем`() {
        SniffPolicy.canSniff(spec()) shouldBe true
    }
}
