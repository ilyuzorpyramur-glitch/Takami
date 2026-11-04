package eu.kanade.tachiyomi.takami.video.sniff

import java.io.File

/**
 * Память домена: какой слой сработал и когда.
 *
 * Снимок стоит до двенадцати секунд, поэтому платить их на каждой серии
 * незачем. Храним только домен, слой, счётчики и время: ни адресов, ни
 * query. Файл маленький и обрезается.
 */
class SniffMemo(
    private val dir: File?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Одна запись памяти. */
    data class Entry(
        val host: String,
        val layer: String,
        val ok: Int,
        val fail: Int,
        val streak: Int,
        val last: Long,
    )

    /** С какого слоя начинать: снимок, если вчера сработал. */
    fun preferred(host: String): String {
        val entry = read()[norm(host)] ?: return ""
        val fresh = entry.ok > 0 && clock() - entry.last < WEEK_MS
        return if (fresh) entry.layer else ""
    }

    /** Можно ли пробовать слой: две неудачи подряд закрывают его на сутки. */
    fun allowed(host: String, layer: String): Boolean {
        val entry = read()[norm(host)] ?: return true
        if (entry.layer != layer) return true
        if (entry.streak < 2) return true
        return clock() - entry.last >= DAY_MS
    }

    /** Запоминает исход попытки. */
    fun record(host: String, layer: String, success: Boolean) {
        val key = norm(host)
        if (key.isEmpty()) return
        val all = read()
        val previous = all[key]
        val same = previous?.layer == layer
        val entry = Entry(
            host = key,
            layer = layer,
            ok = (previous?.ok ?: 0) + if (success) 1 else 0,
            fail = (previous?.fail ?: 0) + if (success) 0 else 1,
            streak = if (success) 0 else (if (same) previous?.streak ?: 0 else 0) + 1,
            last = clock(),
        )
        all[key] = entry
        write(trim(all.values.toList()))
    }

    /** Сколько строк в памяти: нужно проверке и экрану. */
    fun size(): Int = read().size

    /** Стирает память целиком. */
    fun clear() {
        val target = file() ?: return
        runCatching { target.delete() }
    }

    private fun trim(entries: List<Entry>): List<Entry> =
        entries.sortedByDescending { it.last }.take(MAX_LINES)

    private fun read(): MutableMap<String, Entry> {
        val result = linkedMapOf<String, Entry>()
        val target = file() ?: return result
        if (!target.exists()) return result
        target.forEachLine { line ->
            val entry = parse(line) ?: return@forEachLine
            result[entry.host] = entry
        }
        return result
    }

    /** Строка памяти: домен, слой, три числа и время. Битые строки пропускаем. */
    private fun parse(line: String): Entry? {
        val parts = line.trim().split('|')
        if (parts.size != FIELDS) return null
        val host = norm(parts[0])
        if (host.isEmpty()) return null
        val layer = parts[1].trim()
        if (layer != SniffLayer.SNIFF && layer != SniffLayer.CONFIG) return null
        val ok = parts[2].toIntOrNull() ?: return null
        val fail = parts[3].toIntOrNull() ?: return null
        val streak = parts[4].toIntOrNull() ?: return null
        val last = parts[5].toLongOrNull() ?: return null
        return Entry(host, layer, ok, fail, streak, last)
    }

    private fun write(entries: List<Entry>) {
        val target = file() ?: return
        target.parentFile?.mkdirs()
        val text = entries.joinToString("\n") { entry ->
            listOf(entry.host, entry.layer, entry.ok, entry.fail, entry.streak, entry.last)
                .joinToString("|")
        }
        runCatching { target.writeText(if (text.isEmpty()) "" else text + "\n") }
    }

    private fun file(): File? = dir?.let { File(it, FILE) }

    private fun norm(host: String): String = host.trim().lowercase().removePrefix("www.")

    private companion object {
        private const val FILE = "sniff-memo.jsonl"
        private const val FIELDS = 6
        private const val MAX_LINES = 200
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val WEEK_MS = 7L * DAY_MS
    }
}
