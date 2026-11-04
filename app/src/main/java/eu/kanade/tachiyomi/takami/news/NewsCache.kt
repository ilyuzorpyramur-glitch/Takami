package eu.kanade.tachiyomi.takami.news

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Кэш ленты на источник: files/news/<sourceId>.json, без миграций базы.
 *
 * Новости не трогают схему библиотеки: файл, снимок и время. Битый файл
 * читается как отсутствие кэша — блок просто не собирается.
 */
object NewsCache {

    private var dir: File? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Папка известна после старта приложения; в тестах подменяют. */
    fun init(filesDir: File) {
        dir = File(filesDir, "news")
    }

    /** Снимок источника; файла нет или он не читается — null. */
    fun load(sourceId: Long): NewsSnapshot? {
        val file = file(sourceId) ?: return null
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<NewsSnapshot>(file.readText()) }.getOrNull()
    }

    /** Записывает снимок через временный файл: срыв не портит прошлый. */
    fun save(sourceId: Long, snapshot: NewsSnapshot) {
        val file = file(sourceId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(json.encodeToString(snapshot))
            }
        }
    }

    private fun file(sourceId: Long): File? = dir?.let { File(it, sourceId.toString() + ".json") }
}
