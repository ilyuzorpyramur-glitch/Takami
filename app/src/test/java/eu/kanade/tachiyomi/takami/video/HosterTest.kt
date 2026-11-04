package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.HosterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.VideoSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Плеер по спеке: сопоставление, идентификатор и адрес карты. */
class HosterTest {

    private val hoster = HosterSpec(
        name = "playerjs",
        urlPattern = """//[a-z0-9.-]+/player""",
        kind = "playerjs",
        apiTemplate = "https://api.example/video/{id}",
        idRegex = """/embed/(\d+)""",
    )

    private val spec = SiteSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Пример",
        catalog = CatalogSpec(itemSelector = "article"),
        video = VideoSpec(hosters = listOf(hoster)),
    )

    @Test
    fun `плеер находится по шаблону адреса`() {
        Hoster.match(spec, "https://cdn.example/player/42")?.name shouldBe "playerjs"
        Hoster.match(spec, "https://cdn.example/other/42") shouldBe null
        Hoster.match(spec, "") shouldBe null
    }

    @Test
    fun `идентификатор берётся из шаблона`() {
        Hoster.idOf(hoster, "https://cdn.example/embed/42") shouldBe "42"
        Hoster.idOf(hoster, "https://cdn.example/embed/") shouldBe ""
    }

    @Test
    fun `адрес карты подставляет идентификатор`() {
        Hoster.apiUrl(hoster, "https://cdn.example/embed/42") shouldBe
            "https://api.example/video/42"
    }

    @Test
    fun `шаблон без идентификатора берётся как есть`() {
        val plain = hoster.copy(apiTemplate = "//api.example/list")

        Hoster.apiUrl(plain, "https://cdn.example/embed/42") shouldBe "https://api.example/list"
    }

    @Test
    fun `без шаблона карту не спрашиваем`() {
        Hoster.apiUrl(hoster.copy(apiTemplate = null), "https://cdn.example/embed/42") shouldBe null
        Hoster.apiUrl(hoster.copy(apiTemplate = ""), "https://cdn.example/embed/42") shouldBe null
    }

    @Test
    fun `шаблону нужен идентификатор, а его нет`() {
        Hoster.apiUrl(hoster, "https://cdn.example/none") shouldBe null
    }
}
