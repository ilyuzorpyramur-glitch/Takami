package eu.kanade.tachiyomi.takami.fetch

import java.io.IOException

/**
 * Ошибка сети или ответа сайта.
 *
   * Тексты попадают на экран «Диагностика», поэтому они
   * короткие и
  * без адресов с параметрами:
 * в query string у плееров встречаются подписанные ссылки.
 */
sealed class FetchError(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Ответ пришёл, но код не тот, который ждём. */
class HttpStatusError(
    val code: Int,
    val url: String,
    val retryAfterMs: Long? = null,
) : FetchError("HTTP " + code + " " + url)

/**
 * Дневной потолок запросов к домену исчерпан: до полуночи
 * работаем только из кэша.
 */
class DailyLimitReached(
    val host: String,
    val limit: Int,
) : FetchError(
    "Дневной лимит запросов к " + host + " исчерпан: " + limit + " за сутки",
)

/** Сеть недоступна или соединение оборвалось. */
class NetworkError(
    val host: String,
    cause: Throwable,
) : FetchError("Сеть недоступна: " + host, cause)

/** Адрес не разбирается в URL. */
class InvalidUrlError(
    val url: String,
) : FetchError("Некорректный адрес: " + url)

/**
 * Редиректы идут по кругу или их слишком много: цепочку обрываем.
 */
class TooManyRedirectsError(
    val url: String,
) : FetchError("Слишком много редиректов: " + url)

/** Ответ пришёл, но это не страница: картинка, JSON, пустое тело. */
class NotHtmlError(
    val url: String,
    val contentType: String,
) : FetchError("Не HTML (" + contentType.ifBlank { "без типа" } + "): " + url)

/**
 * Страница больше разумного: скорее всего, отдали файл вместо
 * разметки.
 */
class PageTooLargeError(
    val url: String,
    val bytes: Long,
) : FetchError("Страница слишком большая: " + bytes + " байт, " + url)

/**
  * Заглушка антибота. Повторять такой запрос нельзя:
  * именно повтор
 * и блокирует.
 */
open class ChallengePageError(
    val url: String,
) : FetchError("Сайт просит проверку браузера: " + url)

/**
 * Сайт ждёт человека: капчу или подтверждение входа.
 *
  * Запрос не повторяется: пока проверку не прошли, каждый
  * повтор только
 * подтверждает сайту, что перед ним не браузер.
 */
class VerificationRequiredError(
    val host: String,
    val level: ProtectionLevel,
    url: String,
) : ChallengePageError(url) {
    override val message: String
        get() = "Сайт " + host + " требует проверки (" + level.name.lowercase() + ")"
}

/** Домен в паузе после серии ошибок: запрос не отправляем. */
class BreakerOpenError(
    val host: String,
) : FetchError("Сайт " + host + " на паузе после ошибок")
