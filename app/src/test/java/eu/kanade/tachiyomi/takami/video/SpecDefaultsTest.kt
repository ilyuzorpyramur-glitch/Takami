package eu.kanade.tachiyomi.takami.video

import eu.kanade.tachiyomi.takami.HosterSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.spec.SPEC_VERSION
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** Спека версии 3 без новых полей читается и работает как раньше. */
class SpecDefaultsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val old = """
        {
          "host": "site.example",
          "baseUrl": "https://site.example",
          "name": "Пример",
          "version": 3,
          "catalog": { "itemSelector": "article" }
        }
    """.trimIndent()

    @Test
    fun `версия схемы осталась третьей`() {
        SPEC_VERSION shouldBe 3
    }

    @Test
    fun `поля обхода по умолчанию`() {
        val spec = json.decodeFromString(SiteSpec.serializer(), old)

        spec.video.maxPlayers shouldBe 8
        spec.video.maxRequests shouldBe 12
        spec.video.embedAttrs shouldBe
            listOf("src", "data-src", "data-iframe", "data-player", "data-href", "data")
        spec.video.iframeDepth shouldBe 2
        spec.video.hlsMaster shouldBe true
    }

    @Test
    fun `плеер без шаблонов читается`() {
        val hoster = json.decodeFromString(
            HosterSpec.serializer(),
            """{"name":"playerjs","urlPattern":"//player","kind":"playerjs"}""",
        )

        hoster.idRegex shouldBe null
        hoster.apiTemplate shouldBe null
    }
}
