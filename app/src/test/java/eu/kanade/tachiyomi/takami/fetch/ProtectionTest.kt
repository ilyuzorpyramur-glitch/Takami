package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Разбор защиты по заголовкам: браузерная проверка, капча и отказ — разные вещи. */
class ProtectionTest {

    @Test
    fun `облако с отметкой о проверке — уровень A`() {
        val signal = Protection.of(403, "cloudflare", "challenge")

        signal.level shouldBe ProtectionLevel.BROWSER_CHECK
        signal.passed shouldBe false
    }

    @Test
    fun `заголовок сервера без отметки тоже узнаётся`() {
        listOf("cloudflare", "ddos-guard", "Qrator").forEach { server ->
            Protection.of(403, server, null, "<html>check</html>").level shouldBe
                ProtectionLevel.BROWSER_CHECK
        }
    }

    @Test
    fun `капча отличается от проверки браузера`() {
        val page = "<html><body><div class=\"g-recaptcha\"></div></body></html>"

        val signal = Protection.of(200, "nginx", null, page)

        signal.level shouldBe ProtectionLevel.CAPTCHA
    }

    @Test
    fun `отказ без признаков защиты — уровень C`() {
        Protection.of(403, "nginx", null, "").level shouldBe ProtectionLevel.BLOCKED
        Protection.of(451, "nginx", null, "").level shouldBe ProtectionLevel.BLOCKED
    }

    @Test
    fun `обычная страница и перегрузка защитой не считаются`() {
        Protection.of(200, "nginx", null, "<html>каталог</html>").level shouldBe ProtectionLevel.NONE
        Protection.of(429, "nginx", null, "").level shouldBe ProtectionLevel.NONE
    }

    @Test
    fun `cookie проверки разбирается в пары`() {
        val cookies = ChallengeCookies.parse("site.example", "cf_clearance=abc; __ddg1_=def")

        cookies.size shouldBe 2
        cookies.map { it.name } shouldBe listOf("cf_clearance", "__ddg1_")
        cookies.forEach { it.domain shouldBe "site.example" }
    }

    @Test
    fun `пометка требует проверки и снимается`() {
        val registry = ProtectionRegistry()

        registry.mark("site.example", ProtectionLevel.CAPTCHA, "капча", 1_000L)

        registry.status("site.example", 2_000L)?.level shouldBe ProtectionLevel.CAPTCHA
        registry.all(2_000L).size shouldBe 1
        registry.clear("site.example")
        registry.status("site.example", 2_000L) shouldBe null
    }

    @Test
    fun `старая пометка истекает`() {
        val registry = ProtectionRegistry(ttlMs = 100L)

        registry.mark("site.example", ProtectionLevel.BLOCKED, "отказ", 1_000L)

        registry.status("site.example", 1_050L)?.level shouldBe ProtectionLevel.BLOCKED
        registry.status("site.example", 1_500L) shouldBe null
    }
}
