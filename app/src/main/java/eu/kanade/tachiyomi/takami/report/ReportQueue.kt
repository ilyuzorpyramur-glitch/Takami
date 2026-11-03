package eu.kanade.tachiyomi.takami.report

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Сколько событий держим локально. */
const val REPORT_QUEUE_MAX_EVENTS = 500

/** Сколько занимает очередь на диске. */
const val REPORT_QUEUE_MAX_BYTES = 512 * 1024

/**
 * Очередь отчётов на диске.
 *
 * Живёт в filesDir, поэтому переживает перезапуск и не теряется, когда
 * отправить прямо сейчас нельзя. Пределы жёсткие: при переполнении уходят
 * самые старые события, иначе очередь росла бы вместе с числом сбоев.
 */
class ReportQueue(private val dir: File?) {

    fun all(): List<ReportEvent> = read()

    fun size(): Int = read().size

    /** Добавляет событие, вытесняя старое при переполнении. */
    fun add(event: ReportEvent) {
        val next = (read() + event).takeLast(REPORT_QUEUE_MAX_EVENTS)
        write(trimBySize(next))
    }

    /** Первые события очереди, без удаления: отправка может не удаться. */
    fun peek(count: Int): List<ReportEvent> = read().take(count)

    /** Убирает отправленные события. */
    fun remove(sent: List<ReportEvent>) {
        if (sent.isEmpty()) return
        val keys = sent.map { it.host + "|" + it.step + "|" + it.at + "|" + it.error }.toSet()
        val left = read().filterNot { event ->
            (event.host + "|" + event.step + "|" + event.at + "|" + event.error) in keys
        }
        write(left)
    }

    fun clear() {
        val target = dir ?: return
        runCatching { File(target, FILE).delete() }
    }

    private fun trimBySize(events: List<ReportEvent>): List<ReportEvent> {
        var current = events
        while (current.isNotEmpty() && encode(current).toByteArray().size > REPORT_QUEUE_MAX_BYTES) {
            current = current.drop(1)
        }
        return current
    }

    private fun read(): List<ReportEvent> {
        val target = dir ?: return emptyList()
        val file = File(target, FILE)
        if (!file.exists()) return emptyList()
        // Битый файл — не повод падать: очередь просто пустая.
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun write(events: List<ReportEvent>) {
        val target = dir ?: return
        target.mkdirs()
        runCatching { writeAtomic(File(target, FILE), encode(events)) }
    }

    private fun encode(events: List<ReportEvent>): String =
        json.encodeToString(serializer, events)

    private companion object {
        private const val FILE = "report-queue.json"
        private val serializer = ListSerializer(ReportEvent.serializer())
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Запись через временный файл: при сбое прежняя очередь остаётся целой. */
internal fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (tmp.renameTo(target)) return
    target.delete()
    if (!tmp.renameTo(target)) throw java.io.IOException("не удалось записать " + target.name)
}
