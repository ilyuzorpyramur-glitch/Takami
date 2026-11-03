package eu.kanade.tachiyomi.takami.report

import com.tadami.aurora.BuildConfig
import eu.kanade.tachiyomi.takami.fetch.Fetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * До какой даты идёт сбор: 2027-02-01T00:00:00Z, то есть конец
 * 31 января 2027 по UTC. После этого отправка прекращается в самом коде,
 * без обновления приложения.
 */
const val CALIBRATION_UNTIL = 1_801_440_000_000L

/** Сколько событий уходит одним запросом. */
const val REPORT_BATCH_SIZE = 20

/** Как часто пробуем отправить: не чаще раза в час. */
const val REPORT_INTERVAL_MS = 60L * 60L * 1_000L

/** Как часто может срабатывать толчок после сбоя. */
const val REPORT_FLUSH_INTERVAL_MS = 10L * 60L * 1_000L

/** Сколько отчётов отправляем за всё время калибровки. */
const val MAX_TOTAL_REPORTS = 2_000

/** Чем закончилась попытка отправки. */
enum class SendOutcome {
    /** Адрес не задан в сборке: отправка выключена целиком. */
    NO_ENDPOINT,

    /** Подпись не задана в сборке: приёмник закрыт, отправки нет. */
    NO_SIGNATURE,

    /** Срок калибровки прошёл: очередь очищена, отправки больше нет. */
    EXPIRED,

    /** Пользователь выключил отправку. */
    DISABLED,

    /** Общий предел отправленных отчётов достигнут. */
    LIMIT_REACHED,

    /** Срок между попытками ещё не прошёл. */
    NOT_DUE,

    /** Отправлять нечего. */
    EMPTY,

    SENT,

    /** Сайт-приёмник не принял: события остаются до следующего раза. */
    FAILED,
}

/** Что помним между запусками: согласие, время отправки и счётчик. */
@Serializable
data class ReporterState(
    val enabled: Boolean = true,
    val lastSentAt: Long = 0L,
    val totalSent: Int = 0,
    /** Когда срабатывал внеочередной толчок: он реже часа, но чаще раза в 10 минут. */
    val lastFlushAt: Long = 0L,
)

/**
 * Отправка отчётов пачкой раз в час.
 *
 * Три границы, за которые отправка не выходит: пустой адрес (нет сборки без
 * секрета), срок калибровки и общий предел отправленного. Неудача не теряет
 * события: они остаются в очереди, а следующая попытка будет не раньше чем
 * через час — без Storm-повторов.
 */
