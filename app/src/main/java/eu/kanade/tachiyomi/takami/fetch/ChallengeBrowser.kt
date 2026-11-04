package eu.kanade.tachiyomi.takami.fetch

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.Cookie
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Проход проверки браузера так же, как это делает Chrome.
 *
  * Ничего не подменяем и не обходим: открываем настоящий
  * WebView с включённым JS
  * и даём скрипту сайта поставить cookie. Возвращает строку
  * cookie после
 * проверки либо null, если сайт так и не пустил.
 */
interface ChallengeBrowser {
    suspend fun pass(url: String, host: String): String?
}

/**
 * WebView для проверки браузера.
 *
 * Один WebView на домен одновременно: второй запрос ждёт первый, а не
  * открывает своё окно. Общий предел ожидания —
  * [FetchLimits.CHALLENGE_TIMEOUT_MS].
  * При отмене корутины (пользователь ушёл с экрана) окно
  * закрывается.
 */
class WebViewChallengeBrowser(private val context: Context) : ChallengeBrowser {

    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun pass(url: String, host: String): String? {
        val lock = locks.getOrPut(host) { Mutex() }
        if (!lock.tryLock()) {
            // Домен уже проходит проверку: второй раз окно не
            // открываем.
            return withTimeoutOrNull(FetchLimits.CHALLENGE_TIMEOUT_MS) { lock.withLock { null } }
        }
        return try {
            solve(url, host)
        } finally {
            lock.unlock()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solve(url: String, host: String): String? = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val before = manager.getCookie(url).orEmpty()
        val web = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Тот же браузерный агент, что у сетевого слоя: cookie
            // проверки
            // привязана к агенту, с другим она бесполезна.
            settings.userAgentString = Fetcher.USER_AGENT
            webViewClient = WebViewClient()
        }
        val changed = withTimeoutOrNull(FetchLimits.CHALLENGE_TIMEOUT_MS) {
            web.loadUrl(url)
            while (true) {
                val now = manager.getCookie(url).orEmpty()
                if (now.isNotEmpty() && now != before) return@withTimeoutOrNull true
                delay(FetchLimits.CHALLENGE_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        web.stopLoading()
        web.destroy()
        val cookies = manager.getCookie(url).orEmpty()
        (changed == true).also { passed ->
            logcat(LogPriority.DEBUG) {
                "Takami: проверка браузера " + host + (if (passed) " пройдена" else " не пройдена")
            }
        }
        if (changed == true && cookies.isNotBlank()) cookies else null
    }
}

/** Превращает строку cookie из WebView в пары для общего jar. */
object ChallengeCookies {

    fun parse(host: String, header: String): List<Cookie> = header.split(';')
        .mapNotNull { pair ->
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) {
                null
            } else {
                runCatching {
                    Cookie.Builder()
                        .domain(host)
                        .path("/")
                        .name(name)
                        .value(value)
                        .build()
                }.getOrNull()
            }
        }
}
