package eu.kanade.tachiyomi.takami.heal

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** План лечения: старшинство частей и отказ от видео. */
class HealPlannerTest {

    @Test
    fun `каталог и пагинация вместе дают каталог`() {
        HealPlanner.plan(listOf("catalog", "pagination")) shouldBe SpecPart.CATALOG
    }

    @Test
    fun `только пагинация даёт пагинацию`() {
        HealPlanner.plan(listOf("pagination")) shouldBe SpecPart.PAGINATION
    }

    @Test
    fun `детали дают карточку`() {
        HealPlanner.plan(listOf("details")) shouldBe SpecPart.CARD
    }

    @Test
    fun `эпизоды дают серии`() {
        HealPlanner.plan(listOf("episodes")) shouldBe SpecPart.EPISODES
    }

    @Test
    fun `видео не лечится`() {
        HealPlanner.plan(listOf("video")) shouldBe SpecPart.NONE
    }

    @Test
    fun `пустой список даёт отсутствие поломки`() {
        HealPlanner.plan(emptyList()) shouldBe SpecPart.NONE
    }

    @Test
    fun `каталог старше карточки`() {
        HealPlanner.plan(listOf("details", "catalog")) shouldBe SpecPart.CATALOG
    }

    @Test
    fun `карточка старше серий`() {
        HealPlanner.plan(listOf("episodes", "details")) shouldBe SpecPart.CARD
    }

    @Test
    fun `у каждой части есть свой шаг самопроверки`() {
        HealPlanner.stepOf(SpecPart.CATALOG) shouldBe "catalog"
        HealPlanner.stepOf(SpecPart.CARD) shouldBe "details"
        HealPlanner.stepOf(SpecPart.EPISODES) shouldBe "episodes"
        HealPlanner.stepOf(SpecPart.PAGINATION) shouldBe "pagination"
        HealPlanner.stepOf(SpecPart.NONE) shouldBe null
    }
}
