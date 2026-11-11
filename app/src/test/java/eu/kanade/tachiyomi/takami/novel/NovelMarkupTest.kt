package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Какой ответ отдал сайт: разметку, каркас или защиту. */
class NovelMarkupTest {

    @Test
    fun `каркас nuxt распознаётся как браузерная сборка`() {
        val html = """<html><div id="app"></div><script>window.__NUXT__={}</script></html>"""
            .padEnd(300, ' ')

        NovelMarkup.detect(html) shouldBe NovelMarkup.JS_ONLY
    }

    @Test
    fun `проверка браузера распознаётся отдельно`() {
        val html = "<html><title>Just a moment...</title>" + "x".repeat(300) + "</html>"

        NovelMarkup.detect(html) shouldBe NovelMarkup.ANTI_BOT
    }

    @Test
    fun `пустой ответ не считается каталогом`() {
        NovelMarkup.detect("") shouldBe NovelMarkup.EMPTY
    }

    @Test
    fun `обычная страница с карточками считается серверной`() {
        val card = """<div class="card"><a href="/n/1"><img src="/1.jpg"></a></div>"""
        val html = "<html><body>" + card.repeat(4) + "</body></html>"

        NovelMarkup.detect(html) shouldBe NovelMarkup.SERVER
    }
}
