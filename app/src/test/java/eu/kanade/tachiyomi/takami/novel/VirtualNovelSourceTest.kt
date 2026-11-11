package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Обёртка источника: номер, имя, язык, фильтры. */
class VirtualNovelSourceTest {

    private val spec = NovelSpec(
        host = "site.example",
        baseUrl = "https://site.example",
        name = "Источник",
        lang = "ru",
        catalog = NovelCatalogSpec(itemSelector = "div.card"),
    )

    @Test
    fun `номер источника тот же, что у реестра`() {
        val source = VirtualNovelSource(spec)

        source.id shouldBe NovelSourceId.of("site.example")
        source.name shouldBe "Источник"
        source.lang shouldBe "ru"
    }

    @Test
    fun `лента обновлений поддерживается`() {
        VirtualNovelSource(spec).supportsLatest shouldBe true
    }

    @Test
    fun `фильтры пустые и не падают`() {
        VirtualNovelSource(spec).getFilterList().size shouldBe 0
    }

    @Test
    fun `номер не зависит от порядка вызовов`() {
        NovelSourceId.of("site.example") shouldBe NovelSourceId.of("www.site.example")
    }
}
