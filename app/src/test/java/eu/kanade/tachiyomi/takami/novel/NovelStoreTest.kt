package eu.kanade.tachiyomi.takami.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Спеки на диске: запись, чтение, удаление и битый файл. */
class NovelStoreTest {

    private fun spec(host: String) = NovelSpec(
        host = host,
        baseUrl = "https://" + host,
        name = host,
        catalog = NovelCatalogSpec(itemSelector = "div.card"),
    )

    @Test
    fun `записанная спека читается обратно`(@TempDir dir: File) {
        val store = NovelStore(dir)

        store.save(spec("site.example"))

        store.load("site.example")?.host shouldBe "site.example"
        store.list().size shouldBe 1
    }

    @Test
    fun `битый файл пропускается, список не падает`(@TempDir dir: File) {
        val store = NovelStore(dir)
        store.save(spec("good.example"))
        File(dir, "broken.novel.json").writeText("{")

        store.list().size shouldBe 1
    }

    @Test
    fun `удаление убирает источник`(@TempDir dir: File) {
        val store = NovelStore(dir)
        store.save(spec("gone.example"))

        store.delete("gone.example") shouldBe true
        store.list() shouldBe emptyList()
    }
}
