package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Сбор озвучек: повторы, мусорные подписи и число серий. */
class DubbingTest {

    @Test
    fun `повторы складываются по имени`() {
        val names = Dubbing.names(listOf("AniStar", "AniStar", " AniLib ", "AniLib"))

        names shouldBe listOf("AniStar", "AniLib")
    }

    @Test
    fun `служебные подписи отбрасываются`() {
        val names = Dubbing.names(
            listOf("смотреть", "ещё", "Серия 5", "AniStar", "", "12"),
        )

        names shouldBe listOf("AniStar")
    }

    @Test
    fun `список ограничен двадцатью записями`() {
        val many = (1..40).map { "Студия " + it }

        Dubbing.names(many).size shouldBe Dubbing.MAX
    }

    @Test
    fun `дорожки запоминают наибольшее число серий`() {
        val tracks = Dubbing.tracks(
            listOf(
                DubbingTrack("AniStar", 2),
                DubbingTrack("AniStar", 12),
                DubbingTrack("AniLib", 4),
            ),
        )

        tracks.size shouldBe 2
        tracks.first().episodes shouldBe 12
        tracks.map { it.name } shouldBe listOf("AniStar", "AniLib")
    }

    @Test
    fun `пустой список остаётся пустым`() {
        Dubbing.names(emptyList()) shouldBe emptyList()
        Dubbing.meaningful("смотреть") shouldBe false
        Dubbing.meaningful("AniStar") shouldBe true
    }
}
