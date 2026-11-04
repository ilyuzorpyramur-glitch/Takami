package eu.kanade.tachiyomi.takami.heal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Строка журнала лечения: одна попытка — одна строка.
 *
 * В строке только домен, часть, номер попытки, решение, код причины,
 * плюс-минус по шагам, триггер и время. Ни адресов с путями, ни названий,
 * ни текста страниц, ни служебных заголовков запросов.
 */
@Serializable
data class HealLogEntry(
    val host: String,
    val part: String,
    val attempt: Int = 0,
    val decision: String,
    val reason: String = "",
    /** Плюс-минус по шагам до правки: «catalog+;details-». */
    val before: String = "",
    /** Плюс-минус по шагам после правки. */
    val after: String = "",
    /** user — кнопка, sweep — прогон. */
    val trigger: String = "",
    val at: Long = 0L,
)

/**
 * Журнал лечения в filesDir/takami/heal.jsonl.
 *
 * Битые строки пропускаются, файл ограничен пятьюстами строками — старые
 * обрезаются. Журнал не мешает работе источника: любой сбой чтения даёт
 * пустое состояние.
 */
object HealLog {

    private const val FILE = "heal.jsonl"
    /** Строк в файле — больше не нужно: старые попытки ничего не решают. */
    const val MAX_LINES = 500

    private var dir: File? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Папка известна после старта приложения; в тестах её подменяют. */
    fun init(filesDir: File) {
        dir = File(filesDir, "takami")
    }

    private fun file(): File? = dir?.let { File(it, FILE) }

    /** Дописывает попытку и подрезает файл до [MAX_LINES] строк. */
    fun write(entry: HealLogEntry) {
        val target = file() ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(json.encodeToString(entry) + "\n")
            trim(target)
        }
    }

    /** Попытки источника, свежие сверху. */
    fun recent(host: String, limit: Int = 5): List<HealLogEntry> =
        entries().filter { it.host == host }.take(limit)

    /** Состояние для правила: попытки за сутки, провалы подряд, защита сайта. */
    fun state(host: String, now: Long): HealState {
        val rows = entries().filter { it.host == host && it.decision != "skipped" }
        if (rows.isEmpty()) return HealState()
        val dayAgo = now - HealPolicy.HARD_COOLDOWN_MS
        val today = rows.filter { it.at >= dayAgo }
        var failedInRow = 0
        for (row in rows) {
            if (row.decision == "rejected" || row.decision == "failed") failedInRow++ else break
        }
        val lastFailed = rows.firstOrNull { it.decision == "rejected" || it.decision == "failed" }
        val lastAttempt = rows.maxOf { it.at }
        val blocked = rows.filter { it.decision == "blocked" }.maxOfOrNull { it.at } ?: 0L
        return HealState(
            attemptsToday = today.size,
            lastAttemptAt = lastAttempt,
            lastFailedAt = lastFailed?.at ?: 0L,
            failedInRow = failedInRow,
            blockedAt = blocked,
        )
    }

    /** Разбирает файл целиком: битые строки пропускаются. */
    private fun entries(): List<HealLogEntry> {
        val target = file() ?: return emptyList()
        if (!target.exists()) return emptyList()
        return runCatching {
            target.readLines()
                .mapNotNull { line -> runCatching { json.decodeFromString<HealLogEntry>(line) }.getOrNull() }
                .reversed()
        }.getOrDefault(emptyList())
    }

    /** Оставляет последние [MAX_LINES] строк: файл не растёт вечно. */
    private fun trim(target: File) {
        val lines = target.readLines()
        if (lines.size <= MAX_LINES) return
        val kept = lines.takeLast(MAX_LINES)
        val tmp = File(target.parentFile, FILE + ".tmp")
        tmp.writeText(kept.joinToString("\n") + "\n")
        if (!tmp.renameTo(target)) tmp.delete()
    }
}
