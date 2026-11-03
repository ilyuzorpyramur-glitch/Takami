package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.takami.spec.SpecHistory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** История версий: предел в три записи, откат и сохранность полей. */
class SpecHistoryTest {

    @TempDir
    lateinit var dir: File

    private fun spec(checks: Int, name: String = "Сайт"): SiteSpec = SiteSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = name,
        checksPassed = checks,
        dubbings = listOf("AniLibria"),
        catalog = CatalogSpec(itemSelector = "div.item"),
    )

    @Test
    fun `после четырёх сохранений в истории три версии`() {
        val history = SpecHistory(dir)
        (1..4).forEach { history.record(spec(it)) }

        val versions = history.versions("site.example")

        versions.size shouldBe 3
        versions.map { it.checksPassed } shouldBe listOf(4, 3, 2)
    }

    @Test
    fun `откат возвращает прошлую версию со всеми полями`() {
        val history = SpecHistory(dir)
        val working = spec(5).copy(
            iconPath = "/files/icons/site.png",
            qualityPreference = "max",
            dubbings = listOf("AniLibria", "Студийная"),
        )
        history.record(working)
        history.record(spec(2, name = "Сломанный"))

        val back = history.rollback("site.example")

        back shouldBe working
        back?.iconPath shouldBe "/files/icons/site.png"
        back?.qualityPreference shouldBe "max"
        back?.dubbings shouldBe listOf("AniLibria", "Студийная")
        history.latest("site.example") shouldBe working
        // Перезапуск приложения: откат остался на диске.
        SpecHistory(dir).latest("site.example") shouldBe working
    }

    @Test
    fun `откатывать некуда на единственной версии`() {
        val history = SpecHistory(dir)
        history.record(spec(3))

        history.rollback("site.example") shouldBe null
        history.latest("site.example") shouldBe spec(3)
    }

    @Test
    fun `битый файл истории читается как пустая история`() {
        dir.mkdirs()
        File(dir, "spec-history.json").writeText("{ это не json")

        val history = SpecHistory(dir)

        history.versions("site.example") shouldBe emptyList()
        history.latest("site.example") shouldBe null
    }

    @Test
    fun `истории разных источников не смешиваются`() {
        val history = SpecHistory(dir)
        history.record(spec(4))
        history.record(spec(1).copy(host = "other.example"))

        history.versions("site.example").size shouldBe 1
        history.versions("other.example").size shouldBe 1
    }
}
