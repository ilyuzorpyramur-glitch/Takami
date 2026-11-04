package eu.kanade.tachiyomi.takami.video.sniff

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Отказ вместо нуля: итог по находкам и причине. */
class SniffVerdictTest {

    @Test
    fun `ссылка, которая отдаёт поток, это успех`() {
        SniffVerdict.of(SniffResult(), links = 3, plays = true) shouldBe SniffVerdict.OK
    }

    @Test
    fun `ссылка без потока — отдельный итог`() {
        SniffVerdict.of(SniffResult(), links = 2, plays = false) shouldBe SniffVerdict.NO_VIDEO
    }

    @Test
    fun `причины отказа различаются`() {
        SniffVerdict.of(SniffResult(stopReason = SniffStop.UNAVAILABLE), 0, false) shouldBe
            SniffVerdict.UNAVAILABLE
        SniffVerdict.of(SniffResult(stopReason = SniffStop.DRM), 0, false) shouldBe SniffVerdict.DRM
        SniffVerdict.of(SniffResult(stopReason = SniffStop.TIMEOUT), 0, false) shouldBe
            SniffVerdict.BLOCKED
        SniffVerdict.of(SniffResult(stopReason = SniffStop.FOUND), 0, false) shouldBe
            SniffVerdict.NO_LINKS
    }

    @Test
    fun `подпись называет слой`() {
        SniffVerdict.text(SniffVerdict.OK, 5, SniffLayer.CONFIG) shouldBe "5 ссылок (конфиг)"
        SniffVerdict.text(SniffVerdict.BLOCKED, 0, "") shouldBe "плеер не раскрылся"
        SniffVerdict.text(SniffVerdict.DRM, 0, "") shouldBe "только DRM"
        SniffVerdict.text(SniffVerdict.UNAVAILABLE, 0, "") shouldBe "недоступно по лицензии"
    }
}
