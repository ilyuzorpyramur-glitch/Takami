package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR

/**
 * Вход в свайп-подбор: сразу под hero и выше строки поиска.
 *
 * Отдельная строка, чтобы соседние блоки главной не переставлялись.
 */
@Composable
internal fun HomeSwipeEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(MR.strings.swipe_entry_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(MR.strings.swipe_entry_subtitle),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
