package eu.kanade.tachiyomi.takami.news

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Кэш ленты: переживает перезапуск, битый файл даёт пустоту. */
class NewsCacheTest {

    @TempDir
    lateinit var files: File

    private val item = NewsItem(
        url = "https://example.test/news/one.html",
        title = "Заголовок записи",
        snippet = "Анонс записи",
        publishedAt = 1000L,
        source = "Сайт",
    )

    @Test
    fun `снимок сохраняется и читается`() {
        NewsCache.init(files)

        NewsCache.save(1L, NewsSnapshot(savedAt = 100L, items = listOf(item)))

        NewsCache.load(1L) shouldNotBeNull()
        NewsCache.load(1L)!!.items.single().url shouldBe item.url
        NewsCache.load(1L)!!.savedAt shouldBe 100L
    }

    @Test
    fun `перезапуск ничего не теряет`() {
        NewsCache.init(files)
        NewsCache.save(2L, NewsSnapshot(savedAt = 200L, items = listOf(item)))

        NewsCache.init(files)

        NewsCache.load(2L)!!.savedAt shouldBe 200L
    }

    @Test
    fun `битый файл читается как отсутствие кэша`() {
        NewsCache.init(files)
        File(files, "news").apply { mkdirs() }
        File(files, "news/3.json").writeText("{ не json }")

        NewsCache.load(3L).shouldBeNull()
    }

    @Test
    fun `после битого файла запись снова работает`() {
        NewsCache.init(files)
        File(files, "news").apply { mkdirs() }
        File(files, "news/4.json").writeText("мусор")

        NewsCache.save(4L, NewsSnapshot(savedAt = 300L, items = listOf(item)))

        NewsCache.load(4L)!!.savedAt shouldBe 300L
    }

    @Test
    fun `чужой источник не подмешивается`() {
        NewsCache.init(files)
        NewsCache.save(5L, NewsSnapshot(savedAt = 400L, items = listOf(item)))

        NewsCache.load(6L).shouldBeNull()
    }
}
