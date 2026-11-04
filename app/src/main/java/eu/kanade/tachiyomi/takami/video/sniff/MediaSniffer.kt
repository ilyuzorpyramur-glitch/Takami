package eu.kanade.tachiyomi.takami.video.sniff

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Снимок сети: открываем страницу так, как её открыл бы человек, и
 * записываем медиа-адреса, которые плеер запросил сам.
 *
 * Ничего не подменяем: запрос уходит как есть, `shouldInterceptRequest`
 * возвращает null. Окно одно на всё приложение, создаётся только по
 * действию человека и снимается в любом исходе.
 */
object MediaSniffer {

    private const val BRIDGE = "TakamiSniff"

    private const val MAX_URL = 2_048

    /** Запас сверх срока и «дозора»: окно должно успеть сняться. */
    private const val OVERHEAD_MS = 4_000

    private val gate = Mutex()

    @Volatile
    private var app: Context? = null

    /** Запоминает контекст: окно потом создаётся по действию человека. */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun ready(): Boolean = app != null

    suspend fun capture(request: SniffRequest): SniffResult {
        val context = app ?: return SniffResult(stopReason = SniffStop.UNAVAILABLE)
        if (request.url.isBlank()) return SniffResult(stopReason = SniffStop.UNAVAILABLE)
        val total = (request.timeoutMs + request.settleMs + OVERHEAD_MS).toLong()
        return gate.withLock {
            withTimeoutOrNull(total) {
                withContext(Dispatchers.Main) { open(context, request) }
            } ?: SniffResult(stopReason = SniffStop.TIMEOUT)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open(context: Context, request: SniffRequest): SniffResult =
        suspendCancellableCoroutine { cont ->
            val hits = mutableListOf<SniffHit>()
            val state = SnifferState(request)
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val view = WebView(context)

            fun finish(reason: String) {
                if (!done.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
                cont.resume(SniffResult(hits.toList(), reason))
            }

            cont.invokeOnCancellation {
                handler.post {
                    if (done.compareAndSet(false, true)) {
                        runCatching { view.stopLoading() }
                        runCatching { view.destroy() }
                    }
                }
            }

            val sink: (String) -> Unit = { raw ->
                when (val hit = accept(raw, request)) {
                    null -> Unit
                    MARK_UNAVAILABLE -> finish(SniffStop.UNAVAILABLE)
                    MARK_DRM -> finish(SniffStop.DRM)
                    is SniffHit -> {
                        val first = state.hit(System.currentTimeMillis())
                        hits += hit
                        if (first) handler.postDelayed({ finish(SniffStop.FOUND) }, request.settleMs.toLong())
                    }
                    else -> Unit
                }
            }

            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            runCatching { CookieManager.getInstance().setAcceptCookie(true) }
            view.addJavascriptInterface(Bridge(sink), BRIDGE)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    sink(request.url?.toString().orEmpty())
                    // Ничего не подменяем: запрос идёт как есть.
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    // Зацепка ставится до скриптов страницы.
                    view.evaluateJavascript(HOOK, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(HOOK + LATE_CHECK, null)
                }
            }

            handler.postDelayed({ finish(if (state.found()) SniffStop.FOUND else SniffStop.TIMEOUT) },
                request.timeoutMs.toLong())
            try {
                if (request.referer.isNotBlank()) {
                    view.loadUrl(request.url, mapOf("Referer" to request.referer))
                } else {
                    view.loadUrl(request.url)
                }
            } catch (error: Throwable) {
                // Окно снимается в любом исходе, включая исключение загрузки.
                finish(SniffStop.UNAVAILABLE)
            }
        }

    /** Отбор одного сообщения: медиа-адрес или метка страницы. */
    private fun accept(raw: String, request: SniffRequest): Any? {
        val text = raw.trim()
        if (text == MARK_UNAVAILABLE || text == MARK_DRM) return text
        if (text.length > MAX_URL) return null
        val lower = text.lowercase()
        if (lower.startsWith("blob:")) return SniffHit(text, "", "js")
        if (!lower.startsWith("http") && !lower.startsWith("//")) return null
        val url = if (lower.startsWith("//")) "https:" + text else text
        if (url.length > MAX_URL) return null
        return SniffHit(url, "", "net")
    }

    /** Мост из страницы: один метод, строгая проверка входа. */
    private class Bridge(private val sink: (String) -> Unit) {

        @JavascriptInterface
        fun post(value: String) {
            val text = value.trim()
            if (text.length > MAX_URL) return
            sink(text)
        }
    }

    private const val MARK_UNAVAILABLE = "takami:unavailable"

    private const val MARK_DRM = "takami:drm"

    /** Зацепка ставится до скриптов страницы: перехват запросов и медиа. */
    private val HOOK = """
        (function(){
          if (window.__takamiHook) return; window.__takamiHook = 1;
          function send(u){
            try {
              if (typeof u !== 'string') return;
              if (u.length > 2048) return;
              var l = u.toLowerCase();
              if (l.indexOf('http') !== 0 && l.indexOf('//') !== 0 && l.indexOf('blob:') !== 0) return;
              window.$BRIDGE.post(u);
            } catch (e) {}
          }
          try {
            var open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u){ send(u); return open.apply(this, arguments); };
          } catch (e) {}
          try {
            var f = window.fetch;
            if (f) {
              window.fetch = function(i){
                send(typeof i === 'string' ? i : (i && i.url));
                return f.apply(this, arguments);
              };
            }
          } catch (e) {}
          try {
            var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
            if (d && d.set) { Object.defineProperty(HTMLMediaElement.prototype, 'src', {
              set: function(v){ send(v); return d.set.call(this, v); }, get: d.get, configurable: true }); }
          } catch (e) {}
          try {
            var s = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
            if (s && s.set) { Object.defineProperty(HTMLSourceElement.prototype, 'src', {
              set: function(v){ send(v); return s.set.call(this, v); }, get: s.get, configurable: true }); }
          } catch (e) {}
        })();
    """

    /** Проверка состояния страницы: лицензия и DRM видны только в тексте. */
    private const val LATE_CHECK = """
        (function(){
          try {
            var t = (document.body && document.body.innerText || '').toLowerCase();
            if (t.indexOf('blob:') >= 0 && !window.__takamiNet) window.$BRIDGE.post('takami:drm');
            if (t.indexOf('недоступно') >= 0 && t.indexOf('лиценз') >= 0) {
              window.$BRIDGE.post('takami:unavailable');
            }
          } catch (e) {}
        })();
    """
}
