package eu.kanade.tachiyomi.takami.fetch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest
import kotlin.math.absoluteValue

/** Найденная запись вместе с телом страницы. */
data class CachedHit(
    val entry: CacheEntry,
    val page: FetchedPage,
)

/**
 * Содержимое файла индекса. Поля с умолчаниями: старый файл
 * читается после правок.
 */
@Serializable
private data class CacheIndex(
    val entries: Map<String, CacheEntry> = emptyMap(),
)

/**
 * Дисковый кэш страниц.
 *
  * Лежит во внутреннем кэше приложения: внешнее хранилище может
  * исчезнуть и
  * требует прав. Своих запросов в сеть не делает — это только
  * хранилище, весь
 * обмен идёт через [Fetcher], иначе лимиты домена обходились бы мимо.
 *
  * Тело каждой страницы — отдельный файл, пишется атомарно:
  * временный файл и
 * переименование. Индекс — один JSON рядом с телами.
 */
class HttpCache(
    private val dir: File,
    private val maxBytes: Long = FetchLimits.CACHE_MAX_BYTES.toLong(),
    private val maxEntries: Int = FetchLimits.CACHE_INDEX_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.cacheDir, DIR_NAME))

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Ключ записи: адрес и referer.
     *
      * Referer входит в ключ, потому что часть сайтов отдаёт по нему
      * разную
      * разметку: страница серии, открытая из каталога и из карточки,
      * может
      * отличаться. Склеивать их в одну запись — значит иногда
      * показывать чужую.
     */
    fun key(url: String, referer: String?): String {
        val material = url.trim() + "\n" + (referer?.trim() ?: "")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Срок жизни конкретного источника: сутки плюс-минус разброс. */
    fun ttlFor(host: String, seed: String = host): Long {
        val spread = FetchLimits.CACHE_TTL_JITTER_MS
        // Разброс детерминированный: один сайт всегда получает один
        // и тот же срок,
        // а разные устройства расходятся между собой и не бьют сайт
        // одновременно.
        val offset = (seed.hashCode().toLong().absoluteValue % (spread + 1)) - spread / 2
        return FetchLimits.CACHE_TTL_MS + offset
    }

    suspend fun isFresh(entry: CacheEntry, at: Long = now()): Boolean {
        val ttl = if (entry.ttlMs > 0L) entry.ttlMs else FetchLimits.CACHE_TTL_MS.toLong()
        return at - entry.fetchedAt < ttl
    }

    /**
     * Свежая или просроченная запись вместе с телом. Отмечает
     * обращение для LRU.
     */
    suspend fun lookup(key: String, at: Long = now()): CachedHit? = mutex.withLock {
        val current = index()[key] ?: return@withLock null
        val body = withContext(Dispatchers.IO) {
            val file = File(dir, current.fileName)
            val readable = !current.fileName.isBlank() && file.isFile
            val text = if (readable) file.readText() else null
            // Обрезанный файл считаем промахом: пустая страница всё равно не пройдёт ворота.
            text?.takeIf { it.isNotEmpty() }
        }
        if (body == null) {
            removeLocked(key)
            return@withLock null
        }
        val touched = current.copy(lastUsedAt = at)
        val snapshot = index().toMutableMap().also { it[key] = touched }
        writeIndex(snapshot)
        CachedHit(
            entry = touched,
            page = FetchedPage(
                html = body,
                url = touched.url,
                charset = touched.charset,
                code = 200,
            ),
        )
    }

    /**
     * Кладёт страницу в кэш: тело отдельным файлом, запись в
     * индексе.
     */
    suspend fun store(key: String, entry: CacheEntry, html: String) {
        mutex.withLock {
            val fileName = key + BODY_SUFFIX
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                writeAtomically(File(dir, fileName), html)
            }
            val ready = entry.copy(
                fileName = fileName,
                sizeBytes = html.toByteArray().size.toLong(),
                bodyHash = Conditional.contentHash(html.toByteArray()),
                lastUsedAt = now(),
            )
            val snapshot = index().toMutableMap().also { it[key] = ready }
            writeIndex(snapshot)
            trimLocked()
        }
    }

    /**
      * Сайт ответил «не изменилось»: продлеваем срок и обновляем
      * метки.
     * Тело остаётся тем же файлом — ничего не перезаписываем.
     */
    suspend fun confirm(key: String, at: Long, etag: String?, lastModified: String?) {
        mutex.withLock {
            val current = index()[key] ?: return@withLock
            val updated = current.copy(
                fetchedAt = at,
                lastUsedAt = at,
                etag = etag ?: current.etag,
                lastModified = lastModified ?: current.lastModified,
            )
            val snapshot = index().toMutableMap().also { it[key] = updated }
            writeIndex(snapshot)
        }
    }

    /** Сколько сейчас занято: для диагностики и тестов. */
    suspend fun sizeBytes(): Long = mutex.withLock { index().values.sumOf { it.sizeBytes } }

    suspend fun entries(): Int = mutex.withLock { index().size }

    /** Полная очистка: кнопка сброса в диагностике и тесты. */
    suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() }
            }
            cached = null
        }
    }

    @Volatile
    private var cached: MutableMap<String, CacheEntry>? = null

    /**
     * Индекс читается один раз; битый файл считается пустым, а не
     * поводом упасть.
     */
    private suspend fun index(): Map<String, CacheEntry> {
        cached?.let { return it }
        val loaded = readIndex()
        cached = loaded.toMutableMap()
        return loaded
    }

    private suspend fun readIndex(): Map<String, CacheEntry> = withContext(Dispatchers.IO) {
        val file = File(dir, INDEX_NAME)
        if (!file.isFile) return@withContext emptyMap()
        runCatching { json.decodeFromString<CacheIndex>(file.readText()).entries }
            .getOrElse {
                logcat(LogPriority.WARN) { "Takami: индекс кэша повреждён" }
                emptyMap()
            }
    }

    private suspend fun writeIndex(entries: Map<String, CacheEntry>) {
        cached = entries.toMutableMap()
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            writeAtomically(File(dir, INDEX_NAME), json.encodeToString(CacheIndex(entries)))
        }
    }

    /**
     * Удаление записи вместе с телом: используется, когда тела уже
     * нет.
     */
    private suspend fun removeLocked(key: String) {
        val current = cached?.get(key) ?: return
        if (current.fileName.isNotBlank()) {
            withContext(Dispatchers.IO) { File(dir, current.fileName).delete() }
        }
        val snapshot = (cached ?: return).toMutableMap().also { it.remove(key) }
        writeIndex(snapshot)
    }

    /**
      * Вытеснение самых давно использованных: сначала по числу
      * записей, затем по
      * объёму. Первым уходит то, к чему дольше не обращались, —
      * свежий экран при
     * этом остаётся на месте.
     */
    private suspend fun trimLocked() {
        val map = cached ?: return
        var changed = false

        if (map.size > maxEntries) {
            val excess = map.entries.sortedBy { it.value.lastUsedAt }.take(map.size - maxEntries)
            excess.forEach { entry ->
                deleteBody(entry.value)
                map.remove(entry.key)
                changed = true
            }
        }

        var total = map.values.sumOf { it.sizeBytes }
        while (total > maxBytes && map.isNotEmpty()) {
            val oldest = map.entries.minByOrNull { it.value.lastUsedAt } ?: break
            deleteBody(oldest.value)
            map.remove(oldest.key)
            total -= oldest.value.sizeBytes
            changed = true
        }

        if (changed) writeIndex(map)
    }

    private suspend fun deleteBody(entry: CacheEntry) {
        if (entry.fileName.isBlank()) return
        withContext(Dispatchers.IO) { File(dir, entry.fileName).delete() }
    }

    /**
     * Временный файл и переименование: обрезанного файла на месте
     * страницы не будет.
     */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        private const val DIR_NAME = "takami-http"
        private const val INDEX_NAME = "index.json"
        private const val BODY_SUFFIX = ".html"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
