package eu.kanade.tachiyomi.takami.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Итог проверки: отказ с причиной, а не ноль. */
class MangaCheckTest {

    @Test
    fun `все три шага дают успех`() {
        MangaCheck.of(catalog = 20, chapters = 12, pages = 18) shouldBe MangaCheck.OK
    }

    @Test
    fun `каждый провал называет свой шаг`() {
        MangaCheck.of(catalog = 0, chapters = 0, pages = 0) shouldBe MangaCheck.NO_CATALOG
        MangaCheck.of(catalog = 5, chapters = 0, pages = 0) shouldBe MangaCheck.NO_CHAPTERS
        MangaCheck.of(catalog = 5, chapters = 3, pages = 0) shouldBe MangaCheck.NO_PAGES
    }

    @Test
    fun `закрытый сайт отличается от пустого каталога`() {
        MangaCheck.of(catalog = 0, chapters = 0, pages = 0, blocked = true) shouldBe MangaCheck.BLOCKED
    }

    @Test
    fun `подпись не выдумывает чисел`() {
        MangaCheck.text(MangaCheck.NO_CHAPTERS, 5, 0, 0) shouldBe "главы не найдены"
        MangaCheck.text(MangaCheck.NO_PAGES, 5, 3, 0) shouldBe "страницы главы не найдены"
        MangaCheck.text(MangaCheck.OK, 7, 2, 9) shouldBe "каталог: 7, глав: 2, страниц: 9"
    }
}
