package eu.kanade.tachiyomi.takami.video.config

import eu.kanade.tachiyomi.takami.VideoSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Шахта конфига: данные страницы без сети. */
class MediaMinerTest {

    private val spec = VideoSpec()

    @Test
    fun `JSON-конфиг читается в обоих порядках полей`() {
        val first = """{"file":"https://cdn.example/a.mp4","title":"720p"}"""
        val second = """{"title":"720p","file":"https://cdn.example/b.mp4"}"""

        MediaMiner.mine(first, "https://site.example/ep/1", spec).map { it.url } shouldBe
            listOf("https://cdn.example/a.mp4")
        MediaMiner.mine(second, "https://site.example/ep/1", spec).map { it.url } shouldBe
            listOf("https://cdn.example/b.mp4")
    }

    @Test
    fun `экранированный адрес приводится к обычному виду`() {
        val html = """var cfg = {"src":"https:\/\/cdn.example\/c.mp4"};"""

        MediaMiner.mine(html, "https://site.example/ep/1", spec).map { it.url } shouldBe
            listOf("https://cdn.example/c.mp4")
    }

    @Test
    fun `base64 раскрывается только там, где внутри http`() {
        val html = "file:\"" + "WzcyMHBdaHR0cHM6Ly9jZG4uZXhhbXBsZS9tLm1wNA==" + "\""

        MediaMiner.mine(html, "https://site.example/ep/1", spec).map { it.url } shouldBe
            listOf("https://cdn.example/m.mp4")
    }

    @Test
    fun `длинный плейлист отбирается по номеру серии`() {
        val links = (1..30).joinToString(",") { """"https://cdn.example/episode-$it/index.m3u8"""" }
        val html = "sources: [$links]"

        val picked = MediaMiner.mine(html, "https://site.example/episode-9", spec)

        picked.size shouldBe 1
        picked.first().url.contains("episode-9") shouldBe true
    }

    @Test
    fun `плейлист без совпадения по номеру ничего не отдаёт`() {
        val links = (1..30).joinToString(",") { """"https://cdn.example/episode-$it/index.m3u8"""" }

        MediaMiner.mine("sources: [$links]", "https://site.example/title", spec) shouldBe emptyList()
    }

    @Test
    fun `мусор в разметке не даёт ссылок`() {
        MediaMiner.mine("""{"file":""}""", "https://site.example/ep/1", spec) shouldBe emptyList()
        MediaMiner.mine("""<div file="нет адреса"></div>""", "https://site.example/ep/1", spec) shouldBe
            emptyList()
        MediaMiner.mine("", "https://site.example/ep/1", spec) shouldBe emptyList()
    }
}
