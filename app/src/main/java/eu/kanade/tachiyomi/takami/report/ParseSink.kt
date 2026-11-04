package eu.kanade.tachiyomi.takami.report

import com.tadami.aurora.BuildConfig
import eu.kanade.tachiyomi.takami.ParseFailureSink
import eu.kanade.tachiyomi.takami.ParseFailures

/**
 * Подключение пути разбора к сбору.
 *
  * Разбор ничего не знает про отчёты: он сообщает домен,
  * шаг и тип ошибки в
  * [ParseFailures], а сбор подписывается на них отсюда. Снимается
  * подписка
 * там же — вместе со всем сбором после калибровки.
 */
internal fun installParseSink(active: Reporter, installId: () -> String) {
    ParseFailures.sink = ParseFailureSink { host, step, error, specVersion ->
        active.note(parseEvent(host, step, error, specVersion, installId()))
    }
}

/**
 * Событие о провале разбора: домен, шаг и тип.
 *
  * Путь страницы, query и название тайтла сюда не попадают:
  * разбор передаёт
 * только домен, а остальное — короткие машинные метки.
 */
internal fun parseEvent(
    host: String,
    step: String,
    error: String,
    specVersion: Int,
    installId: String,
    at: Long = System.currentTimeMillis(),
): ReportEvent = ReportEvent(
    host = Sanitizer.hostOf(host),
    step = Sanitizer.field(step),
    error = Sanitizer.field(error),
    health = "degraded",
    appVersion = Sanitizer.field(BuildConfig.VERSION_NAME),
    specVersion = specVersion,
    installId = installId,
    at = at,
)
