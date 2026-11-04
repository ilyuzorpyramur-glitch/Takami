package eu.kanade.tachiyomi.ui.discovery

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.discovery.model.DiscoveryMediaType
import tachiyomi.domain.discovery.model.DiscoveryReleaseStatus
import tachiyomi.domain.discovery.model.DiscoveryRowType
import tachiyomi.domain.discovery.model.DiscoverySuggestion

/** Правила карточки без UI: шкала, статус, подпись источника, показ. */
class SwipeLogicTest {

    private fun card(index: Int) = DiscoverySuggestion(
        id = index.toLong(),
        mediaType = DiscoveryMediaType.ANIME,
        rowType = DiscoveryRowType.TREND,
        title = "Title " + index,
        cleanTitle = "title " + index,
        coverUrl = null,
        reason = null,
        seedTitle = null,
        provider = "shikimori_trend",
        score = 1.0,
        position = index.toLong(),
        createdAt = 0L,
    )

    @Test
    fun `шкала приводится к десяти, ноль и мусор — нет оценки`() {
        SwipeLogic.rating(8.44) shouldBe 8.4
        SwipeLogic.ratingText(8.0) shouldBe "8.0"
        SwipeLogic.rating(0.0) shouldBe null
        SwipeLogic.ratingText(null) shouldBe null
        SwipeLogic.rating(11.0) shouldBe null
    }

    @Test
    fun `число серий ноль значит нет данных`() {
        SwipeLogic.episodes(12) shouldBe 12
        SwipeLogic.episodes(0) shouldBe null
        SwipeLogic.episodes(null) shouldBe null
    }

    @Test
    fun `сырые статусы трёх провайдеров дают общий ключ`() {
        SwipeLogic.statusKey("ongoing") shouldBe DiscoveryReleaseStatus.ONGOING.key
        SwipeLogic.statusKey("released") shouldBe DiscoveryReleaseStatus.FINISHED.key
        SwipeLogic.statusKey("Currently Airing") shouldBe DiscoveryReleaseStatus.ONGOING.key
        SwipeLogic.statusKey("Finished Airing") shouldBe DiscoveryReleaseStatus.FINISHED.key
        SwipeLogic.statusKey("RELEASING") shouldBe DiscoveryReleaseStatus.ONGOING.key
        SwipeLogic.statusKey("NOT_YET_RELEASED") shouldBe DiscoveryReleaseStatus.ANONS.key
        SwipeLogic.statusKey("нечто") shouldBe null
        SwipeLogic.statusKey(null) shouldBe null
    }

    @Test
    fun `подпись источника только у знакомых провайдеров`() {
        SwipeLogic.sourceLabel("shikimori_trend") shouldBe "Shikimori"
        SwipeLogic.sourceLabel("jikan_trend") shouldBe "MAL"
        SwipeLogic.sourceLabel("anilist_trend") shouldBe "AniList"
        SwipeLogic.sourceLabel("mangaupdates") shouldBe ""
    }

    @Test
    fun `стек держит порядок, конец и окно предзагрузки`() {
        val cards = (1..5).map(::card)
        val stack = SwipeLogic.stack(cards)

        stack.current shouldBe cards.first()
        stack.remaining shouldBe 5
        stack.window(3) shouldBe cards.take(3)

        val two = stack.advance().advance()
        two.current shouldBe cards[2]
        two.rewind().current shouldBe cards[1]
        two.advance().advance().advance().advance().current shouldBe null
        two.remaining shouldBe 3
    }

    @Test
    fun `стек не берёт больше тридцати карточек`() {
        SwipeLogic.stack((1..40).map(::card)).cards.size shouldBe SwipeLogic.MAX_CARDS
    }

    @Test
    fun `показ засчитывается только с половины и секунды`() {
        SwipeLogic.countsAsExposure(0.9f, 1_200L) shouldBe true
        SwipeLogic.countsAsExposure(0.4f, 2_000L) shouldBe false
        SwipeLogic.countsAsExposure(0.9f, 400L) shouldBe false
    }
}
