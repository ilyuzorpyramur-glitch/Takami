package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.takami.spec.SpecJournal
import eu.kanade.tachiyomi.takami.spec.SpecRollback
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Авто-откат: меньше проверок — возврат к прошлой версии и запись причины. */
class SpecRollbackTest {

    @TempDir
    lateinit var dir: File

    private fun spec(checks: Int): SiteSpec = SiteSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Сайт",
        checksPassed = checks,
        qualityPreference = "max",
        dubbings = listOf("AniLibria"),
        catalog = CatalogSpec(itemSelector = "div.item"),
    )

    @Test
    fun `деградация возвращает прошлую версию и пишет причину`() {
        val previous = spec(5)
        val candidate = spec(2)
        val journal = SpecJournal(dir)

        val decision = SpecRollback.apply(previous, candidate, listOf("Видео", "Серии"), journal)

        decision.rolledBack shouldBe true
        decision.spec shouldBe previous
        decision.spec.qualityPreference shouldBe "max"
        decision.spec.dubbings shouldBe listOf("AniLibria")
        val reason = decision.reason.orEmpty()
        reason.contains("Видео") shouldBe true
        reason.contains("Серии") shouldBe true

        val entries = journal.entries("site.example")
        entries.size shouldBe 1
        entries.first().reason shouldBe reason
        entries.first().checksBefore shouldBe 5
        entries.first().checksAfter shouldBe 2
        entries.first().failedChecks shouldBe listOf("Видео", "Серии")
    }

    @Test
    fun `столько же проверок — версия остаётся`() {
        val journal = SpecJournal(dir)

        val decision = SpecRollback.apply(spec(4), spec(4), emptyList(), journal)

        decision.rolledBack shouldBe false
        decision.spec.checksPassed shouldBe 4
        journal.entries().size shouldBe 0
    }

    @Test
    fun `больше проверок — версия остаётся`() {
        val journal = SpecJournal(dir)

        val decision = SpecRollback.apply(spec(3), spec(5), emptyList(), journal)

        decision.rolledBack shouldBe false
        decision.spec.checksPassed shouldBe 5
        journal.entries().size shouldBe 0
    }

    @Test
    fun `первая версия источника откату не подлежит`() {
        val decision = SpecRollback.apply(null, spec(1), listOf("Каталог"))

        decision.rolledBack shouldBe false
        decision.spec.checksPassed shouldBe 1
    }

    @Test
    fun `причина без списка упавших проверок тоже пишется`() {
        val journal = SpecJournal(dir)

        val decision = SpecRollback.apply(spec(4), spec(1), emptyList(), journal)

        val entries = journal.entries("site.example")
        entries.size shouldBe 1
        entries.first().reason.contains("1 из 4") shouldBe true
    }
}
