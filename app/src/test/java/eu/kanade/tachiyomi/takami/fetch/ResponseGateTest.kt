package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** Ворота ответа: что считаем страницей, а что заглушкой. */
class ResponseGateTest {

    @Test
    fun `обычный html проходит`() {
        val page = ResponseGate.check(
            "text/html; charset=utf-8",
            "<html><body>каталог</body></html>".toByteArray(),
            "https://site.example/",
        )

        page.charset.lowercase().contains("utf") shouldBe true
    }

    @Test
    fun `заглушка cloudflare распознаётся`() {
        val body = "<html><title>Just a moment...</title><div id=\"cf-chl\"></div></html>"

        assertFailsWith<ChallengePageError> {
            ResponseGate.check("text/html", body.toByteArray(), "https://site.example/")
        }
    }

    @Test
    fun `заглушка ddos-guard распознаётся`() {
        val body = "<html><body>DDoS-Guard: checking your browser</body></html>"

        assertFailsWith<ChallengePageError> {
            ResponseGate.check("text/html", body.toByteArray(), "https://site.example/")
        }
    }

    @Test
    fun `json не считается страницей`() {
        assertFailsWith<NotHtmlError> {
            ResponseGate.check("application/json", "{\"a\":1}".toByteArray(), "https://s.example/")
        }
    }

    @Test
    fun `картинка не считается страницей`() {
        assertFailsWith<NotHtmlError> {
            ResponseGate.check("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "https://s/")
        }
    }

    @Test
    fun `пустое тело не уходит в разбор`() {
        assertFailsWith<NotHtmlError> {
            ResponseGate.check("text/html", ByteArray(0), "https://site.example/")
        }
    }
}
