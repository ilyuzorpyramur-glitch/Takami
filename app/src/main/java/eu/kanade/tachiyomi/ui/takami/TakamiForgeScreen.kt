package eu.kanade.tachiyomi.ui.takami

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest

/**
 * «Добавить источник по ссылке»: генератор внутри приложения.
 *
 * Вставил адрес → разбор → самопроверка → сохранить. Источник после этого
 * появляется в общем списке рядом с APK-расширениями.
 */
class TakamiForgeScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { TakamiForgeScreenModel() }

        TakamiForge.init(context)

        val state by screenModel.state.collectAsStateWithLifecycle()
        val saved by screenModel.saved.collectAsStateWithLifecycle()
        var url by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is ForgeEvent.Saved -> context.toast("Источник «${event.name}» добавлен")
                    is ForgeEvent.Removed -> context.toast("Источник «${event.name}» удалён")
                }
            }
        }

        ForgeContent(
            url = url,
            onUrlChange = { url = it },
            state = state,
            saved = saved,
            onBuild = { screenModel.build(url) },
            onSave = screenModel::save,
            onReset = screenModel::reset,
            onRemove = screenModel::remove,
            navigateUp = navigator::pop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeContent(
    url: String,
    onUrlChange: (String) -> Unit,
    state: ForgeState,
    saved: List<SiteSpec>,
    onBuild: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRemove: (SiteSpec) -> Unit,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить источник по ссылке") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Вставь адрес сайта — приложение разберёт вёрстку, соберёт источник " +
                        "и проверит его на себе. APK не собирается: источник живёт на устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес сайта") },
                    placeholder = { Text("https://example-anime.org") },
                    singleLine = true,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onBuild,
                        enabled = url.isNotBlank() && state !is ForgeState.Running,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("  Разобрать")
                    }
                    if (state !is ForgeState.Idle) {
                        OutlinedButton(onClick = onReset) { Text("Сбросить") }
                    }
                }
            }

            when (val current = state) {
                is ForgeState.Running -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text("Разбираю ${current.url}")
                    }
                }

                is ForgeState.Failed -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                                Text("  Не получилось", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(current.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is ForgeState.Ready -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(current.spec.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    current.spec.baseUrl + " · " + current.spec.engine,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    items(current.checks) { check ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (check.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = if (check.ok) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                "  ${check.title}: ${check.note}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = onSave,
                            enabled = current.checks.any { it.key == "catalog" && it.ok },
                        ) {
                            Text("Сохранить источник")
                        }
                    }
                }

                ForgeState.Idle -> Unit
            }

            if (saved.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text("Мои источники", style = MaterialTheme.typography.titleSmall)
                }
                items(saved) { spec ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                            Text(spec.host, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onRemove(spec) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
        }
    }
}
