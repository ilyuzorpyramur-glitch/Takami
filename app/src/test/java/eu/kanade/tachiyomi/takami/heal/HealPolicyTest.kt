package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.spec.SpecJournalEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Правила лечения: каждый запрет — отдельная причина. */
class HealPolicyTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    /** Рабочий источник: проверок больше нуля, история есть, журнал знает о поломке. */
    private val healthy = HealState(checksPassed = 3, historyVersions = 2)

    private fun journal(vararg steps: String): List<SpecJournalEntry> = listOf(
        SpecJournalEntry(
            host = "example.test",
            fromVersion = 3,
            toVersion = 3,
            checksBefore = 4,
            checksAfter = 3,
            failedChecks = steps.toList(),
            reason = "деградация",
            at = now - 2 * 24 * hour,
        ),
    )

    @Test
    fun `источник только для чтения запрещён по домену`() {
        val allow = HealPolicy.allow("dreamerscast.com", SpecPart.CARD, healthy, journal("details"), now)

        allow shouldBe HealAllow.No("read_only")
    }

    @Test
    fun `источник только для чтения запрещён по ключу спеки`() {
        val allow = HealPolicy.allow("kodikdreamcast", SpecPart.CARD, healthy, journal("details"), now)

        allow shouldBe HealAllow.No("read_only")
    }

    @Test
    fun `сайт с приставкой тоже только для чтения`() {
        HealPolicy.allow("www.dreamerscast.com", SpecPart.CARD, healthy, journal("details"), now) shouldBe
            HealAllow.No("read_only")
    }

    @Test
    fun `видео не лечится`() {
        HealPolicy.allow("example.test", SpecPart.NONE, healthy, journal(), now) shouldBe
            HealAllow.No("video_owned")
    }

    @Test
    fun `источник без истории и без проверок не лечится`() {
        val neverWorked = HealState()

        HealPolicy.allow("example.test", SpecPart.CARD, neverWorked, journal("details"), now) shouldBe
            HealAllow.No("never_worked")
    }

    @Test
    fun `третья попытка в сутки запрещена`() {
        val state = healthy.copy(attemptsToday = HealPolicy.MAX_ATTEMPTS_PER_DAY)

        HealPolicy.allow("example.test", SpecPart.CARD, state, journal("details"), now) shouldBe
            HealAllow.No("attempts_today")
    }

    @Test
    fun `после провала ждут шесть часов`() {
        val state = healthy.copy(lastFailedAt = now - hour, failedInRow = 1)

        val allow = HealPolicy.allow("example.test", SpecPart.CARD, state, journal("details"), now)

        allow shouldBe HealAllow.No("cooldown", 5 * hour)
    }

    @Test
    fun `после двух провалов подряд ждут сутки`() {
        val state = healthy.copy(lastFailedAt = now - 2 * hour, failedInRow = 2)

        val allow = HealPolicy.allow("example.test", SpecPart.CARD, state, journal("details"), now)

        allow shouldBe HealAllow.No("cool_down_hard", 22 * hour)
    }

    @Test
    fun `после упора в защиту сайта сутки тишины`() {
        val state = healthy.copy(blockedAt = now - hour)

        val allow = HealPolicy.allow("example.test", SpecPart.CARD, state, journal("details"), now)

        allow shouldBe HealAllow.No("blocked", 23 * hour)
    }

    @Test
    fun `журнал без провала этого шага лечение закрывает`() {
        HealPolicy.allow("example.test", SpecPart.CARD, healthy, journal("catalog"), now) shouldBe
            HealAllow.No("no_breakage")
    }

    @Test
    fun `рабочий источник с зафиксированной поломкой лечится`() {
        HealPolicy.allow("example.test", SpecPart.CARD, healthy, journal("details"), now) shouldBe
            HealAllow.Yes
    }
}
