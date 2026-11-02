package eu.kanade.tachiyomi.takami.fetch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Дневной счётчик запросов к домену.
 *
  * Ключ включает дату, поэтому сброс в полночь получается сам
  * собой: новый день —
  * новый счётчик. Старые домены в файле не копятся: при смене дня
  * карта начинается с нуля.
 */
interface RequestBudget {
    suspend fun used(host: String): Int

    suspend fun remaining(host: String): Int

    /**
     * Учитывает запрос. false — потолок на сегодня уже выбран, в сеть
     * идти нельзя.
     */
    suspend fun consume(host: String): Boolean

    suspend fun reset()
}

/**
 * Содержимое файла счётчика. Поля с умолчаниями — чтобы старый
 * файл читался после правок.
 */
@Serializable
private data class BudgetFile(
    val day: String = "",
    val used: Map<String, Int> = emptyMap(),
)

/**
 * Счётчик в filesDir — отдельным JSON-файлом.
 *
  * Отдельный файл, а не общее хранилище предпочтений: счётчики по
  * доменам не должны
  * попадать в резервные копии пользовательских настроек и в общий
  * список ключей.
  * Запись атомарная: сначала временный файл, потом переименование
  * поверх старого.
 */
class FileRequestBudget(
    private val file: File,
    private val limit: Int = FetchLimits.DAILY_PER_HOST,
    private val day: () -> String = { currentDay() },
) : RequestBudget {

    constructor(context: Context) : this(File(context.filesDir, DEFAULT_PATH))

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun used(host: String): Int = mutex.withLock {
        val state = read()
        if (state.day == day()) state.used[host] ?: 0 else 0
    }

    override suspend fun remaining(host: String): Int = (limit - used(host)).coerceAtLeast(0)

    override suspend fun consume(host: String): Boolean = mutex.withLock {
        val today = day()
        val state = read()
        val counts = if (state.day == today) state.used else emptyMap()
        val current = counts[host] ?: 0
        if (current >= limit) return@withLock false
        write(BudgetFile(today, counts + (host to current + 1)))
        true
    }

    override suspend fun reset() {
        mutex.withLock {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun read(): BudgetFile = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext BudgetFile()
        runCatching { json.decodeFromString<BudgetFile>(file.readText()) }
            .getOrDefault(BudgetFile())
    }

    private suspend fun write(state: BudgetFile) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val text = json.encodeToString(state)
            val tmp = File(file.parentFile, file.name + TEMP_SUFFIX)
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Takami: не записал счётчик запросов" } }
        Unit
    }

    private companion object {
        private const val DEFAULT_PATH = "takami/network-budget.json"
        private const val TEMP_SUFFIX = ".tmp"
    }
}

private fun currentDay(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
