package eu.kanade.tachiyomi.ui.discovery

import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import tachiyomi.domain.category.anime.interactor.CreateAnimeCategoryWithName
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.anime.interactor.SetAnimeCategories
import tachiyomi.domain.category.manga.interactor.CreateMangaCategoryWithName
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.manga.interactor.SetMangaCategories
import tachiyomi.domain.category.novel.interactor.CreateNovelCategoryWithName
import tachiyomi.domain.category.novel.interactor.GetNovelCategories
import tachiyomi.domain.category.novel.interactor.SetNovelCategories
import eu.kanade.domain.discovery.service.DiscoveryPreferences
import eu.kanade.tachiyomi.data.discovery.DiscoveryCandidatePolicy
import eu.kanade.tachiyomi.data.discovery.DiscoveryLibraryAdder
import eu.kanade.tachiyomi.data.discovery.DiscoveryStackSelector
import eu.kanade.tachiyomi.data.discovery.TasteSignalRecorder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.discovery.model.DiscoveryMediaType
import tachiyomi.domain.discovery.model.DiscoverySignalType
import tachiyomi.domain.discovery.model.DiscoverySuggestion
import tachiyomi.domain.discovery.repository.DiscoveryRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

/** Что показать вместо карточки, когда стек пуст. */
internal enum class SwipeEmpty { NONE, LOADING, DONE, OFFLINE, FAILED }

internal data class SwipeUiState(
    val mediaType: DiscoveryMediaType = DiscoveryMediaType.ANIME,
    val stack: SwipeStack = SwipeStack(),
    val empty: SwipeEmpty = SwipeEmpty.LOADING,
    val hiddenSnackbarTitle: String? = null,
    val addedTitle: String? = null,
    val addFailed: Boolean = false,
    val adding: Boolean = false,
    val preview: DiscoverySuggestion? = null,
) {
    val current: DiscoverySuggestion? get() = stack.current
}

/**
 * Стек свайпов поверх существующей подборки.
 *
 * Своего источника данных нет: карточки приходят из тех же потоков
 * `DiscoveryRepository`, что и карусель главной, и проходят тот же отбор.
 */
