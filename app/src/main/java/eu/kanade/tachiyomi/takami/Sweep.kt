package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.takami.heal.Healer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
  * Прогон списка сайтов через генератор: то же, что раньше
  * делал стенд.
 *
 * Список берётся из файла: сайты не зашиты в код, иначе список из
 * отчёта и список в прогоне разошлись бы при первой же правке.
  * Один сайт за раз и пауза между сайтами: тридцать три
  * сайта подряд
 * без пауз — это уже не проверка, а нагрузка.
 *
  * Прогон можно прервать и продолжить: записанные строки
  * не повторяются.
 */
class Sweep(
    private val forge: Forge = Forge(),
    private val pause: Long = PAUSE_MS,
) {

    /**
     * Итог одного сайта. В отчёт идёт только то, что не жалко
     * показать. */
    data class Outcome(
        val host: String,
        val url: String,
        /** Каталог открылся — источник в принципе рабочий. */
        val catalogOk: Boolean,
        /** Все проверки прошли: каталог, карточка, серии, видео. */
        val ok: Boolean,
        val name: String,
        val engine: String,
        val checks: List<CheckResult>,
        val reason: String,
        val ms: Long,
        /** Качества, найденные у сайта: пусто — ось не определена. */
        val qualities: List<String> = emptyList(),
        /** Озвучки, найденные у сайта: пусто — ось не определена. */
        val dubbings: List<String> = emptyList(),
        /** Плеер, через который отдаётся видео. */
        val hoster: String = "",
        /** Источник лечился во время прогона: попытка была и чем кончилась. */
        val healed: Boolean = false,
        /** Код исхода лечения; пусто — лечения не было. */
        val healReason: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("host", host)
            put("url", url)
            put("ok", ok)
            put("catalogOk", catalogOk)
            put("name", name)
            put("engine", engine)
            put("ms", ms)
            put("reason", reason)
            put("healed", healed)
            put("healReason", healReason)
            // Две оси списками-строками: качество и озвучка не
            // смешиваются.
            put("qualities", qualities.joinToString(","))
            put("dubbings", dubbings.joinToString(","))
            put("hoster", hoster)
            put("checks", JSONArray().apply {
                checks.forEach { check ->
                    put(
                        JSONObject().apply {
                            put("key", check.key)
                            put("ok", check.ok)
                            // Заметка проверки — короткая фраза без
                            // названий тайтлов.
                            put("note", check.note.take(NOTE_LIMIT))
                        },
                    )
                }
            })
        }
    }

    /**
      * Прогоняет [sites] по очереди и дописывает строку в [out]
      * после каждого сайта.
     *
      * Дописывание после каждого сайта — чтобы падение на
      * середине не стоило
      * всего прогона: продолжение подхватит начатое с того
      * же файла.
     */
    suspend fun run(
        sites: List<String>,
        out: File,
        onProgress: (done: Int, total: Int, outcome: Outcome) -> Unit = { _, _, _ -> },
    ): List<Outcome> = withContext(Dispatchers.IO) {
        val queue = sites
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val done = readDone(out)
        val outcomes = mutableListOf<Outcome>()
        var index = 0

        for (url in queue) {
            val host = hostOf(url)
            if (host.isEmpty() || host in done) continue
            index++
            val started = System.currentTimeMillis()
            val outcome = attempt(url, host, started)
            append(out, outcome)
            outcomes += outcome
            onProgress(index, queue.size, outcome)
            // Последняя пауза не нужна: прогон закончился.
            if (index < queue.size) delay(pause)
        }
        outcomes
    }

    private suspend fun attempt(url: String, host: String, started: Long): Outcome {
        val result = runCatching { forge.build(url) }
        val ms = System.currentTimeMillis() - started
        return result.fold(
            onSuccess = { built ->
                when (built) {
                    is ForgeResult.Built -> {
                        val catalog = built.checks.firstOrNull { it.key == "catalog" }
                        val checksFine = built.checks.isNotEmpty() && built.checks.all { it.ok }
                        // Прогон — явное действие пользователя: сломанный источник
                        // можно лечить прямо здесь, по общим правилам.
                        var healed = false
                        var healReason = ""
                        if (!checksFine) {
                            val saved = TakamiForge.specs.value.firstOrNull { it.host == host }
                            if (saved != null) {
                                val result = Healer().heal(saved, "sweep")
                                healed = result.healed
                                healReason = result.reason
                            }
                        }
                        Outcome(
                            host = host,
                            url = url,
                            catalogOk = catalog?.ok == true,
                            ok = checksFine,
                            name = built.spec.name,
                            engine = built.spec.engine,
                            checks = built.checks,
                            reason = "",
                            ms = ms,
                            qualities = built.qualities,
                            dubbings = built.dubbings,
                            hoster = built.hoster,
                            healed = healed,
                            healReason = healReason,
                        )
                    }
                    is ForgeResult.Failure -> Outcome(
                        host = host,
                        url = url,
                        catalogOk = false,
                        ok = false,
                        name = "",
                        engine = "",
                        checks = emptyList(),
                        // Причина уже сформулирована генератором,
                        // обрезаем по границе слова.
                        reason = built.reason.take(REASON_LIMIT),
                        ms = ms,
                    )
                }
            },
            onFailure = { error ->
                Outcome(
                    host = host,
                    url = url,
                    catalogOk = false,
                    ok = false,
                    name = "",
                    engine = "",
                    checks = emptyList(),
                    reason = (error.message ?: error.javaClass.simpleName).take(REASON_LIMIT),
                    ms = ms,
                )
            },
        )
    }

    /** Хосты, уже записанные в файл: их прогон не повторяет. */
    private fun readDone(out: File): Set<String> {
        if (!out.exists()) return emptySet()
        return out.readLines()
            .mapNotNull { line ->
                line.trim().takeIf { it.startsWith("{") }
                    ?.let { runCatching { JSONObject(it).optString("host") }.getOrNull() }
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun append(out: File, outcome: Outcome) {
        out.parentFile?.mkdirs()
        out.appendText(outcome.toJson().toString() + "\n")
    }

    companion object {
        /**
         * Файл прогона в filesDir: забирается через adb, в сеть не
         * уходит. */
        const val FILE = "sweep.jsonl"

        /** Файл со списком сайтов в filesDir: одна строка — один адрес. */
        const val LIST = "sites.txt"

        /**
         * Пауза между сайтами: сайт не должен видеть очередь
         * запросов. */
        const val PAUSE_MS = 1500L

        private const val NOTE_LIMIT = 120
        private const val REASON_LIMIT = 160

        fun hostOf(url: String): String = url
            .substringAfter("://", url)
            .substringBefore('/')
            .removePrefix("www.")
            .lowercase()
            .trim()
    }
}
