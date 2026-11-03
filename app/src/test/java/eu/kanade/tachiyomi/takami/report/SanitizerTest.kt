package eu.kanade.tachiyomi.takami.report

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** Очистка: из адреса остаются схема и хост, названия не попадают вовсе. */
class SanitizerTest {

    @Test
    fun `путь и query вырезаются, хост остаётся`() {
        val url = "https://site.example/series/12345/episode-7?token=secret&sig=abc"

        Sanitizer.originOf(url) shouldBe "https://site.example"
        Sanitizer.hostOf(url) shouldBe "site.example"
    }

    @Test
    fun `query без пути тоже вырезается`() {
        Sanitizer.originOf("http://site.example?token=secret") shouldBe "http://site.example"
    }

    @Test
    fun `якорь не остаётся в адресе`() {
        Sanitizer.originOf("https://site.example/catalog#top") shouldBe "https://site.example"
        Sanitizer.hostOf("https://www.site.example/catalog") shouldBe "site.example"
    }

    @Test
    fun `пустой и битый адрес не дают хоста`() {
        Sanitizer.hostOf(null) shouldBe ""
        Sanitizer.hostOf("") shouldBe ""
        Sanitizer.hostOf("просто текст") shouldBe ""
    }

    @Test
    fun `номер установки уходит только хешем`() {
        val raw = "5f1c6d2e-0000-4000-8000-abcdefabcdef"

        val hashed = Sanitizer.installIdHash(raw)

        hashed.length shouldBe 32
        hashed shouldNotBe raw
        hashed.contains(raw) shouldBe false
        // Разные установки различимы, одинаковые — совпадают.
        Sanitizer.installIdHash(raw) shouldBe hashed
        Sanitizer.installIdHash(raw + "x") shouldNotBe hashed
    }

    @Test
    fun `поле отчёта остаётся одной короткой строкой`() {
        val text = "Первая строка\nВторая строка" + "я".repeat(200)

        val field = Sanitizer.field(text)

        field.contains('\n') shouldBe false
        field.contains('\r') shouldBe false
        (field.length <= 64) shouldBe true
    }
}
