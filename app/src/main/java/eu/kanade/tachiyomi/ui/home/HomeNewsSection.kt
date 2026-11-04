package eu.kanade.tachiyomi.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.takami.news.NewsItem
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.i18n.MR
import java.util.Date

/**
 * Новости на главной: горизонтальный блок из пяти карточек.
 *
 * Появляется, только если у источников есть настоящие записи: пустого блока,
 * заглушки и ошибки на главной не бывает. Тап открывает запись в браузере.
 */
@Composable
fun HomeNewsSection(section: eu.kanade.tachiyomi.ui.home.HomeHubSection) {
    // Спеки Takami — аниме-источники: блок живёт на вкладке аниме.
    if (section != eu.kanade.tachiyomi.ui.home.HomeHubSection.Anime) return
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val model = remember { NewsStateModel() }
    val items by model.items.collectAsStateWithLifecycle()
    val now = remember { Date().time }

    LaunchedEffect(Unit) {
        model.sync(opening = true)
    }

    if (items.isEmpty()) return

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.strings.news_title),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { navigator.push(NewsScreen()) }) {
                Text(stringResource(MR.strings.news_more))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.take(BLOCK_ITEMS), key = { it.url }) { item ->
                NewsItemCard(
                    item = item,
                    now = now,
                    onClick = { context.openInBrowser(item.url) },
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
            }
        }
    }
}

/** Сколько карточек в блоке на главной; остальное — на экране «Все новости». */
private const val BLOCK_ITEMS = 5
