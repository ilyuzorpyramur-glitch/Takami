package eu.kanade.tachiyomi.takami.news

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import kotlinx.serialization.json.Json
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Старые спеки читаются без миграции: новые поля с дефолтами. */
class NewsSpecCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val legacy = """
        {
          "host": "old.example",
          "baseUrl": "https://old.example",
          "name": "Старый источник",
          "catalog": { "itemSelector": "div.item" },
          "version": 3
        }
    """.trimIndent()

    @Test
    fun `спека без поля новостей читается, новости null`() {
        val spec = json.decodeFromString(SiteSpec.serializer(), legacy)

        spec.news.shouldBeNull()
        spec.newsCheckedAt shouldBe 0L
        spec.version shouldBe SPEC_VERSION
    }

    @Test
    fun `спека с новостями читается со всеми полями`() {
        val text = legacy.replace("\"version\": 3", "\"version\": 3," +
            " \"news\": { \"path\": \"/news/\", \"itemSelector\": \"div.n\" }, \"newsCheckedAt\": 123")

        val spec = json.decodeFromString(SiteSpec.serializer(), text)

        spec.news.shouldNotBeNull()
        spec.news!!.path shouldBe "/news/"
        spec.news!!.maxItems shouldBe 10
        spec.newsCheckedAt shouldBe 123L
    }

    @Test
    fun `версия схемы не менялась`() {
        SPEC_VERSION shouldBe 3
    }

    @Test
    fun `путь раздела живёт в спеке как обычное поле`() {
        val spec = SiteSpec(
            host = "example.test",
            baseUrl = "https://example.test",
            name = "Тест",
            catalog = CatalogSpec(itemSelector = "div.item"),
            news = NewsSpec(path = "/blog/", itemSelector = "div.post"),
        )

        spec.catalog.itemSelector shouldBe "div.item"
        spec.news!!.itemSelector shouldBe "div.post"
    }
}
