package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.browse.novel.source.globalsearch.GlobalNovelSearchScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.entries.novel.NovelScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.delay
import tachiyomi.domain.discovery.model.DiscoveryMediaType
import tachiyomi.i18n.MR
import java.io.Serializable

/**
 * Свайп-подбор: та же подборка, что и карусель главной, только по одной карточке.
 *
 * Вправо — «Посмотреть позже», влево — скрыть с отменой. Данные не дублируются:
 * экран читает те же потоки репозитория и проходит тот же отбор.
 */
class SwipeDiscoveryScreen(private val mediaKey: String) : Screen(), Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val media = remember(mediaKey) {
            DiscoveryMediaType.fromKey(mediaKey) ?: DiscoveryMediaType.ANIME
        }
        val model = rememberScreenModel { SwipeDiscoveryScreenModel(media, context.applicationContext) }
        LaunchedEffect(Unit) { model.start() }
        val state by model.state.collectAsStateWithLifecycle()
        val current = state.current

        LaunchedEffect(current?.id) {
            if (current != null) {
                delay(1_000)
                model.markExposed(current)
            }
        }
        LaunchedEffect(state.pendingOpen) {
            val open = state.pendingOpen ?: return@LaunchedEffect
            model.consumeOpen()
            navigator.push(
                when (open.mediaType) {
                    DiscoveryMediaType.ANIME -> AnimeScreen(open.entryId)
                    DiscoveryMediaType.MANGA -> MangaScreen(open.entryId)
                    DiscoveryMediaType.NOVEL -> NovelScreen(open.entryId)
                },
            )
        }

        val preview = state.preview
        if (preview != null) {
            DiscoveryPreviewSheet(
                item = preview,
                meta = state.previewMeta,
                isMetaLoading = state.previewLoading,
                coverMediaType = preview.mediaType,
                onDismiss = { model.dismissPreview() },
                onAdd = { model.dismissPreview(); model.watchLater(preview) },
                onFind = { model.dismissPreview(); navigator.push(globalSearch(preview)) },
                onHide = { model.dismissPreview(); model.hide(preview) },
            )
        }

        LaunchedEffect(state.hiddenSnackbarTitle) {
            if (state.hiddenSnackbarTitle != null) {
                delay(4_000)
                model.dismissHiddenSnackbar()
            }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.swipe_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { navigator.pop() }) {
                        Text(stringResource(MR.strings.swipe_close))
                    }
                }
                Text(
                    text = stringResource(MR.strings.swipe_counter, state.stack.remaining),
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        current != null -> SwipeCard(
                            item = current,
                            onHide = { model.hide(current) },
                            onLater = { model.watchLater(current) },
                            onOpen = { model.open(current) },
                            onLongClick = { model.openPreview(current) },
                        )
                        else -> SwipeEmptyState(
                            empty = state.empty,
                            onRefresh = { model.refresh() },
                            onRetry = { model.refresh() },
                        )
                    }
                }
                when {
                    state.hiddenSnackbarTitle != null -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.hiddenSnackbarTitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { model.undoHide() }) {
                            Text(stringResource(MR.strings.swipe_undo))
                        }
                    }
                    state.addedTitle != null -> Text(
                        text = stringResource(MR.strings.swipe_added, state.addedTitle.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.addFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(MR.strings.swipe_add_failed),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val item = current ?: return@TextButton
                                model.dismissAddResult()
                                navigator.push(globalSearch(item))
                            },
                        ) {
                            Text(stringResource(MR.strings.swipe_find_manual))
                        }
                        TextButton(onClick = { model.dismissAddResult() }) {
                            Text(stringResource(MR.strings.swipe_close))
                        }
                    }
                }
            }
        }
    }
}

/** Четыре честных пустых состояния: без объяснения экран не остаётся. */
@Composable
private fun SwipeEmptyState(
    empty: SwipeEmpty,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = when (empty) {
            SwipeEmpty.LOADING -> MR.strings.swipe_empty_loading_title
            SwipeEmpty.DONE -> MR.strings.swipe_empty_done_title
            SwipeEmpty.OFFLINE -> MR.strings.swipe_empty_offline_title
            SwipeEmpty.FAILED -> MR.strings.swipe_empty_failed_title
            SwipeEmpty.NONE -> MR.strings.swipe_empty_done_title
        }
        val body = when (empty) {
            SwipeEmpty.LOADING -> MR.strings.swipe_empty_loading_body
            SwipeEmpty.DONE -> MR.strings.swipe_empty_done_body
            SwipeEmpty.OFFLINE -> MR.strings.swipe_empty_offline_body
            SwipeEmpty.FAILED -> MR.strings.swipe_empty_failed_body
            SwipeEmpty.NONE -> MR.strings.swipe_empty_done_body
        }
        val action = when (empty) {
            SwipeEmpty.LOADING -> MR.strings.swipe_refresh
            SwipeEmpty.FAILED -> MR.strings.swipe_retry
            else -> MR.strings.swipe_refresh
        }
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = if (empty == SwipeEmpty.FAILED) onRetry else onRefresh) {
            Text(stringResource(action))
        }
    }
}

/** Ручной поиск по названию: честный путь, когда привязки к источнику нет. */
private fun globalSearch(item: tachiyomi.domain.discovery.model.DiscoverySuggestion): Screen =
    when (item.mediaType) {
        tachiyomi.domain.discovery.model.DiscoveryMediaType.ANIME -> GlobalAnimeSearchScreen(item.title)
        tachiyomi.domain.discovery.model.DiscoveryMediaType.MANGA -> GlobalMangaSearchScreen(item.title)
        tachiyomi.domain.discovery.model.DiscoveryMediaType.NOVEL -> GlobalNovelSearchScreen(item.title)
    }
