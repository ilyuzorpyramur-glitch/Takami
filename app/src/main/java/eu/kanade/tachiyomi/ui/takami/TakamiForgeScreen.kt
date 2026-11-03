package eu.kanade.tachiyomi.ui.takami

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.takami.CheckResult
import eu.kanade.tachiyomi.takami.SiteSpec
import eu.kanade.tachiyomi.takami.TakamiForge
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/**
 * Мастер добавления источника: ссылка → иконка → предпросмотр → качество → озвучки → сохранение.
 *
 * Каждый шаг показывает только то, что зависит от источника; ничего не обещает заранее.
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
        var step by remember { mutableStateOf(0) }
        var iconPath by remember { mutableStateOf<String?>(null) }
        var quality by remember { mutableStateOf("auto") }
        var dubbings by remember { mutableStateOf(emptyList<String>()) }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is ForgeEvent.Saved -> context.toast("Источник «${event.name}» добавлен")
                    is ForgeEvent.Removed -> context.toast("Источник «${event.name}» удалён")
                }
            }
        }

        val pickImage = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                iconPath = copyIcon(context, uri, (state as? ForgeState.Ready)?.spec?.host)
            }
        }

        ForgeContent(
            url = url,
            onUrlChange = { url = it },
            state = state,
            saved = saved,
            step = step,
            iconPath = iconPath,
            quality = quality,
            dubbings = dubbings,
            onBuild = {
                step = 1
                screenModel.build(url)
            },
            onStep = { step = it },
            onPickIcon = { pickImage.launch("image/*") },
            onQuality = { quality = it },
            onDubbing = { name ->
                dubbings = if (name in dubbings) dubbings - name else dubbings + name
            },
            onSave = {
                screenModel.save(iconPath, quality, dubbings)
                step = 1
            },
            onReset = {
                screenModel.reset()
                step = 0
                iconPath = null
                quality = "auto"
                dubbings = emptyList()
            },
            onRemove = screenModel::remove,
            navigateUp = navigator::pop,
        )
    }

    /** Копируем выбранную картинку в файлы приложения: спека хранит только путь. */
    private fun copyIcon(context: Context, uri: android.net.Uri, host: String?): String? = runCatching {
        val dir = File(context.filesDir, "takami/icons").apply { mkdirs() }
        val file = File(dir, (host ?: "source") + ".png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    }.getOrNull()
}

private const val STEPS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeContent(
    url: String,
    onUrlChange: (String) -> Unit,
    state: ForgeState,
    saved: List<SiteSpec>,
    step: Int,
    iconPath: String?,
    quality: String,
    dubbings: List<String>,
    onBuild: () -> Unit,
    onStep: (Int) -> Unit,
    onPickIcon: () -> Unit,
    onQuality: (String) -> Unit,
    onDubbing: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRemove: (SiteSpec) -> Unit,
    navigateUp: () -> Unit,
) {
    val ready = state as? ForgeState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            ready == null -> "Источник по ссылке"
                            else -> "Шаг " + step + " из " + STEPS
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (step > 1) onStep(step - 1) else navigateUp() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ready == null) {
                UrlStep(url, state, onUrlChange, onBuild, onReset)
            } else {
                when (step) {
                    1 -> IconStep(ready.spec, iconPath, onPickIcon)
                    2 -> PreviewStep(ready)
                    3 -> QualityStep(ready, quality, onQuality)
                    else -> DubbingStep(ready, dubbings, onDubbing, onSave)
                }

                if (step in 1..3) {
                    Button(onClick = { onStep(step + 1) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Далее")
                    }
                }
            }

            if (saved.isNotEmpty()) {
                HorizontalDivider()
                Text("Мои источники", style = MaterialTheme.typography.titleSmall)
                saved.forEach { spec ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                spec.host + " · " + spec.qualityPreference +
                                    if (spec.dubbings.isEmpty()) "" else " · " + spec.dubbings.size + " озв.",
                                style = MaterialTheme.typography.bodySmall,
                            )
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

@Composable
private fun UrlStep(
    url: String,
    state: ForgeState,
    onUrlChange: (String) -> Unit,
    onBuild: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        "Вставь адрес сайта — приложение разберёт вёрстку, покажет предпросмотр " +
            "и даст выбрать иконку, качество и озвучки. APK не собирается.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Адрес сайта") },
        placeholder = { Text("https://example-anime.org") },
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBuild, enabled = url.isNotBlank() && state !is ForgeState.Running) {
            Text("Разобрать")
        }
        if (state !is ForgeState.Idle) {
            OutlinedButton(onClick = onReset) { Text("Сбросить") }
        }
    }

    when (state) {
        is ForgeState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text("  Разбираю " + state.url, style = MaterialTheme.typography.bodyMedium)
        }

        is ForgeState.Failed -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text("  Не получилось", style = MaterialTheme.typography.titleSmall)
                }
                Text(state.reason, style = MaterialTheme.typography.bodySmall)
            }
        }

        else -> Unit
    }
}

