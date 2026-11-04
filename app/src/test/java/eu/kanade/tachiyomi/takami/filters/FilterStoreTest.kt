package eu.kanade.tachiyomi.takami.filters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Хранение выбора: переживает перезапуск и битый файл. */
class FilterStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `выбор сохраняется и читается`() {
        FilterStore.init(dir)

        FilterStore.save("site.one", mapOf("genre" to listOf("comedy")))
        FilterStore.save("site.two", mapOf("year" to listOf("2024")))

        FilterStore.load("site.one") shouldBe mapOf("genre" to listOf("comedy"))
        FilterStore.load("site.two") shouldBe mapOf("year" to listOf("2024"))
    }

    @Test
    fun `перезапуск ничего не теряет`() {
        FilterStore.init(dir)
        FilterStore.save("site.one", mapOf("genre" to listOf("comedy")))

        // Новый запуск приложения: папка та же, объект новый.
        FilterStore.init(dir)
        FilterStore.load("site.one") shouldBe mapOf("genre" to listOf("comedy"))
    }

    @Test
    fun `битый файл не роняет приложение`() {
        FilterStore.init(dir)
        File(dir, "filters.json").writeText("{ не json }")

        FilterStore.load("site.one") shouldBe emptyMap()
    }

    @Test
    fun `после битого файла запись снова работает`() {
        FilterStore.init(dir)
        File(dir, "filters.json").writeText("мусор")

        FilterStore.save("site.one", mapOf("genre" to listOf("comedy")))

        FilterStore.load("site.one") shouldBe mapOf("genre" to listOf("comedy"))
    }

    @Test
    fun `пустой выбор убирает источник из файла`() {
        FilterStore.init(dir)
        FilterStore.save("site.one", mapOf("genre" to listOf("comedy")))

        FilterStore.save("site.one", emptyMap())

        FilterStore.load("site.one") shouldBe emptyMap()
        FilterStore.load("site.two") shouldBe emptyMap()
    }
}
