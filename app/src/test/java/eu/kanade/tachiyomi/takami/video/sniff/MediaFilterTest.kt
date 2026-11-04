package eu.kanade.tachiyomi.takami.video.sniff

import eu.kanade.tachiyomi.takami.SniffSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Отбор и ранг пойманного окном. */
class MediaFilterTest {

    private val spec = SniffSpec()

    @Test
    fun `медиа по адресу остаётся, сегменты уходят`() {
        val hits = listOf(
            SniffHit("https://cdn.example/master.m3u8"),
            SniffHit("https://cdn.example/a.mp4"),
            SniffHit("https://cdn.example/b.webm"),
            SniffHit("https://cdn.example/c.mpd"),
            SniffHit("https://cdn.example/seg-1.ts"),
            SniffHit("https://cdn.example/seg-2.m4s"),
        )

        val kept = MediaFilter.keep(hits, spec)

        kept.size shouldBe 4
        kept.none { it.url.endsWith(".ts") || it.url.endsWith(".m4s") } shouldBe true
    }

    @Test
    fun `тип содержимого тоже считается`() {
        val hits = listOf(
            SniffHit("https://cdn.example/play", "application/vnd.apple.mpegurl"),
            SniffHit("https://cdn.example/manifest", "application/dash+xml"),
            SniffHit("https://cdn.example/stream", "video/mp4"),
            SniffHit("https://cdn.example/pic", "image/png"),
        )

        MediaFilter.keep(hits, spec).size shouldBe 3
    }

    @Test
    fun `blob и data не берём`() {
        val hits = listOf(SniffHit("blob:https://site.example/1"), SniffHit("data:video/mp4;base64,AA"))

        MediaFilter.keep(hits, spec) shouldBe emptyList()
    }

    @Test
    fun `мастер важнее файла, файл важнее обычного плейлиста`() {
        val hits = listOf(
            SniffHit("https://cdn.example/media.m3u8"),
            SniffHit("https://cdn.example/a.mp4"),
            SniffHit("https://cdn.example/master.m3u8"),
        )

        val kept = MediaFilter.keep(hits, spec, setOf("https://cdn.example/master.m3u8"))

        kept.first().url shouldBe "https://cdn.example/master.m3u8"
        kept[1].url shouldBe "https://cdn.example/a.mp4"
        kept.last().url shouldBe "https://cdn.example/media.m3u8"
    }

    @Test
    fun `повторы склеиваются по полному адресу`() {
        val hits = listOf(
            SniffHit("https://cdn.example/a.mp4?sign=1"),
            SniffHit("https://cdn.example/a.mp4?sign=1"),
            SniffHit("https://cdn.example/a.mp4?sign=2"),
        )

        // Query не режем: подпись в нём, без неё ссылка мертва.
        MediaFilter.keep(hits, spec).size shouldBe 2
    }

    @Test
    fun `запрещённые домены не берём`() {
        val deny = spec.copy(denyHosts = listOf("ads.example"))
        val hits = listOf(
            SniffHit("https://ads.example/a.mp4"),
            SniffHit("https://cdn.example/b.mp4"),
        )

        MediaFilter.keep(hits, deny).map { it.url } shouldBe listOf("https://cdn.example/b.mp4")
    }
}
