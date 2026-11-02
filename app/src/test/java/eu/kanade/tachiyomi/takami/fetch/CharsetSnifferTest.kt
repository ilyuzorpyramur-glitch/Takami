package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/**
 * Кодировка определяется так же, как в браузере: BOM, заголовок, meta.
 */
class CharsetSnifferTest {

    @Test
    fun `заголовок важнее meta`() {
        val bytes = "<html><meta charset=\"windows-1251\"></html>".toByteArray(Charsets.UTF_8)

        CharsetSniffer.detect("text/html; charset=utf-8", bytes) shouldBe Charsets.UTF_8
    }

    @Test
    fun `meta используется, если в заголовке пусто`() {
        val bytes = "<html><meta charset=\"windows-1251\"></html>".toByteArray(Charsets.UTF_8)

        CharsetSniffer.detect("text/html", bytes).name().lowercase().contains("1251") shouldBe true
    }

    @Test
    fun `meta в форме http-equiv тоже читается`() {
        val html = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=cp1251\">"
        CharsetSniffer.detect(null, html.toByteArray()).name().lowercase().contains("1251") shouldBe true
    }

    @Test
    fun `без подсказок считаем utf-8`() {
        CharsetSniffer.detect(null, "<html></html>".toByteArray()) shouldBe Charsets.UTF_8
    }

    @Test
    fun `bom перекрывает всё`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "тест".toByteArray(Charset.forName("UTF-16LE"))

        CharsetSniffer.detect("text/html; charset=utf-8", bytes) shouldBe Charsets.UTF_16LE
    }
}
