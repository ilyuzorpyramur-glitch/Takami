package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.spec.SpecJournalEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Журнал лечения: формат, обрезка, битые строки, состояние для правила. */
class HealLogTest {

    @TempDir
    lateinit var files: File

    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun entry(decision: String, at: Long, part: String = "CARD"): HealLogEntry = HealLogEntry(
        host = "example.test",
        part = part,
        attempt = 1,
        decision = decision,
        reason = if (decision == "accepted") "" else "verdict",
        before = "catalog+;details-",
        after = "catalog+;details+",
        trigger = "user",
        at = at,
    )

    @Test
    fun `запись читается обратно со всеми полями`() {
        HealLog.init(files)
        HealLog.write(entry("accepted", now))

        val row = HealLog.recent("example.test").single()

        row.part shouldBe "CARD"
        row.decision shouldBe "accepted"
        row.before shouldBe "catalog+;details-"
        row.after shouldBe "catalog+;details+"
        row.trigger shouldBe "user"
        row.at shouldBe now
    }

    @Test
    fun `формат строки в файле — только разрешённые поля`() {
        HealLog.init(files)
        HealLog.write(entry("rejected", now))

        val line = File(File(files, "takami"), "heal.jsonl").readText()
        val keys = line.substringAfter('{').substringBefore('}')
            .split(',').map { it.trim().trim('"').substringBefore(':') }

        keys.toSet() shouldBe setOf("host", "part", "attempt", "decision", "reason", "before", "after", "trigger", "at")
    }

    @Test
    fun `битые строки пропускаются`() {
        HealLog.init(files)
        val target = File(File(files, "takami"), "heal.jsonl")
        target.parentFile?.mkdirs()
        target.writeText("мусор\n{\"host\":\n")
        HealLog.write(entry("accepted", now))

        HealLog.recent("example.test").size shouldBe 1
    }

    @Test
    fun `файл обрезается до пятисот строк`() {
        HealLog.init(files)
        repeat(HealLog.MAX_LINES + 5) { HealLog.write(entry("rejected", now + it)) }

        val target = File(File(files, "takami"), "heal.jsonl")
        target.readLines().filter { it.isNotBlank() }.size shouldBe HealLog.MAX_LINES
        // Свежие строки остались: последние пять попыток читаются.
        HealLog.recent("example.test").size shouldBe 5
    }

    @Test
    fun `состояние считает попытки за сутки`() {
        HealLog.init(files)
        HealLog.write(entry("accepted", now - 2 * 24 * hour))
        HealLog.write(entry("rejected", now - hour))

        val state = HealLog.state("example.test", now)

        state.attemptsToday shouldBe 1
        state.lastFailedAt shouldBe now - hour
        state.failedInRow shouldBe 1
    }

    @Test
    fun `два провала подряд дают счётчик для жёсткой паузы`() {
        HealLog.init(files)
        HealLog.write(entry("rejected", now - 3 * hour))
        HealLog.write(entry("failed", now - 2 * hour))

        HealLog.state("example.test", now).failedInRow shouldBe 2
    }

    @Test
    fun `успех сбрасывает счётчик провалов`() {
        HealLog.init(files)
        HealLog.write(entry("rejected", now - 3 * hour))
        HealLog.write(entry("accepted", now - 2 * hour))

        HealLog.state("example.test", now).failedInRow shouldBe 0
    }

    @Test
    fun `упор в защиту запоминается отдельно`() {
        HealLog.init(files)
        HealLog.write(entry("blocked", now - hour))

        val state = HealLog.state("example.test", now)

        state.blockedAt shouldBe now - hour
        state.failedInRow shouldBe 0
    }

    @Test
    fun `чужой источник не мешает состоянию`() {
        HealLog.init(files)
        HealLog.write(entry("rejected", now))

        HealLog.state("other.test", now) shouldBe HealState()
    }

    @Test
    fun `старые строки журнала откатов читаются без новых полей`() {
        val old = "{\"host\":\"example.test\",\"fromVersion\":3,\"toVersion\":3," +
            "\"checksBefore\":4,\"checksAfter\":3,\"reason\":\"деградация\",\"at\":0}"

        val parsed = SpecJournalEntryJson.decode(old)

        parsed.part shouldBe ""
        parsed.attempt shouldBe 0
        parsed.decision shouldBe ""
    }

    /** Разбор строки старого журнала: те же настройки, что у SpecJournal. */
    private object SpecJournalEntryJson {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun decode(line: String): SpecJournalEntry = json.decodeFromString(SpecJournalEntry.serializer(), line)
    }
}