internal class SwipeDiscoveryScreenModel(
    initialMedia: DiscoveryMediaType,
    private val context: Context,
    private val repository: DiscoveryRepository = Injekt.get(),
    private val preferences: DiscoveryPreferences = Injekt.get(),
    private val adder: DiscoveryLibraryAdder = Injekt.get(),
) : StateScreenModel<SwipeUiState>(SwipeUiState(mediaType = initialMedia)) {

    private val dismissed = mutableSetOf<String>()
    private val undoQueue = DiscoveryHideUndoQueue(screenModelScope)
    private var lastHidden: DiscoverySuggestion? = null
    private var firstEmission = true

    fun start() {
        screenModelScope.launchIO {
            val media = state.value.mediaType
            combine(
                repository.subscribe(media),
                repository.subscribeHidden(media),
                repository.subscribeConsumed(media),
                repository.subscribeBlacklist(media),
            ) { all, hidden, consumed, blacklist -> Source(all, hidden, consumed, blacklist) }
                .collectLatest { source ->
                    val policy = DiscoveryCandidatePolicy(
                        spec = preferences.genreFilterSpec(),
                        blacklistedTags = source.blacklist,
                    )
                    val cards = runCatching {
                        DiscoveryStackSelector.select(
                            items = source.all,
                            policy = policy,
                            hidden = source.hidden,
                            consumed = source.consumed,
                            dismissed = dismissed.toSet(),
                        )
                    }.getOrNull()
                    if (cards == null) {
                        mutableState.update { it.copy(empty = SwipeEmpty.FAILED) }
                        return@collectLatest
                    }
                    val stack = SwipeLogic.stack(cards)
                    mutableState.update { current ->
                        val keepIndex = if (cards.isEmpty()) 0 else current.stack.index.coerceAtMost(cards.size)
                        current.copy(
                            stack = stack.copy(index = keepIndex),
                            empty = if (cards.isEmpty()) {
                                if (firstEmission) SwipeEmpty.LOADING else SwipeEmpty.DONE
                            } else {
                                SwipeEmpty.NONE
                            },
                        )
                    }
                    firstEmission = false
                }
        }
    }

    /** Свайп вправо: «Посмотреть позже». */
    fun watchLater(item: DiscoverySuggestion) {
        if (state.value.adding) return
        mutableState.update { it.copy(adding = true, addedTitle = null, addFailed = false) }
        screenModelScope.launchIO {
            TasteSignalRecorder.record(repository, item, DiscoverySignalType.LIKE)
            val id = adder.addWithId(item.mediaType, item.title, item.provider)
            val categorized = id != null && assignLater(item.mediaType, id)
            mutableState.update {
                it.copy(
                    adding = false,
                    addedTitle = if (id != null) item.title else null,
                    addFailed = id == null || !categorized,
                )
            }
            advance()
        }
    }

    /** Свайп влево: скрыть с отменой. */
    fun hide(item: DiscoverySuggestion) {
        lastHidden = item
        dismissed += item.cleanTitle
        undoQueue.enqueue {
            TasteSignalRecorder.record(repository, item, DiscoverySignalType.HIDE)
            repository.hide(item.mediaType, item.cleanTitle)
        }
        advance()
        mutableState.update { it.copy(hiddenSnackbarTitle = item.title) }
    }

    fun undoHide() {
        val item = lastHidden ?: return
        lastHidden = null
        dismissed -= item.cleanTitle
        undoQueue.enqueue { repository.undoHide(item.mediaType, item.cleanTitle) }
        mutableState.update { it.copy(hiddenSnackbarTitle = null, stack = it.stack.rewind()) }
    }

    fun dismissHiddenSnackbar() = mutableState.update { it.copy(hiddenSnackbarTitle = null) }

    fun dismissAddResult() = mutableState.update { it.copy(addedTitle = null, addFailed = false) }

    fun recordClick(item: DiscoverySuggestion) {
        screenModelScope.launchIO {
            TasteSignalRecorder.record(repository, item, DiscoverySignalType.CLICK)
        }
    }

    /** Показ карточки: половина экрана и секунда — и только тогда засчитываем. */
    fun markExposed(item: DiscoverySuggestion) {
        screenModelScope.launchIO {
            runCatching { repository.markShown(item.mediaType, listOf(item.cleanTitle)) }
        }
    }

    fun preview(item: DiscoverySuggestion?) = mutableState.update { it.copy(preview = item) }

    fun advance() = mutableState.update { it.copy(stack = it.stack.advance()) }

    fun refresh() {
        mutableState.update { it.copy(empty = SwipeEmpty.LOADING) }
        firstEmission = true
    }

    private suspend fun assignLater(mediaType: DiscoveryMediaType, entryId: Long): Boolean {
        val name = context.contextStringResource(MR.strings.swipe_later_category)
        return when (mediaType) {
            DiscoveryMediaType.ANIME -> {
                Injekt.get<CreateAnimeCategoryWithName>().await(name)
                val target = Injekt.get<GetAnimeCategories>().await().firstOrNull { it.name == name }
                    ?: return false
                val current = Injekt.get<GetAnimeCategories>().await(entryId).map { it.id }
                Injekt.get<SetAnimeCategories>().await(entryId, (current + target.id).distinct())
                true
            }
            DiscoveryMediaType.MANGA -> {
                Injekt.get<CreateMangaCategoryWithName>().await(name)
                val target = Injekt.get<GetMangaCategories>().await().firstOrNull { it.name == name }
                    ?: return false
                val current = Injekt.get<GetMangaCategories>().await(entryId).map { it.id }
                Injekt.get<SetMangaCategories>().await(entryId, (current + target.id).distinct())
                true
            }
            DiscoveryMediaType.NOVEL -> {
                val categories = Injekt.get<GetNovelCategories>().await()
                val categoryId = categories.firstOrNull { it.name == name }?.id
                    ?: Injekt.get<CreateNovelCategoryWithName>()
                        .await(name, categories.size.toLong(), 0L)
                    ?: return false
                val current = Injekt.get<GetNovelCategories>().await(entryId).map { it.id }
                Injekt.get<SetNovelCategories>().await(entryId, (current + categoryId).distinct())
                true
            }
        }
    }

    private data class Source(
        val all: List<DiscoverySuggestion>,
        val hidden: Set<String>,
        val consumed: Set<String>,
        val blacklist: Set<String>,
    )
}
