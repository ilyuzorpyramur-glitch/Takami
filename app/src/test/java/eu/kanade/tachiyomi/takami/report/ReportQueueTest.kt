package eu.kanade.tachiyomi.takami.report

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Очередь на диске: предел, атомарность и битый файл. */
class ReportQueueTest {

    @TempDir
    lateinit var dir: File

    private var counter = 0

    private fun event(): ReportEvent {
        counter++
        return ReportEvent(
            host = "site.example",
            step = "catalog",
            error = "network",
            health = "healthy",
            at = counter.toLong(),
        )
    }

    @Test
    fun `переполнение выбрасывает старые события`() {
        val queue = ReportQueue(dir)

        repeat(REPORT_QUEUE_MAX_EVENTS + 25) { queue.add(event()) }

        queue.size() shouldBe REPORT_QUEUE_MAX_EVENTS
        // Первыми ушли самые старые: в очереди остались последние.
        queue.all().first().at shouldBe 26L
        queue.all().last().at shouldBe (REPORT_QUEUE_MAX_EVENTS + 25).toLong()
    }

    @Test
    fun `очередь переживает перезапуск`() {
        ReportQueue(dir).add(event())

        ReportQueue(dir).size() shouldBe 1
    }

    @Test
    fun `запись атомарная — временного файла не остаётся`() {
        val queue = ReportQueue(dir)
        queue.add(event())

        val names = dir.list()?.toList().orEmpty()
        names.none { it.endsWith(".tmp") } shouldBe true
        names.contains("report-queue.json") shouldBe true
    }

    @Test
    fun `битый файл читается как пустая очередь`() {
        dir.mkdirs()
        File(dir, "report-queue.json").writeText("{ это не json")

        val queue = ReportQueue(dir)

        queue.size() shouldBe 0
        // И очередь продолжает работать: новое событие записывается.
        queue.add(event())
        queue.size() shouldBe 1
    }

    @Test
    fun `отправленные события убираются, остальные остаются`() {
        val queue = ReportQueue(dir)
        repeat(3) { queue.add(event()) }

        val batch = queue.peek(2)
        queue.remove(batch)

        queue.size() shouldBe 1
        queue.all().first().at shouldBe 3L
    }

    @Test
    fun `очистка стирает очередь целиком`() {
        val queue = ReportQueue(dir)
        repeat(5) { queue.add(event()) }

        queue.clear()

        queue.size() shouldBe 0
    }
}
