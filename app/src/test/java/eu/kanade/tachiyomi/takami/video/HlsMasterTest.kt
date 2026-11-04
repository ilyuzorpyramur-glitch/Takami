package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Разбор мастер-плейлиста: только текст, без запросов и сегментов. */
class HlsMasterTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,NAME="360p"
        https://cdn.example/360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,NAME="720p"
        https://cdn.example/720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
        https://cdn.example/1080/index.m3u8
    """.trimIndent()

    @Test
    fun `варианты берутся из строк с атрибутами`() {
        val links = HlsMaster.parse(master, "AniStar")

        links.size shouldBe 3
        links.map { it.quality } shouldBe listOf("360p", "720p", "1080p")
        links.first().url shouldBe "https://cdn.example/360/index.m3u8"
        links.first().dubbing shouldBe "AniStar"
        links.all { it.kind == "hls" } shouldBe true
    }

    @Test
    fun `высота из разрешения заменяет отсутствующую метку`() {
        val links = HlsMaster.parse(master)

        links.last().quality shouldBe "1080p"
    }

    @Test
    fun `обычный плейлист сегментов вариантов не даёт`() {
        val plain = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.9,
            segment-1.ts
        """.trimIndent()

        HlsMaster.parse(plain) shouldBe emptyList()
        HlsMaster.parse("") shouldBe emptyList()
    }
}
