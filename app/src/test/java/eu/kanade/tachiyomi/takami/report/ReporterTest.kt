package eu.kanade.tachiyomi.takami.report

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Отправка: адрес, срок калибровки, размер пачки и неудача. */
class ReporterTest {

    @TempDir
    lateinit var dir: File

    private val hour = REPORT_INTERVAL_MS
    private val justBeforeEnd = CALIBRATION_UNTIL - hour
    private val afterEnd = CALIBRATION_UNTIL + hour

    private var calls = 0
    private val bodies = mutableListOf<String>()
    private val signatures = mutableListOf<String>()

    private fun reporter(
        endpoint: String = "https://collector.example/ingest",
        token: String = "signature",
        now: Long = justBeforeEnd,
        fail: Boolean = false,
    ): Reporter = Reporter(
        dir = dir,
        endpoint = endpoint,
        appVersion = "0.1",
        token = token,
        clock = { now },
        send = { _, body, signature ->
            calls++
            bodies += body
            signatures += signature
            if (fail) throw IllegalStateException("приёмник недоступен")
        },
    )

    /** События разные: иначе одинаковые сбои сложились бы в счётчик. */
    private fun fill(reporter: Reporter, count: Int) {
        repeat(count) { index ->
            reporter.note(
                ReportEvent(
                    host = "site.example",
                    step = "catalog",
                    error = "network" + index,
                    at = index.toLong(),
                ),
            )
        }
    }

    @Test
    fun `пустой адрес не делает ни одного запроса`(): Unit = runBlocking {
        val reporter = reporter(endpoint = "")
        fill(reporter, 5)

        val outcome = reporter.sendIfDue(justBeforeEnd)

        outcome shouldBe SendOutcome.NO_ENDPOINT
        calls shouldBe 0
        // Очередь копится локально и никуда не девается.
        reporter.queueSize() shouldBe 5
    }

    @Test
    fun `истёкший срок очищает очередь и не отправляет`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, 4)

        val outcome = reporter.sendIfDue(afterEnd)

        outcome shouldBe SendOutcome.EXPIRED
        calls shouldBe 0
        reporter.queueSize() shouldBe 0
        reporter.status(afterEnd).active shouldBe false
        reporter.status(afterEnd).enabled shouldBe false
        // Следующая попытка тоже молчит.
        reporter.sendIfDue(afterEnd + hour) shouldBe SendOutcome.EXPIRED
        calls shouldBe 0
    }

    @Test
    fun `пачка не больше двадцати событий`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, REPORT_BATCH_SIZE + 15)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.SENT

        calls shouldBe 1
        reporter.queueSize() shouldBe 15
        val sent = bodies.first()
        sent.split("\"host\"").size - 1 shouldBe REPORT_BATCH_SIZE
    }

    @Test
    fun `неудача сохраняет очередь и не повторяет сразу`(): Unit = runBlocking {
        val reporter = reporter(fail = true)
        fill(reporter, 30)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.FAILED

        reporter.queueSize() shouldBe 30
        // Второй заход в ту же минуту — не пора.
        reporter.sendIfDue(justBeforeEnd + 1_000L) shouldBe SendOutcome.NOT_DUE
        calls shouldBe 1
        reporter.status(justBeforeEnd).lastSentAt shouldBe justBeforeEnd
    }

    @Test
    fun `выключенная отправка молчит`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, 3)
        reporter.setEnabled(false)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.DISABLED
        calls shouldBe 0
        reporter.status(justBeforeEnd).enabled shouldBe false
    }

    @Test
    fun `счётчик отправленного растёт`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, REPORT_BATCH_SIZE)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.SENT
        reporter.status(justBeforeEnd).totalSent shouldBe REPORT_BATCH_SIZE
        reporter.queueSize() shouldBe 0
        // Через час отправлять нечего: очередь пуста.
        reporter.sendIfDue(justBeforeEnd + 1_000L) shouldBe SendOutcome.NOT_DUE
    }

    @Test
    fun `общий предел прекращает отправку`(): Unit = runBlocking {
        dir.mkdirs()
        File(dir, "report-state.json").writeText(
            "{\"enabled\":true,\"lastSentAt\":0,\"totalSent\":" + MAX_TOTAL_REPORTS + "}",
        )
        val reporter = reporter()
        fill(reporter, 5)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.LIMIT_REACHED
        calls shouldBe 0
        reporter.queueSize() shouldBe 5
    }

    @Test
    fun `толчок после сбоя срабатывает не чаще раза в десять минут`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, 2)

        reporter.flushSoon(justBeforeEnd) shouldBe SendOutcome.SENT
        calls shouldBe 1

        fill(reporter, 2)
        // Через минуту толчок ещё не положен.
        reporter.flushSoon(justBeforeEnd + 60_000L) shouldBe SendOutcome.NOT_DUE
        calls shouldBe 1
        // А через десять минут — снова можно.
        reporter.flushSoon(justBeforeEnd + REPORT_FLUSH_INTERVAL_MS) shouldBe SendOutcome.SENT
        calls shouldBe 2
    }

    @Test
    fun `толчок слушается предела и срока`(): Unit = runBlocking {
        dir.mkdirs()
        File(dir, "report-state.json").writeText(
            "{\"enabled\":true,\"lastSentAt\":0,\"totalSent\":" + MAX_TOTAL_REPORTS + "}",
        )
        val limited = reporter()
        fill(limited, 2)

        limited.flushSoon(justBeforeEnd) shouldBe SendOutcome.LIMIT_REACHED
        calls shouldBe 0

        val expired = reporter(now = afterEnd)
        fill(expired, 2)
        expired.flushSoon(afterEnd) shouldBe SendOutcome.EXPIRED
        calls shouldBe 0
    }

    @Test
    fun `после срока калибровки событие не пишется`(): Unit = runBlocking {
        val reporter = reporter(now = afterEnd)

        reporter.note(ReportEvent(host = "site.example", step = "video", error = "no_video", at = 1L))

        reporter.queueSize() shouldBe 0
        reporter.sendIfDue(afterEnd) shouldBe SendOutcome.EXPIRED
        calls shouldBe 0
    }

    @Test
    fun `запись события подталкивает отправку и складывает повторы`() {
        var kicks = 0
        val active = Reporter(
            dir = dir,
            endpoint = "https://collector.example/ingest",
            appVersion = "0.1",
            token = "signature",
            send = { _, _, _ -> },
            onQueued = { kicks++ },
        )

        repeat(3) { active.note(ReportEvent(host = "site.example", step = "video", error = "no_links", at = 5L)) }

        // Толчок просят на каждую запись, а событие в очереди одно.
        kicks shouldBe 3
        active.queueSize() shouldBe 1
        active.pending().single().count shouldBe 3
    }

    @Test
    fun `подпись передаётся в отправку`(): Unit = runBlocking {
        val reporter = reporter()
        fill(reporter, 2)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.SENT

        signatures shouldBe listOf("signature")
    }

    @Test
    fun `пустая подпись не отправляет и не теряет события`(): Unit = runBlocking {
        val reporter = reporter(token = "")
        fill(reporter, 3)

        reporter.sendIfDue(justBeforeEnd) shouldBe SendOutcome.NO_SIGNATURE
        calls shouldBe 0
        reporter.queueSize() shouldBe 3
    }
}
