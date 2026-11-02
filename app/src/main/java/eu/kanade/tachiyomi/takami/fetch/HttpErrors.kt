package eu.kanade.tachiyomi.takami.fetch

import java.io.IOException

/**
 * Ошибка сети или ответа сайта.
 *
 * Тексты попадают на экран «Диагностика», поэтому они короткие и без адресов с параметрами:
 * в query string у плееров встречаются подписанные ссылки.
 */
sealed class FetchError(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Ответ пришёл, но код не тот, который ждём. */
class HttpStatusError(
    val code: Int,
    val url: String,
    val retryAfterMs: Long? = null,
) : FetchError("HTTP " + code + " " + url)

/** Дневной потолок запросов к домену исчерпан: до полуночи работаем только из кэша. */
class DailyLimitReached(
    val host: String,
    val limit: Int,
) : FetchError("Дневной лимит запросов к " + host + " исчерпан: " + limit + " за сутки")

/** Сеть недоступна или соединение оборвалось. */
class NetworkError(
    val host: String,
    cause: Throwable,
) : FetchError("Сеть недоступна: " + host, cause)

/** Адрес не разбирается в URL. */
class InvalidUrlError(
    val url: String,
) : FetchError("Некорректный адрес: " + url)
