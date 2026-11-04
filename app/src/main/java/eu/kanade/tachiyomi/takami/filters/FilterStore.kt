package eu.kanade.tachiyomi.takami.filters

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Выбор фильтров, переживший перезапуск: один файл на все источники, ключ — хост.
 *
 * Битый или чужой файл не роняет приложение: начинаем с пустого состояния.
 */
object FilterStore {

    private const val FILE = "filters.json"
    private var dir: File? = null

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Хост → имя параметра → выбранные значения. */
    private val mapSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), ListSerializer(String.serializer())))

    /** Папка известна только после старта приложения; в тестах её подменяют. */
    fun init(filesDir: File) {
        dir = filesDir
    }

    /** Выбор источника: пустая карта, если файла нет или он не читается. */
    fun load(host: String): Map<String, List<String>> {
        val file = file() ?: return emptyMap()
        val root = runCatching {
            json.decodeFromString<Map<String, Map<String, List<String>>>>(file.readText())
        }.getOrNull() ?: return emptyMap()
        return root[host].orEmpty()
    }

    /** Записывает выбор одного источника, не трогая остальных. */
    fun save(host: String, values: Map<String, List<String>>) {
        val file = file() ?: return
        val current = runCatching {
            json.decodeFromString<Map<String, Map<String, List<String>>>>(file.readText())
        }.getOrDefault(emptyMap())
        val next = if (values.isEmpty()) current - host else current + (host to values)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, FILE + ".tmp")
            tmp.writeText(json.encodeToString(mapSerializer, next))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(json.encodeToString(mapSerializer, next))
            }
        }
    }

    private fun file(): File? {
        val target = dir ?: return null
        return File(target, FILE)
    }
}
