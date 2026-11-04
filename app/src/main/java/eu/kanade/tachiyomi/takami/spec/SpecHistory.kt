package eu.kanade.tachiyomi.takami.spec

import eu.kanade.tachiyomi.takami.SiteSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Сколько рабочих версий держим на источник: текущая и
 * две прошлых. */
const val SPEC_HISTORY_LIMIT = 3

/**
 * История последних рабочих версий каждой спеки.
 *
  * Лежит рядом со спеками в filesDir, поэтому переживает
  * перезапуск. В голове
  * списка — текущая рабочая версия, за ней прошлые. Спека
  * хранится целиком:
  * откат возвращает и путь к иконке, и озвучки, и качество,
  * и фиды — ничего не
 * собирается заново по кусочкам.
 */
class SpecHistory(private val dir: File?) {

    /** Записывает версию как рабочую, вытесняя самую старую. */
    fun record(spec: SiteSpec) {
        val all = read().toMutableMap()
        val list = all[spec.host].orEmpty().filterNot { sameContent(it, spec) }
        all[spec.host] = (listOf(spec) + list).take(SPEC_HISTORY_LIMIT)
        write(all)
    }

    fun versions(host: String): List<SiteSpec> = read()[host].orEmpty()

    fun latest(host: String): SiteSpec? = versions(host).firstOrNull()

    /**
      * Откат: снимает текущую версию и возвращает
      * предыдущую рабочую, делая её
     * текущей. null — откатывать некуда.
     */
    fun rollback(host: String): SiteSpec? {
        val all = read().toMutableMap()
        val list = all[host].orEmpty()
        if (list.isEmpty()) return null
        val back = list.drop(1)
        all[host] = back
        write(all)
        return back.firstOrNull()
    }

    /**
     * Источник удалён пользователем — его история больше
     * не нужна. */
    fun forget(host: String) {
        val all = read().toMutableMap()
        if (all.remove(host) != null) write(all)
    }

    private fun read(): Map<String, List<SiteSpec>> {
        val target = dir ?: return emptyMap()
        val file = File(target, FILE)
        if (!file.exists()) return emptyMap()
        // Битый файл — это не повод терять источники: читаем
        // как пустую историю.
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyMap())
    }

    private fun write(all: Map<String, List<SiteSpec>>) {
        val target = dir ?: return
        target.mkdirs()
        val text = json.encodeToString(serializer, all)
        runCatching { writeAtomically(File(target, FILE), text) }
    }

    private fun sameContent(left: SiteSpec, right: SiteSpec): Boolean =
        left.copy(updatedAt = 0L) == right.copy(updatedAt = 0L)

    private companion object {
        private const val FILE = "spec-history.json"
        private val serializer = MapSerializer(String.serializer(), ListSerializer(SiteSpec.serializer()))
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}

/**
 * Запись файла через временный файл и переименование.
 *
  * При сбое посреди записи старое содержимое остаётся
  * целым: переименование
 * подменяет файл целиком, а не переписывает его по частям.
 */
internal fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (tmp.renameTo(target)) return
    target.delete()
    if (!tmp.renameTo(target)) throw java.io.IOException("не удалось записать " + target.name)
}
