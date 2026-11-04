package eu.kanade.tachiyomi.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import java.util.Date

/**
 * Экран «Все новости»: плоский список всех записей из кэша.
 *
 * Кнопка «Обновить» — единственное место с интерактивным запросом.
 * Пусто — честная подпись и кнопка проверки, без ошибок.
 */
class NewsScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val model = remember { NewsStateModel() }
        val items by model.items.collectAsStateWithLifecycle()
        var busy by remember { mutableStateOf(false) }
        val now = remember { Date().time }

        LaunchedEffect(Unit) {
            model.sync(opening = false)
        }

        val refresh: () -> Unit = {
            busy = true
            scope.launch {
                model.sync(opening = false, force = true)
                busy = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.news_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = refresh, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 6.dp))
                    }
                    Text(stringResource(MR.strings.news_refresh))
                }

                if (items.isEmpty()) {
                    Text(stringResource(MR.strings.news_empty), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = refresh) {
                        Text(stringResource(MR.strings.news_retry))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.url }) { item ->
                            NewsItemCard(
                                item = item,
                                now = now,
                                onClick = { context.openInBrowser(item.url) },
                            )
                        }
                    }
                }
            }
        }
    }
}
