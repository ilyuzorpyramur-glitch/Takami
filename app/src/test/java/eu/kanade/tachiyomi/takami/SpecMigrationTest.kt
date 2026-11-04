package eu.kanade.tachiyomi.takami

import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import eu.kanade.tachiyomi.takami.spec.SpecMigration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Старые файлы спек: чтение без потерь и повторный запуск без изменений. */
class SpecMigrationTest {

    private val legacy = """
        [
          {
            "host": "old.example",
            "baseUrl": "https://old.example",
            "name": "Старый источник",
            "iconPath": "/data/user/0/app/files/takami/icons/old.example.png",
            "qualityPreference": "max",
            "dubbings": ["AniLibria", "AnimeVost"],
            "nsfw": true,
            "catalog": { "itemSelector": "div.short", "latestPath": "/novinki/" },
            "episodes": { "itemSelector": "a.serie", "linkSelector": "a.serie" },
            "video": { "kind": "iframe", "iframeSelector": "iframe" }
          }
        ]
    """.trimIndent()

    @Test
    fun `файл без версии читается со всеми полями`() {
        val result = SpecMigration.migrate(legacy)

        result.changed shouldBe true
        result.specs.size shouldBe 1
        val spec = result.specs.first()
        spec.version shouldBe SPEC_VERSION
        spec.host shouldBe "old.example"
        spec.baseUrl shouldBe "https://old.example"
        spec.name shouldBe "Старый источник"
        spec.iconPath shouldBe "/data/user/0/app/files/takami/icons/old.example.png"
        spec.qualityPreference shouldBe "max"
        spec.dubbings shouldBe listOf("AniLibria", "AnimeVost")
        spec.nsfw shouldBe true
        spec.catalog.itemSelector shouldBe "div.short"
        spec.catalog.latestPath shouldBe "/novinki/"
        spec.episodes.itemSelector shouldBe "a.serie"
        spec.video.kind shouldBe "iframe"
    }

    @Test
    fun `лишние поля не мешают чтению`() {
        val text = """
            [
              {
                "host": "new.example",
                "baseUrl": "https://new.example",
                "name": "Новый",
                "version": ${SPEC_VERSION},
                "futureField": { "a": 1 },
                "catalog": { "itemSelector": "div.item", "smartPagination": true }
              }
            ]
        """.trimIndent()

        val result = SpecMigration.migrate(text)

        result.specs.size shouldBe 1
        result.specs.first().name shouldBe "Новый"
        result.changed shouldBe false
    }

    @Test
    fun `повторная миграция ничего не меняет`() {
        val first = SpecMigration.migrate(legacy)
        val second = SpecMigration.migrate(SpecMigration.encode(first.specs))

        second.changed shouldBe false
        second.specs shouldBe first.specs
    }

    @Test
    fun `мусор вместо файла не роняет чтение`() {
        val result = SpecMigration.migrate("{ это не json")

        result.specs shouldBe emptyList()
        result.reason shouldBe "файл спек не разобрать"
    }
}
