package eu.kanade.tachiyomi.takami.video.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Отбор из длинного плейлиста по номеру серии. */
class PlaylistPickTest {

    private fun playlist(count: Int): List<String> =
        (1..count).map { "https://cdn.example/series/episode-$it/index.m3u8" }

    @Test
    fun `короткий плейлист отдаётся целиком`() {
        val short = playlist(3)

        PlaylistPick.pick(short, "https://site.example/episode/2") shouldBe short
    }

    @Test
    fun `длинный плейлист отбирается по номеру серии`() {
        val picked = PlaylistPick.pick(playlist(120), "https://site.example/title/episode-7")

        picked.size shouldBe 1
        picked.first().contains("episode-7") shouldBe true
    }

    @Test
    fun `без совпадения по номеру не отдаём ничего`() {
        val picked = PlaylistPick.pick(playlist(40), "https://site.example/title/episode-999")

        picked shouldBe emptyList()
    }

    @Test
    fun `номер страницы берётся из адреса`() {
        PlaylistPick.pageNumber("https://site.example/title/episode-12") shouldBe 12
        PlaylistPick.pageNumber("https://site.example/serial/5/seriya") shouldBe 5
        PlaylistPick.pageNumber("https://site.example/") shouldBe 0
    }
}
