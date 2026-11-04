package eu.kanade.tachiyomi.takami.spec

import eu.kanade.tachiyomi.takami.SiteSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Что решили делать с только что сохранённой версией спеки. */
data class RollbackDecision(
    /** Какую версию оставить текущей. */
    val spec: SiteSpec,
    val rolledBack: Boolean,
    val reason: String? = null,
)

/** Запись журнала: когда и почему вернулись к прошлой версии. */
@Serializable
data class SpecJournalEntry(
    val host: String,
    val fromVersion: Int,
    val toVersion: Int,
    val checksBefore: Int,
    val checksAfter: Int,
    val failedChecks: List<String> = emptyList(),
    val reason: String,
    val at: Long = 0L,
)

/**
  * Журнал откатов: одна строка JSON на событие,
  * дописывается в конец.
 *
  * Битые строки пропускаются — журнал не должен мешать
  * работе источника.
 */
class SpecJournal(private val dir: File?) {

    fun write(entry: SpecJournalEntry) {
        val target = dir ?: return
        target.mkdirs()
        runCatching { File(target, FILE).appendText(json.encodeToString(entry) + "\n") }
    }

    /** События по источнику, свежие сверху. */
    fun entries(host: String? = null): List<SpecJournalEntry> {
        val target = dir ?: return emptyList()
        val file = File(target, FILE)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line -> runCatching { json.decodeFromString<SpecJournalEntry>(line) }.getOrNull() }
            .filter { host == null || it.host == host }
            .reversed()
    }

    private companion object {
        private const val FILE = "spec-journal.jsonl"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Решение по свежей версии спеки.
 *
  * Правка удачна, только если источник после неё проходит
  * не меньше проверок,
  * чем до неё. Иначе возвращается прошлая версия, а
  * причина (какая проверка
 * упала) уходит в журнал.
 */
object SpecRollback {

    fun apply(
        previous: SiteSpec?,
        candidate: SiteSpec,
        failedChecks: List<String> = emptyList(),
        journal: SpecJournal? = null,
        now: Long = System.currentTimeMillis(),
    ): RollbackDecision {
        if (previous == null) return RollbackDecision(candidate, false)
        if (candidate.checksPassed >= previous.checksPassed) return RollbackDecision(candidate, false)

        val reason = reasonOf(previous, candidate, failedChecks)
        journal?.write(
            SpecJournalEntry(
                host = candidate.host,
                fromVersion = previous.version,
                toVersion = candidate.version,
                checksBefore = previous.checksPassed,
                checksAfter = candidate.checksPassed,
                failedChecks = failedChecks,
                reason = reason,
                at = now,
            ),
        )
        return RollbackDecision(previous, true, reason)
    }

    private fun reasonOf(previous: SiteSpec, candidate: SiteSpec, failedChecks: List<String>): String {
        val counts = "пройдено " + candidate.checksPassed + " из " + previous.checksPassed + " проверок"
        if (failedChecks.isEmpty()) return "деградация: " + counts
        return "деградация: " + counts + ", упали: " + failedChecks.joinToString(", ")
    }
}
