package eu.kanade.tachiyomi.takami.video.sniff

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Память домена: сроки, две неудачи и битые строки. */
class SniffMemoTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_700_000_000_000L

    private fun memo() = SniffMemo(dir) { now }

    @Test
    fun `две неудачи подряд закрывают слой на сутки`() {
        val memo = memo()
        memo.record("site.example", SniffLayer.SNIFF, false)
        memo.record("site.example", SniffLayer.SNIFF, false)

        memo.allowed("site.example", SniffLayer.SNIFF) shouldBe false

        now += 25L * 60L * 60L * 1_000L
        memo.allowed("site.example", SniffLayer.SNIFF) shouldBe true
    }

    @Test
    fun `успех делает снимок предпочтительным`() {
        val memo = memo()
        memo.record("site.example", SniffLayer.SNIFF, true)

        memo.preferred("site.example") shouldBe SniffLayer.SNIFF
    }

    @Test
    fun `битая строка не ломает чтение`() {
        dir.mkdirs()
        File(dir, "sniff-memo.jsonl").writeText("мусор без полей|1|2\n\n")
        val memo = memo()

        memo.record("site.example", SniffLayer.CONFIG, true)

        memo.size() shouldBe 1
        memo.preferred("site.example") shouldBe SniffLayer.CONFIG
    }

    @Test
    fun `в памяти нет адресов`() {
        val memo = memo()
        memo.record("site.example", SniffLayer.SNIFF, true)

        val text = File(dir, "sniff-memo.jsonl").readText()

        text.contains("http") shouldBe false
        text.contains("|" + SniffLayer.SNIFF + "|") shouldBe true
    }
}
