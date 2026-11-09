package eu.kanade.tachiyomi.ui.takami

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.manga.MangaForgeStrings

/**
 * Мастер манга-источника: ссылка → проверка → сохранение.
 *
 * Экран ничего не обещает заранее: показывает либо добавленный
 * источник, либо причину, по которой сайт не подошёл.
 */
class MangaForgeScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { MangaForgeScreenModel() }
        val state by model.state.collectAsStateWithLifecycle()
        val specs by model.specs.collectAsStateWithLifecycle()
        var link by remember { mutableStateOf("") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MangaForgeStrings.TITLE) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = MangaForgeStrings.BACK,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(MangaForgeStrings.HINT) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { model.build(link) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(MangaForgeStrings.BUILD)
                }
                if (state.busy) {
                    Row(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(MangaForgeStrings.BUILDING)
                    }
                }
                state.error?.let { reason ->
                    Text(text = reason, color = MaterialTheme.colorScheme.error)
                }
                state.done?.let { spec ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(MangaForgeStrings.DONE, style = MaterialTheme.typography.titleMedium)
                            Text(spec.name)
                            Text(spec.host)
                        }
                    }
                }
                if (specs.isEmpty()) {
                    Text(MangaForgeStrings.EMPTY)
                } else {
                    specs.forEach { spec ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(spec.name)
                                Text(spec.host, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { model.remove(spec.host) }) {
                                Text(MangaForgeStrings.REMOVE)
                            }
                        }
                    }
                }
            }
        }
    }
}
