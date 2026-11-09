package eu.kanade.tachiyomi.takami.manga

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Спеки манга-источников на устройстве: один файл на домен.
 *
 * Хранилище намеренно простое: каталог filesDir, имя файла — домен,
 * содержимое — JSON спеки. Никуда не отправляется.
 */
class MangaStore(
    private val dir: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    },
) {

    fun save(spec: MangaSpec): File {
        if (!dir.exists()) dir.mkdirs()
        val file = fileOf(spec.host)
        file.writeText(json.encodeToString(spec.copy(updatedAt = System.currentTimeMillis())))
        return file
    }

    fun load(host: String): MangaSpec? {
        val file = fileOf(host)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<MangaSpec>(file.readText()) }.getOrNull()
    }

    /** Все спеки каталога; битый файл пропускается, а не роняет список. */
    fun list(): List<MangaSpec> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) } ?: return emptyList()
        return files.sortedBy { it.name }
            .mapNotNull { file -> runCatching { json.decodeFromString<MangaSpec>(file.readText()) }.getOrNull() }
    }

    fun delete(host: String): Boolean = fileOf(host).delete()

    fun fileOf(host: String): File = File(dir, safeName(host) + SUFFIX)

    private fun safeName(host: String): String =
        host.lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(80)

    companion object {
        const val SUFFIX = ".manga.json"
    }
}
