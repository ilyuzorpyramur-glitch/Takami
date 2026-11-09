package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Обёртка источника: номер, имя, язык и сборка моделей без сети. */
class VirtualMangaSourceTest {

    private val spec = MangaSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Источник",
        lang = "ru",
        catalog = MangaCatalogSpec(itemSelector = "div.card"),
    )

    @Test
    fun `номер источника тот же, что у реестра`() {
        val source = VirtualMangaSource(spec)

        source.id shouldBe MangaSourceId.of("site.example")
        source.name shouldBe "Источник"
        source.lang shouldBe "ru"
    }

    @Test
    fun `лента обновлений поддерживается`() {
        VirtualMangaSource(spec).supportsLatest shouldBe true
    }

    @Test
    fun `фильтры пустые и не падают`() {
        VirtualMangaSource(spec).getFilterList().size shouldBe 0
    }

    @Test
    fun `источник собирает адрес каталога по спеке`() {
        val source = VirtualMangaSource(spec)

        source.id shouldBe MangaSourceId.of(spec.host)
        source.id shouldBe (source.id)
    }
}
