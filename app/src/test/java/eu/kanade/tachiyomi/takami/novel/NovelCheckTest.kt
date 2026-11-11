package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Причина отказа вместо нуля без объяснения. */
class NovelCheckTest {

    @Test
    fun `всё нашлось — успех`() {
        NovelCheck.of(10, 5, 900) shouldBe NovelCheck.OK
    }

    @Test
    fun `каркас важнее пустого каталога`() {
        NovelCheck.of(0, 0, 0, markup = NovelMarkup.JS_ONLY) shouldBe NovelCheck.JS_ONLY
    }

    @Test
    fun `защита сайта распознаётся`() {
        NovelCheck.of(0, 0, 0, markup = NovelMarkup.ANTI_BOT) shouldBe NovelCheck.ANTI_BOT
    }

    @Test
    fun `нет текста — своя причина`() {
        NovelCheck.of(10, 5, 0) shouldBe NovelCheck.NO_TEXT
    }
}
