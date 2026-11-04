package eu.kanade.tachiyomi.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.abs
import tachiyomi.domain.discovery.model.DiscoverySuggestion
import tachiyomi.i18n.MR

/**
 * Карточка стека: постер, название, причина, оценка, серии, статус и жанры.
 *
 * Жест живёт здесь: карточка идёт за пальцем, поворот не больше двенадцати
 * градусов, при отпускании — возврат или уход.
 */
@Composable
internal fun SwipeCard(
    item: DiscoverySuggestion,
    onHide: () -> Unit,
    onLater: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    var offset by remember(item.id) { mutableFloatStateOf(0f) }
    var dragging by remember(item.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = offset
                rotationZ = (offset / 40f).coerceIn(-12f, 12f)
            }
            .pointerInput(item.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = { dragging = false; offset = 0f },
                    onDragEnd = {
                        dragging = false
                        when {
                            offset > threshold -> onLater()
                            offset < -threshold -> onHide()
                        }
                        offset = 0f
                    },
                    onHorizontalDrag = { change, drag ->
                        change.consume()
                        offset += drag
                    },
                )
            }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        item.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(text = reason, style = MaterialTheme.typography.bodySmall)
        }
        Text(text = metricsLine(item), style = MaterialTheme.typography.bodyMedium)
        if (item.genres.isNotEmpty()) {
            Text(text = item.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onHide) { Text(stringResource(MR.strings.swipe_hide)) }
            TextButton(onClick = onLater) { Text(stringResource(MR.strings.swipe_later)) }
            TextButton(onClick = onOpen) { Text(stringResource(MR.strings.swipe_open)) }
        }
    }
    if (dragging && abs(offset) >= threshold) offset = 0f
}

/** Строка метрик: оценка с подписью источника, серии, статус. Прочерк вместо пустоты. */
@Composable
private fun metricsLine(item: DiscoverySuggestion): String {
    val none = stringResource(MR.strings.swipe_value_none)
    val source = SwipeLogic.sourceLabel(item.provider)
    val ratingValue = SwipeLogic.ratingText(item.rating)
    val rating = when {
        ratingValue == null -> none
        source.isEmpty() -> ratingValue
        else -> stringResource(MR.strings.swipe_rating_source, source, ratingValue)
    }
    val episodes = SwipeLogic.episodes(item.episodes)?.let { it.toString() } ?: none
    val status = statusText(item.status) ?: none
    val separator = " · "
    return rating + separator + stringResource(MR.strings.swipe_episodes, episodes) +
        separator + status
}

@Composable
private fun statusText(key: String?): String? = when (key) {
    "ongoing" -> stringResource(MR.strings.swipe_status_ongoing)
    "finished" -> stringResource(MR.strings.swipe_status_finished)
    "anons" -> stringResource(MR.strings.swipe_status_anons)
    "paused" -> stringResource(MR.strings.swipe_status_paused)
    else -> null
}
