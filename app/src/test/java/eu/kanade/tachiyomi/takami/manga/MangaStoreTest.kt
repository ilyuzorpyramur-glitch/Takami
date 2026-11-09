package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Хранилище спек: запись, чтение, битый файл, номер источника. */
class MangaStoreTest {

    @TempDir
    lateinit var dir: File

    private fun spec(host: String = "site.example") = MangaSpec(
        host = host,
        baseUrl = "https://" + host,
        name = "Источник",
        catalog = MangaCatalogSpec(itemSelector = "div.card"),
    )

    @Test
    fun `спека переживает запись и чтение`() {
        val store = MangaStore(dir)

        store.save(spec())

        val loaded = store.load("site.example")
        loaded?.host shouldBe "site.example"
        loaded?.catalog?.itemSelector shouldBe "div.card"
        loaded?.updatedAt shouldBe (loaded?.updatedAt ?: 0L)
    }

    @Test
    fun `домен с посторонними знаками не ломает имя файла`() {
        val store = MangaStore(dir)

        val file = store.save(spec("site.example:8080/test"))

        file.name.endsWith(MangaStore.SUFFIX) shouldBe true
        file.name.contains(":") shouldBe false
        file.name.contains("/") shouldBe false
    }

    @Test
    fun `битый файл пропускается при чтении списка`() {
        val store = MangaStore(dir)
        store.save(spec("one.example"))
        File(dir, "broken" + MangaStore.SUFFIX).writeText("{ не json")

        store.list().size shouldBe 1
        store.load("broken") shouldBe null
    }

    @Test
    fun `удаление убирает спеку`() {
        val store = MangaStore(dir)
        store.save(spec())

        store.delete("site.example") shouldBe true
        store.load("site.example") shouldBe null
    }

    @Test
    fun `номер источника постоянен и не пересекается с другим доменом`() {
        val first = MangaSourceId.of("site.example")
        val second = MangaSourceId.of("SITE.example")
        val other = MangaSourceId.of("other.example")

        first shouldBe second
        (first == other) shouldBe false
    }
}