@Composable
private fun IconStep(spec: SiteSpec, iconPath: String?, onPickIcon: () -> Unit) {
    Text("Иконка источника", style = MaterialTheme.typography.titleMedium)
    Text(
        "Сейчас берётся иконка сайта. Можно выбрать свою картинку — она останется на устройстве.",
        style = MaterialTheme.typography.bodySmall,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        val bitmap = remember(iconPath) { iconPath?.let { loadBitmap(it) } }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(spec.name, style = MaterialTheme.typography.bodyLarge)
            Text(iconPath ?: spec.iconUrl ?: "иконка сайта", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPickIcon, modifier = Modifier.padding(top = 6.dp)) {
                Text("Выбрать картинку")
            }
        }
    }
}

@Composable
private fun PreviewStep(ready: ForgeState.Ready) {
    Text("Предпросмотр", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(ready.spec.name, style = MaterialTheme.typography.titleMedium)
            Text(ready.spec.baseUrl + " · " + ready.spec.engine, style = MaterialTheme.typography.bodySmall)
            if (ready.spec.catalog.latestPath != null) {
                Text("Лента новинок: " + ready.spec.catalog.latestPath, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Лента новинок не найдена — «Популярное» и «Новое» будут одинаковыми", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    ready.checks.forEach { check -> CheckRow(check) }
}

@Composable
private fun CheckRow(check: CheckResult) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (check.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text("  " + check.title + ": " + check.note, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QualityStep(ready: ForgeState.Ready, quality: String, onQuality: (String) -> Unit) {
    Text("Качество", style = MaterialTheme.typography.titleMedium)
    Text(
        if (ready.qualities.isEmpty()) {
            "Сайт не отдаёт список качеств для этого тайтла."
        } else {
            "Доступно: " + ready.qualities.joinToString(", ")
        },
        style = MaterialTheme.typography.bodySmall,
    )

    listOf("auto" to "Как отдаёт сайт", "max" to "Максимальное доступное").forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onQuality(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = quality == value, onClick = { onQuality(value) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DubbingStep(
    ready: ForgeState.Ready,
    selected: List<String>,
    onDubbing: (String) -> Unit,
    onSave: () -> Unit,
) {
    Text("Озвучки", style = MaterialTheme.typography.titleMedium)
    if (ready.dubbings.isEmpty()) {
        Text(
            "Сайт не отдаёт список озвучек в разметке — источник возьмёт то, что есть.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text("Отметь нужные. Пусто — брать все.", style = MaterialTheme.typography.bodySmall)
        ready.dubbings.forEach { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDubbing(name) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = name in selected, onCheckedChange = { onDubbing(name) })
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Сохранить источник")
    }
}

private fun loadBitmap(path: String): android.graphics.Bitmap? =
    runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
