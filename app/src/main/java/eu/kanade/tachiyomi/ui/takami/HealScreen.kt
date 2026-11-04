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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.takami.heal.HealAllow
import eu.kanade.tachiyomi.takami.heal.SpecPart
import java.text.DateFormat
import java.util.Date

/**
 * Экран «Починить источник»: что сломалось, можно ли лечить, итог попытки.
 *
 * Лечение запускается только этой кнопкой. Экран не обещает успеха заранее
 * и не рисует чисел, которых нет.
 */
class HealScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { HealScreenModel() }
        val state by screenModel.state.collectAsStateWithLifecycle()
        val sources = TakamiForge.specs.collectAsStateWithLifecycle().value

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Починить источник") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state != HealUiState.Pick) screenModel.back() else navigator.pop()
                        }) {
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val current = state) {
                    HealUiState.Pick -> SourceList(sources, onOpen = screenModel::open)
                    is HealUiState.Checking -> CheckingView(current.spec)
                    is HealUiState.Ready -> DiagnosisView(current, screenModel)
                    is HealUiState.Running -> RunningView(current.spec)
                    is HealUiState.Done -> DoneView(current, screenModel)
                }
            }
        }
    }

    @Composable
    private fun SourceList(sources: List<SiteSpec>, onOpen: (String) -> Unit) {
        if (sources.isEmpty()) {
            Text("Источников пока нет: добавьте источник через мастера.", style = MaterialTheme.typography.bodyMedium)
            return
        }
        Text("Выберите источник", style = MaterialTheme.typography.titleMedium)
        for (spec in sources) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(spec.host) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                Text(spec.host, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun CheckingView(spec: SiteSpec) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            Text("  Проверяю страницу", style = MaterialTheme.typography.bodyLarge)
        }
        Text(spec.name, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun RunningView(spec: SiteSpec) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            Text("  Проверяю страницу", style = MaterialTheme.typography.bodyLarge)
        }
        Text(spec.name, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun DiagnosisView(state: HealUiState.Ready, screenModel: HealScreenModel) {
        Text(state.spec.name, style = MaterialTheme.typography.titleLarge)
        Text(state.spec.host, style = MaterialTheme.typography.bodySmall)

        if (state.failed.isEmpty()) {
            Text("Поломок не нашлось: источник проверку проходит.", style = MaterialTheme.typography.bodyLarge)
            return
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что сломалось", style = MaterialTheme.typography.titleMedium)
                for (step in state.failed) {
                    Text(step.title + (if (step.note.isBlank()) "" else ": " + step.note), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        when (val allow = state.allow) {
            is HealAllow.Yes -> {
                Text("Можно починить", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { screenModel.fix() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Починить")
                }
            }
            is HealAllow.No -> {
                val hours = allow.retryAfterMs / (60L * 60 * 1000)
                val wait = if (allow.retryAfterMs > 0) {
                    "Подождите " + (if (hours < 1) 1 else hours) + " ч"
                } else {
                    "Починка недоступна: " + reasonText(allow.reason)
                }
                Text(wait, style = MaterialTheme.typography.titleMedium)
            }
        }

        Journal(screenModel.journal(state.spec.host))
    }

    @Composable
    private fun DoneView(state: HealUiState.Done, screenModel: HealScreenModel) {
        Text(state.spec.name, style = MaterialTheme.typography.titleLarge)
        if (state.result.healed) {
            Text("Починено: " + partText(state.result.part), style = MaterialTheme.typography.titleMedium)
        } else {
            Text("Не вышло, оставил прежнюю версию", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { screenModel.reopen() }, modifier = Modifier.fillMaxWidth()) {
            Text("Проверить снова")
        }
        Journal(screenModel.journal(state.spec.host))
    }

    @Composable
    private fun Journal(rows: List<eu.kanade.tachiyomi.takami.heal.HealLogEntry>) {
        if (rows.isEmpty()) return
        HorizontalDivider()
        Text("Последние попытки", style = MaterialTheme.typography.titleMedium)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (row in rows) {
            val when_ = format.format(Date(row.at))
            Text(
                text = partTextSafe(row.part) + " · " + decisionText(row.decision) + " · " + when_,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** Части по-русски, без англицизмов. */
    private fun partText(part: SpecPart): String = when (part) {
        SpecPart.CATALOG -> "каталог"
        SpecPart.CARD -> "карточка"
        SpecPart.EPISODES -> "серии"
        SpecPart.PAGINATION -> "страницы"
        SpecPart.NONE -> "—"
    }

    private fun partTextSafe(part: String): String = when (part) {
        "CATALOG" -> "каталог"
        "CARD" -> "карточка"
        "EPISODES" -> "серии"
        "PAGINATION" -> "страницы"
        else -> "—"
    }

    private fun decisionText(decision: String): String = when (decision) {
        "accepted" -> "починено"
        "rejected" -> "отказ"
        "failed" -> "не вышло"
        "blocked" -> "сайт закрылся"
        "skipped" -> "пропущено"
        else -> "—"
    }

    private fun reasonText(reason: String): String = when (reason) {
        "read_only" -> "источник только для чтения"
        "video_owned" -> "видео здесь не чинится"
        "never_worked" -> "источник ещё не работал"
        "attempts_today" -> "попытки на сегодня закончились"
        "no_breakage" -> "поломка не зафиксирована"
        else -> reason
    }
}
