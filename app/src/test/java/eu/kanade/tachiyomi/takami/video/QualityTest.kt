package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Нормализация метки, дедуп по паре осей и порядок вариантов. */
class QualityTest {

    @Test
    fun `подписи приводятся к одному словарю`() {
        Quality.normalize("HD") shouldBe "720p"
        Quality.normalize("FHD") shouldBe "1080p"
        Quality.normalize("SD") shouldBe "480p"
        Quality.normalize("720") shouldBe "720p"
        Quality.normalize("[1080p]") shouldBe "1080p"
        Quality.normalize(" 480p ") shouldBe "480p"
    }

    @Test
    fun `мусорная метка даёт пустое качество без исключения`() {
        Quality.normalize("видео") shouldBe ""
        Quality.normalize("плеер") shouldBe ""
        Quality.normalize("") shouldBe ""
        Quality.normalize(null) shouldBe ""
        // Не число качества: год и номер серии в диапазон не попадают.
        Quality.normalize("2024") shouldBe ""
        Quality.text("") shouldBe Quality.UNDEFINED
    }

    @Test
    fun `повторы складываются по паре озвучка и качество`() {
        val links = listOf(
            QualityLink("https://a/1.mp4", "AniStar", "720p"),
            QualityLink("https://a/2.mp4", "AniStar", "720p"),
            QualityLink("https://a/3.mp4", "AniStar", "480p"),
            QualityLink("https://a/4.mp4", "AniLib", "720p"),
        )

        val unique = Quality.dedupe(links)

        unique.size shouldBe 3
        unique.map { it.url } shouldBe listOf("https://a/1.mp4", "https://a/3.mp4", "https://a/4.mp4")
    }

    @Test
    fun `сортировка идёт по порядку из спеки`() {
        val links = listOf(
            QualityLink("https://a/1.mp4", "AniStar", "360p"),
            QualityLink("https://a/2.mp4", "AniStar", "1080p"),
            QualityLink("https://a/3.mp4", "AniStar", "720p"),
        )

        val sorted = Quality.arrange(links, listOf("720p", "1080p", "360p"))

        sorted.map { it.quality } shouldBe listOf("720p", "1080p", "360p")
        sorted.none { it.single } shouldBe true
    }

    @Test
    fun `одно качество помечается единственным`() {
        val links = listOf(QualityLink("https://a/1.mp4", "AniStar", "720p"))

        val single = Quality.arrange(links)

        single.single().single shouldBe true
    }
}
