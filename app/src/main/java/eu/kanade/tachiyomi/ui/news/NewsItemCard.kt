package eu.kanade.tachiyomi.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.takami.news.NewsItem
import eu.kanade.tachiyomi.takami.news.DayMark
import eu.kanade.tachiyomi.takami.news.dateTextOf
import eu.kanade.tachiyomi.takami.news.dayMarkOf
import tachiyomi.i18n.MR

/**
 * Карточка новости: заголовок, анонс, источник и дата.
 *
 * Полного текста нет и не будет: тап открывает запись в браузере.
 */
@Composable
fun NewsItemCard(item: NewsItem, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            if (item.snippet.isNotBlank()) {
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 6.dp)) {
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = dateLabel(item, now), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Дата записи словами: сегодня, вчера, иначе число месяца; кривая дата пуста. */
@Composable
fun dateLabel(item: NewsItem, now: Long): String = when (dayMarkOf(item.publishedAt, now)) {
    DayMark.TODAY -> stringResource(MR.strings.news_today)
    DayMark.YESTERDAY -> stringResource(MR.strings.news_yesterday)
    DayMark.DAY -> dateTextOf(item.publishedAt, now)
    DayMark.NONE -> ""
}
