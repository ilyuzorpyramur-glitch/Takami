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

    private fun reporter(
        endpoint: String = "https://collector.example/ingest",
        now: Long = justBeforeEnd,
        fail: Boolean = false,
    ): Reporter = Reporter(
        dir = dir,
        endpoint = endpoint,
        appVersion = "0.1",
        clock = { now },
        send = { _, body ->
            calls++
            bodies += body
            if (fail) throw IllegalStateException("приёмник недоступен")
        },
    )

    private fun fill(reporter: Reporter, count: Int) {
        repeat(count) { index ->
            reporter.note(
                ReportEvent(
                    host = "site.example",
                    step = "catalog",
                    error = "network",
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
        reporter.sendIfDue(justBeforeEnd + hour) shouldBe SendOutcome.EMPTY
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
}
