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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.tadami.aurora.BuildConfig
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.fetch.BreakerState
import androidx.compose.material3.Switch
import eu.kanade.tachiyomi.takami.fetch.HostReport
import eu.kanade.tachiyomi.takami.fetch.ReportsView
import eu.kanade.tachiyomi.takami.fetch.VerificationStatus
import eu.kanade.tachiyomi.takami.fetch.NetworkDiagnostics
import kotlinx.coroutines.launch

/**
  * Экран «Диагностика»: сколько запросов ушло к каждому сайту,
  * сколько осталось,
 * не отступили ли мы от сайта после серии ошибок.
 *
   * Экран ничего не запрашивает у сайтов — только читает
   * счётчики.
  * Иначе сама
 * попытка посмотреть расход создавала бы расход.
 */
class DiagnosticsScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var reports by remember { mutableStateOf<List<HostReport>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var waiting by remember { mutableStateOf<List<VerificationStatus>>(emptyList()) }
        var reportStatus by remember { mutableStateOf(ReportsView()) }

        suspend fun reload() {
            reports = NetworkDiagnostics.report()
            waiting = NetworkDiagnostics.verifications()
            reportStatus = NetworkDiagnostics.reports()
            loaded = true
        }

        LaunchedEffect(Unit) { reload() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "Диагностика") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Назад",
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Расход запросов к сайтам за сегодня",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Потолок — 300 запросов на сайт в сутки. " +
                        "После трёх ошибок сайт уходит в паузу на 15 минут.",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (loaded && reports.isEmpty()) {
                    Text(
                        text = "Пока не было ни одного запроса:" +
                            "открывайте каталог," +
                            "и здесь появится расход.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                reports.forEach { report ->
                    HostCard(report = report)
                }

                if (waiting.isNotEmpty()) {
                    Text(
                        text = "Сайты, которые ждут проверки",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    waiting.forEach { status ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(text = status.host, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = status.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(
                                    modifier = Modifier.padding(top = 6.dp),
                                    onClick = { navigator.push(VerificationScreen(status.host)) },
                                ) {
                                    Text("Открыть окно проверки")
                                }
                            }
                        }
                    }
                }

                Text(
                    text = if (reportStatus.active) {
                        "Сбор отчётов для калибровки, до 31 января 2027. " +
                            "После этого выключится сам."
                    } else {
                        "Срок калибровки прошёл: отчёты больше не" +
                            "отправляются."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(text = "Отчёты о сбоях", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "В очереди: " + reportStatus.queued,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Отправлено всего: " + reportStatus.totalSent,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (reportStatus.lastSentAt == 0L) {
                                "Ещё не отправляли"
                            } else {
                                "Последняя отправка: " + formatSent(reportStatus.lastSentAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Отправлять отчёты",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(
                                checked = reportStatus.enabled,
                                onCheckedChange = { value ->
                                    scope.launch {
                                        NetworkDiagnostics.setReportsEnabled(value)
                                        reportStatus = NetworkDiagnostics.reports()
                                    }
                                },
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        NetworkDiagnostics.clearReports()
                                        reportStatus = NetworkDiagnostics.reports()
                                    }
                                },
                            ) {
                                Text("Очистить очередь")
                            }
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                NetworkDiagnostics.resetCounters()
                                reload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Сбросить счётчики (отладочная сборка)")
                    }
                }
            }
        }
    }

    @Composable
    private fun HostCard(report: HostReport) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = report.host, style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Запросов за сутки: " + report.used,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Осталось: " + report.remaining + " из " + report.limit,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                Text(
                    text = "Предохранитель: " + breakerText(report),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Последняя ошибка: " + (report.lastError ?: "нет"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    private fun breakerText(report: HostReport): String = when (report.breaker) {
        BreakerState.CLOSED -> "работает"
        BreakerState.OPEN -> "пауза ещё " + (report.pauseLeftMs / 60_000L + 1L) + " мин"
        BreakerState.HALF_OPEN -> "проверка: один пробный запрос"
    }
}

private fun formatSent(at: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(at))