class Reporter(
    private val dir: File?,
    private val endpoint: String,
    private val appVersion: String,
    // Подпись приходит из сборки и дальше только передаётся в заголовок:
    // в журнал и в состояние она не попадает.
    private val token: String = BuildConfig.TELEMETRY_TOKEN,
    // По умолчанию отправка идёт общим сетевым слоем: те же лимиты домена,
    // что и у обычных запросов, отдельного пути наружу нет.
    private val send: suspend (String, String, String) -> Unit = { url, body, signature ->
        Fetcher.get().postReport(url, body, signature)
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Толчок отправки после записи события: запуск живёт там, где есть цикл,
    // а сроки и пределы остаются здесь.
    private val onQueued: (() -> Unit)? = null,
) {
    private val queue = ReportQueue(dir)

    /** Записывает событие. После срока калибровки не пишет ничего. */
    fun note(event: ReportEvent) {
        if (clock() >= CALIBRATION_UNTIL) return
        queue.add(event)
        // Свежий сбой — повод отправить сразу, а не ждать часа.
        onQueued?.invoke()
    }

    /** Что сейчас лежит в очереди. Нужно только для проверок и состояния. */
    fun pending(): List<ReportEvent> = queue.all()

    fun queueSize(): Int = queue.size()

    fun status(now: Long = clock()): ReportStatus {
        val state = readState()
        return ReportStatus(
            queued = queue.size(),
            totalSent = state.totalSent,
            lastSentAt = state.lastSentAt,
            enabled = state.enabled,
            active = now < CALIBRATION_UNTIL,
            deadline = CALIBRATION_UNTIL,
        )
    }

    fun setEnabled(value: Boolean) {
        writeState(readState().copy(enabled = value))
    }

    fun clearQueue() {
        queue.clear()
    }

    /** Одна попытка отправки. Возвращает, чем она кончилась. */
    suspend fun sendIfDue(now: Long = clock()): SendOutcome {
        // Срок проверяем здесь, а не в интерфейсе: флажок обходится, код — нет.
        if (now >= CALIBRATION_UNTIL) {
            queue.clear()
            writeState(readState().copy(enabled = false))
            return SendOutcome.EXPIRED
        }
        if (endpoint.isBlank()) return SendOutcome.NO_ENDPOINT
        // Без подписи приёмник ответит 401, поэтому не стучимся вовсе:
        // события остаются в очереди до сборки с секретом.
        if (token.isEmpty()) return SendOutcome.NO_SIGNATURE

        val state = readState()
        if (!state.enabled) return SendOutcome.DISABLED
        if (state.totalSent >= MAX_TOTAL_REPORTS) return SendOutcome.LIMIT_REACHED
        if (state.lastSentAt != 0L && now - state.lastSentAt < REPORT_INTERVAL_MS) {
            return SendOutcome.NOT_DUE
        }

        return attempt(now)
    }

    /**
     * Внеочередная отправка после сбоя.
     *
     * Калибровке нужны свежие данные, но не ценой лишних запросов: не чаще
     * раза в десять минут, и только пока идёт срок и не исчерпан предел.
     * Очередь здесь не чистится: после срока это делает плановая отправка.
     */
    suspend fun flushSoon(now: Long = clock()): SendOutcome {
        if (now >= CALIBRATION_UNTIL) return SendOutcome.EXPIRED
        if (endpoint.isBlank()) return SendOutcome.NO_ENDPOINT
        if (token.isEmpty()) return SendOutcome.NO_SIGNATURE
        val state = readState()
        if (!state.enabled) return SendOutcome.DISABLED
        if (state.totalSent >= MAX_TOTAL_REPORTS) return SendOutcome.LIMIT_REACHED
        if (state.lastFlushAt != 0L && now - state.lastFlushAt < REPORT_FLUSH_INTERVAL_MS) {
            return SendOutcome.NOT_DUE
        }
        writeState(state.copy(lastFlushAt = now))
        return attempt(now)
    }

    /** Общая часть обеих отправок: пачка, запрос и запись состояния. */
    private suspend fun attempt(now: Long): SendOutcome {
        val batch = queue.peek(REPORT_BATCH_SIZE)
        if (batch.isEmpty()) return SendOutcome.EMPTY

        val state = readState()
        val body = encodeBody(batch)
        val delivered = runCatching { send(endpoint, body, token) }.isSuccess
        // Время фиксируем в любом случае: иначе при недоступном приёмнике
        // попытки пошли бы подряд.
        val next = if (delivered) {
            queue.remove(batch)
            state.copy(
                lastSentAt = now,
                totalSent = state.totalSent + batch.size,
            )
        } else {
            state.copy(lastSentAt = now)
        }
        writeState(next)
        return if (delivered) SendOutcome.SENT else SendOutcome.FAILED
    }

    private fun encodeBody(events: List<ReportEvent>): String =
        json.encodeToString(Payload(events = events))

    private fun readState(): ReporterState {
        val target = dir ?: return ReporterState()
        val file = File(target, STATE_FILE)
        if (!file.exists()) return ReporterState()
        return runCatching { json.decodeFromString<ReporterState>(file.readText()) }
            .getOrDefault(ReporterState())
    }

    private fun writeState(state: ReporterState) {
        val target = dir ?: return
        target.mkdirs()
        runCatching { writeAtomic(File(target, STATE_FILE), json.encodeToString(state)) }
    }

    @Serializable
    private data class Payload(
        val version: Int = 1,
        val events: List<ReportEvent> = emptyList(),
    )

    private companion object {
        private const val STATE_FILE = "report-state.json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val serializer = ListSerializer(ReportEvent.serializer())
    }
}
