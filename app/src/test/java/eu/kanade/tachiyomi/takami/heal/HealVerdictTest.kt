package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.CheckResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Вердикт: строже общего счёта проверок. */
class HealVerdictTest {

    private fun check(key: String, ok: Boolean) = CheckResult(key, key, ok)

    private val before = listOf(
        check("catalog", true),
        check("pagination", true),
        check("details", false),
        check("episodes", true),
        check("video", true),
    )

    @Test
    fun `сломанный шаг починился, остальные равны — принять`() {
        val after = listOf(
            check("catalog", true),
            check("pagination", true),
            check("details", true),
            check("episodes", true),
            check("video", true),
        )

        HealVerdict.accept(before, after, SpecPart.CARD) shouldBe true
    }

    @Test
    fun `починился, но другой шаг сломался — отказ`() {
        val after = listOf(
            check("catalog", true),
            check("pagination", true),
            check("details", true),
            check("episodes", false),
            check("video", true),
        )

        HealVerdict.accept(before, after, SpecPart.CARD) shouldBe false
    }

    @Test
    fun `счёт равный, шаг всё ещё сломан — отказ`() {
        val after = listOf(
            check("catalog", true),
            check("pagination", false),
            check("details", false),
            check("episodes", true),
            check("video", true),
        )

        HealVerdict.accept(before, after, SpecPart.CARD) shouldBe false
    }

    @Test
    fun `пустой список после — отказ`() {
        HealVerdict.accept(before, emptyList(), SpecPart.CARD) shouldBe false
    }

    @Test
    fun `улучшение соседних шагов не мешает принять`() {
        val after = listOf(
            check("catalog", true),
            check("pagination", true),
            check("details", true),
            check("episodes", true),
            check("video", true),
        )

        HealVerdict.accept(before, after, SpecPart.CARD) shouldBe true
    }

    @Test
    fun `часть без шага не проверяется`() {
        HealVerdict.accept(before, before, SpecPart.NONE) shouldBe false
    }

    @Test
    fun `исчезнувший шаг считается сломанным`() {
        val after = listOf(check("details", true))

        HealVerdict.accept(before, after, SpecPart.CARD) shouldBe false
    }
}
