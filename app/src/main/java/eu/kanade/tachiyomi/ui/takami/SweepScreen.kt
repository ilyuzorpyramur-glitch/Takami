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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.Sweep

/**
  * Прогон списка сайтов: то же, что делает стенд, но на
  * телефоне и по файлу.
 *
  * Экран не обещает результатов заранее: он показывает,
  * сколько сайтов уже
  * пройдено и где лежит итог. Разбирать итог — дело того,
  * кто ведёт проверку.
 */
class SweepScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { SweepScreenModel() }
        val state by screenModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            screenModel.refresh(context.filesDir)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Прогон сайтов") },
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Список", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Файл ${Sweep.LIST} в папке приложения: по" +
                                "одному адресу в строке." +
                                "Строки, начинающиеся с #," +
                                    "пропускаются. Сайты идут по одному," +
                                "с паузой ${Sweep.PAUSE_MS} мс — очередь" +
                                    "запросов сайт видеть не должен.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Итог пишется в ${Sweep.FILE} после каждого сайта: " +
                                "прерванный прогон продолжается с" +
                                    "места, где остановился.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                when (val current = state) {
                    is SweepState.NoList -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Списка нет", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Положите файл по пути ${current.file}" +
                                        "и вернитесь на этот экран.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Button(onClick = { screenModel.refresh(context.filesDir) }) {
                                    Text("Проверить снова")
                                }
                            }
                        }
                    }

                    is SweepState.Idle -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("В списке ${current.total}", style = MaterialTheme.typography.titleMedium)
                                if (current.done > 0) {
                                    Text(
                                        text = "Уже пройдено ${current.done}:" +
                                            "прогон продолжит дальше.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Button(onClick = { screenModel.start(context.filesDir) }) {
                                    Text("Запустить прогон")
                                }
                            }
                        }
                    }

                    is SweepState.Running -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                                    Text(
                                        text = "${current.done} из ${current.total}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { current.done.toFloat() / current.total.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "Сейчас: ${current.host.ifEmpty { "подготовка" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    is SweepState.Finished -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Прогон закончен", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Прошли все проверки: ${current.ok}. С" +
                                        "замечаниями: ${current.failed}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Итог: ${current.file}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { screenModel.refresh(context.filesDir) }) {
                                    Text("В начало")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
