package eu.kanade.tachiyomi.ui.takami

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge

/**
 * Экран «Фильтры источника»: элементы идут сверху вниз, подписи по-русски,
 * выбранное состояние видно сразу — как в эталоне Anilibria.
 *
 * Источник без фильтров честно пишет «У источника нет фильтров» — без пустой панели.
 */
class FilterScreen(private val host: String? = null) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { FilterScreenModel() }
        val editing by screenModel.editing.collectAsStateWithLifecycle()
        val draft by screenModel.draft.collectAsStateWithLifecycle()
        val sources = TakamiForge.specs.collectAsStateWithLifecycle().value

        // Экран открыт сразу на конкретном источнике — из списка в мастере.
        LaunchedEffect(Unit) {
            host?.let { target ->
                sources.firstOrNull { it.host == target }?.let(screenModel::open)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Фильтры источника") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            val current = editing
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current == null) {
                    SourceList(sources, onOpen = screenModel::open)
                } else {
                    FilterEditor(
                        spec = current,
                        draft = draft,
                        screenModel = screenModel,
                        onDone = {
                            screenModel.applyChoice()
                            navigator.pop()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun SourceList(sources: List<SiteSpec>, onOpen: (SiteSpec) -> Unit) {
        if (sources.isEmpty()) {
            Text("Источников пока нет: добавьте источник через мастера.", style = MaterialTheme.typography.bodyMedium)
            return
        }
        Text("Выберите источник", style = MaterialTheme.typography.titleMedium)
        for (spec in sources) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(spec) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (spec.filters.isEmpty()) "нет фильтров" else "фильтров: " + spec.filters.size,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun FilterEditor(
        spec: SiteSpec,
        draft: AnimeFilterList,
        screenModel: FilterScreenModel,
        onDone: () -> Unit,
    ) {
        if (spec.filters.isEmpty()) {
            Text(
                text = "У источника нет фильтров",
                style = MaterialTheme.typography.bodyLarge,
            )
            return
        }

        Text(spec.name, style = MaterialTheme.typography.titleLarge)
        draft.list.forEachIndexed { index, filter ->
            when (filter) {
                is AnimeFilter.Header -> Text(
                    text = filter.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is AnimeFilter.CheckBox -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { screenModel.toggle(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = filter.state, onCheckedChange = { screenModel.toggle(index) })
                    Text(filter.name, style = MaterialTheme.typography.bodyLarge)
                }
                is AnimeFilter.Select<*> -> SelectRow(filter, index, screenModel)
                is AnimeFilter.TriState -> TextButton(onClick = { screenModel.cycle(index) }) {
                    Text(triLabel(filter), style = MaterialTheme.typography.bodyLarge)
                }
                is AnimeFilter.Text -> OutlinedTextField(
                    value = filter.state,
                    onValueChange = { screenModel.type(index, it) },
                    label = { Text(filter.name) },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
        }

        val selected = screenModel.count()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDone) {
                Text(if (selected > 0) "Применить ($selected)" else "Применить")
            }
            OutlinedButton(onClick = { screenModel.reset() }) {
                Text("Сбросить")
            }
        }
    }

    /** Список: кнопка показывает выбранный пункт, меню — все варианты. */
    @Composable
    private fun SelectRow(filter: AnimeFilter.Select<*>, index: Int, screenModel: FilterScreenModel) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { expanded = true }) {
                Text(filter.values.getOrElse(filter.state) { filter.name }.toString())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.values.forEachIndexed { position, label ->
                    DropdownMenuItem(
                        text = { Text(label.toString()) },
                        onClick = {
                            screenModel.choose(index, position)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    /** Трёхпозиционный: состояние видно прямо в подписи. */
    private fun triLabel(filter: AnimeFilter.TriState): String = when {
        filter.isIncluded() -> filter.name + ": включить"
        filter.isExcluded() -> filter.name + ": исключить"
        else -> filter.name + ": любое"
    }
}
