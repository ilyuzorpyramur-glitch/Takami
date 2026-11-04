package eu.kanade.tachiyomi.takami.heal

import eu.kanade.tachiyomi.takami.CatalogSpec
import eu.kanade.tachiyomi.takami.DetailsSpec
import eu.kanade.tachiyomi.takami.EpisodesSpec
import eu.kanade.tachiyomi.takami.FilterSpec
import eu.kanade.tachiyomi.takami.PaginationSpec
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.VideoSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Правка одной части: чужие секции и поля не трогаются. */
class HealMergeTest {

    private val current = SiteSpec(
        host = "example.test",
        baseUrl = "https://example.test",
        name = "Прежнее имя",
        lang = "ru",
        catalog = CatalogSpec(
            itemSelector = "div.old-card",
            pagination = PaginationSpec(template = "page/{n}/"),
            search = null,
        ),
        details = DetailsSpec(title = "h3"),
        episodes = EpisodesSpec(itemSelector = "a.old-episode"),
        video = VideoSpec(kind = "iframe", iframeSelector = "iframe.old"),
        filters = listOf(FilterSpec(name = "Жанр", type = "check", key = "genre")),
        dubbings = listOf("Профессиональная"),
        iconUrl = "https://example.test/icon.png",
        iconPath = "/data/icon.png",
        version = 3,
        checksPassed = 3,
    )

    private val fresh = SiteSpec(
        host = "example.test",
        baseUrl = "https://example.test",
        name = "Новое имя",
        catalog = CatalogSpec(
            itemSelector = "div.new-card",
            pagination = PaginationSpec(template = "page/{n}.html"),
            search = null,
        ),
        details = DetailsSpec(title = "h1"),
        episodes = EpisodesSpec(itemSelector = "a.new-episode"),
        video = VideoSpec(kind = "links"),
        filters = listOf(FilterSpec(name = "Жанр", type = "check", key = "genre")),
        dubbings = emptyList(),
        iconUrl = "https://example.test/other.png",
        version = 3,
        checksPassed = 4,
    )

    @Test
    fun `каталог заменяется целиком, поиск остаётся прежним`() {
        val merged = HealMerge.merge(current, fresh, SpecPart.CATALOG)

        merged.catalog.itemSelector shouldBe "div.new-card"
        merged.catalog.pagination.template shouldBe "page/{n}.html"
        merged.catalog.search shouldBe current.catalog.search
    }

    @Test
    fun `пагинация заменяется, карточки остаются`() {
        val merged = HealMerge.merge(current, fresh, SpecPart.PAGINATION)

        merged.catalog.pagination.template shouldBe "page/{n}.html"
        merged.catalog.itemSelector shouldBe "div.old-card"
    }

    @Test
    fun `карточка заменяется, каталог остаётся`() {
        val merged = HealMerge.merge(current, fresh, SpecPart.CARD)

        merged.details.title shouldBe "h1"
        merged.catalog.itemSelector shouldBe "div.old-card"
    }

    @Test
    fun `серии заменяются, остальное остаётся`() {
        val merged = HealMerge.merge(current, fresh, SpecPart.EPISODES)

        merged.episodes.itemSelector shouldBe "a.new-episode"
        merged.details.title shouldBe "h3"
    }

    @Test
    fun `чужие поля не меняются никогда`() {
        for (part in listOf(SpecPart.CATALOG, SpecPart.CARD, SpecPart.EPISODES, SpecPart.PAGINATION)) {
            val merged = HealMerge.merge(current, fresh, part)

            merged.name shouldBe current.name
            merged.lang shouldBe current.lang
            merged.baseUrl shouldBe current.baseUrl
            merged.sourceId shouldBe current.sourceId
            merged.iconUrl shouldBe current.iconUrl
            merged.iconPath shouldBe current.iconPath
            merged.filters shouldBe current.filters
            merged.video shouldBe current.video
            merged.dubbings shouldBe current.dubbings
            merged.version shouldBe current.version
            merged.checksPassed shouldBe current.checksPassed
            merged.updatedAt shouldBe current.updatedAt
        }
    }

    @Test
    fun `слияние идемпотентно`() {
        for (part in listOf(SpecPart.CATALOG, SpecPart.CARD, SpecPart.EPISODES, SpecPart.PAGINATION)) {
            val once = HealMerge.merge(current, fresh, part)
            val twice = HealMerge.merge(once, fresh, part)

            twice shouldBe once
        }
    }

    @Test
    fun `пустая свежая секция оставляет спеку без изменений`() {
        val emptyFresh = SiteSpec(
            host = "example.test",
            baseUrl = "https://example.test",
            name = "Новое имя",
            catalog = CatalogSpec(itemSelector = ""),
            details = DetailsSpec(title = ""),
            episodes = EpisodesSpec(itemSelector = ""),
        )

        HealMerge.merge(current, emptyFresh, SpecPart.CATALOG) shouldBe current
        HealMerge.merge(current, emptyFresh, SpecPart.CARD) shouldBe current
        HealMerge.merge(current, emptyFresh, SpecPart.EPISODES) shouldBe current
        HealMerge.merge(current, fresh.copy(catalog = fresh.catalog.copy(pagination = PaginationSpec(template = ""))), SpecPart.PAGINATION) shouldBe
            current
    }

    @Test
    fun `видео не сливается вовсе`() {
        HealMerge.merge(current, fresh, SpecPart.NONE) shouldBe current
    }

    @Test
    fun `фильтры не теряются при замене каталога`() {
        val merged = HealMerge.merge(current, fresh.copy(filters = emptyList()), SpecPart.CATALOG)

        merged.filters shouldBe current.filters
    }
}
