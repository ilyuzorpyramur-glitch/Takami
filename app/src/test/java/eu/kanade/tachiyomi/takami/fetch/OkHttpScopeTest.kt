package eu.kanade.tachiyomi.takami.fetch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.fail

/**
 * OkHttpClient живёт только в пакете fetch.
 *
  * Если клиент появится в парсере или в UI, лимиты домена можно
  * будет обойти одной строкой —
 * этот тест такого не пропустит.
 */
class OkHttpScopeTest {

    @Test
    fun `OkHttpClient не выходит за пределы пакета fetch`() {
        val root = listOf(
            File("src/main/java/eu/kanade/tachiyomi/takami"),
            File("app/src/main/java/eu/kanade/tachiyomi/takami"),
        ).firstOrNull { it.isDirectory }
            ?: fail("Не нашёл каталог takami в рабочей папке тестов")

        val leaked = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.parentFile?.name == "fetch" }
            .filter { file -> file.readText().contains("OkHttpClient") }
            .map { it.name }
            .sorted()
            .toList()

        leaked shouldBe emptyList()
    }
}
