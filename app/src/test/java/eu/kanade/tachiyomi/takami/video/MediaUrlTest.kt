package eu.kanade.tachiyomi.takami.video

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Адреса из разметки: экранирование, мнемоники, кодирование, относительный путь. */
class MediaUrlTest {

    private val base = "https://site.example/title/1/"

    @Test
    fun `экранирование снимается`() {
        MediaUrl.normalize("https:\\/\\/cdn.example\\/a.mp4") shouldBe "https://cdn.example/a.mp4"
        MediaUrl.normalize("https://cdn.example/a.mp4?x=1\\u0026y=2") shouldBe
            "https://cdn.example/a.mp4?x=1&y=2"
        MediaUrl.normalize("https://cdn.example/a.mp4?x=1&amp;y=2") shouldBe
            "https://cdn.example/a.mp4?x=1&y=2"
    }

    @Test
    fun `протокол-относительный адрес получает схему`() {
        MediaUrl.normalize("//cdn.example/a.m3u8") shouldBe "https://cdn.example/a.m3u8"
    }

    @Test
    fun `кодирование раскодируется один раз`() {
        MediaUrl.normalize("https:%2F%2Fcdn.example%2Fa.mp4") shouldBe "https://cdn.example/a.mp4"
    }

    @Test
    fun `относительный путь считается от страницы`() {
        MediaUrl.normalize("/embed/123", base) shouldBe "https://site.example/embed/123"
        MediaUrl.normalize("/embed/123") shouldBe ""
    }

    @Test
    fun `непригодный адрес даёт пусто`() {
        MediaUrl.normalize("javascript:void(0)") shouldBe ""
        MediaUrl.normalize("about:blank") shouldBe ""
        MediaUrl.normalize("data:text/html,x") shouldBe ""
        MediaUrl.normalize("") shouldBe ""
        MediaUrl.normalize("   ") shouldBe ""
    }

    @Test
    fun `обрамляющие кавычки и запятые убираются`() {
        MediaUrl.normalize("\"https://cdn.example/a.mp4\",") shouldBe "https://cdn.example/a.mp4"
        MediaUrl.normalize("\u0027//cdn.example/b.mp4\u0027") shouldBe "https://cdn.example/b.mp4"
    }
}
