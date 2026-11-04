package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.HosterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.VideoSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Обход цепочки на подставных страницах: без сети и без живых адресов. */
class ChainTest {

    private fun spec(depth: Int = 2, players: Int = 8) = SiteSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Пример",
        catalog = CatalogSpec(itemSelector = "article"),
        video = VideoSpec(iframeDepth = depth, maxPlayers = players),
    )

    private fun page(vararg frames: String): String =
        "<html><body>" + frames.joinToString("") { "<iframe src=\"" + it + "\">" } + "</body></html>"

    private fun player(url: String): String =
        "<html><script>var p = new Playerjs({file:\"" + url + "\"});</script></html>"

    @Test
    fun `рабочий второй плеер находится`() = runBlocking {
        val pages = mapOf(
            "https://site.example/p1" to "<html>файла нет</html>",
            "https://site.example/p2" to player("[720p]//cdn.example/b.mp4"),
            "https://site.example/p3" to player("//cdn.example/c.mp4"),
        )

        val result = Chain.collect(spec(), page("/p1", "/p2", "/p3"), "https://site.example/ep/1", "") {
            pages[it]
        }

        result.links.map { it.url }.contains("https://cdn.example/b.mp4") shouldBe true
        result.links.map { it.url }.contains("https://cdn.example/c.mp4") shouldBe true
        result.links.any { it.quality == "720p" } shouldBe true
    }

    @Test
    fun `два уровня вложенности разворачиваются, глубже не идём`() = runBlocking {
        val pages = mapOf(
            "https://site.example/outer" to page("/inner"),
            "https://site.example/inner" to page("/deep"),
            "https://site.example/deep" to player("//cdn.example/deep.mp4"),
        )
        val episode = page("/outer")

        val two = Chain.collect(spec(depth = 2), episode, "https://site.example/ep/1", "") { pages[it] }
        val one = Chain.collect(spec(depth = 1), episode, "https://site.example/ep/1", "") { pages[it] }

        two.links.map { it.url } shouldBe listOf("https://cdn.example/deep.mp4")
        one.links shouldBe emptyList()
    }

    @Test
    fun `предел числа плееров соблюдается`() = runBlocking {
        val pages = mapOf(
            "https://site.example/p1" to "<html>файла нет</html>",
            "https://site.example/p2" to "<html>файла нет</html>",
            "https://site.example/p3" to player("//cdn.example/c.mp4"),
        )

        val limited = Chain.collect(spec(players = 2), page("/p1", "/p2", "/p3"), "https://site.example/e", "") {
            pages[it]
        }
        val full = Chain.collect(spec(players = 3), page("/p1", "/p2", "/p3"), "https://site.example/e", "") {
            pages[it]
        }

        limited.links shouldBe emptyList()
        full.links.map { it.url } shouldBe listOf("https://cdn.example/c.mp4")
    }

    @Test
    fun `повтор адреса с двух плееров остаётся одной ссылкой`() = runBlocking {
        val pages = mapOf(
            "https://site.example/p1" to player("//cdn.example/same.mp4"),
            "https://site.example/p2" to player("//cdn.example/same.mp4"),
        )

        val result = Chain.collect(spec(), page("/p1", "/p2"), "https://site.example/e", "") { pages[it] }

        result.links.size shouldBe 1
    }

    @Test
    fun `потолок запросов останавливает обход`() = runBlocking {
        var asked = 0
        val pages = mapOf("https://site.example/p1" to "<html>файла нет</html>")

        val result = Chain.collect(spec(), page("/p1", "/p2", "/p3"), "https://site.example/e", "") {
            asked++
            pages[it]
        }

        asked shouldBe 1
        result.links shouldBe emptyList()
    }

    @Test
    fun `плеер с готовым файлом страницу не открывает`() = runBlocking {
        val hoster = HosterSpec(name = "поток", urlPattern = """//cdn\.example/""", kind = "hls")
        val withHoster = spec().copy(video = VideoSpec(hosters = listOf(hoster)))

        val result = Chain.collect(withHoster, page("https://cdn.example/x.m3u8"), "https://site.example/e", "") {
            throw IllegalStateException("страницу открывать не нужно")
        }

        result.links.map { it.url } shouldBe listOf("https://cdn.example/x.m3u8")
    }

    @Test
    fun `закрытый плеер помечается, но разбор не падает`() = runBlocking {
        val result = Chain.collect(spec(), page("/p1"), "https://site.example/e", "") { null }

        result.links shouldBe emptyList()
        result.blocked shouldBe true
    }
}
