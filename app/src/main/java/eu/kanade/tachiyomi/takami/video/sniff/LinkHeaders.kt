package eu.kanade.tachiyomi.takami.video.sniff

import android.webkit.CookieManager
import eu.kanade.tachiyomi.takami.SniffSpec
import okhttp3.Headers

/**
 * Заголовки окна, с которыми ссылка должна играть.
 *
 * Плеер часто принимает поток только со своим `Referer` и `Origin`, а
 * `Cookie` нужен для входа. Значения берутся у самого приложения: своих
 * cookie не выдумываем и в файлы не пишем.
 */
object LinkHeaders {

    private const val AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Заголовки по списку из спеки. */
    fun passable(spec: SniffSpec, pageUrl: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (name in spec.passHeaders) {
            when (name.trim().lowercase()) {
                "referer" -> if (pageUrl.isNotBlank()) result["Referer"] = pageUrl
                "origin" -> originOf(pageUrl)?.let { result["Origin"] = it }
                "user-agent" -> result["User-Agent"] = AGENT
                "cookie" -> cookieOf(pageUrl)?.let { result["Cookie"] = it }
            }
        }
        return result
    }

    /** Заголовки для сетевого слоя: пустая карта даёт null, а не пустой набор. */
    fun okhttp(headers: Map<String, String>): Headers? {
        if (headers.isEmpty()) return null
        val builder = Headers.Builder()
        headers.forEach { (name, value) -> if (value.isNotBlank()) builder.add(name, value) }
        return runCatching { builder.build() }.getOrNull()?.takeIf { it.size > 0 }
    }

    private fun originOf(pageUrl: String): String? {
        val mark = pageUrl.indexOf("://")
        if (mark <= 0) return null
        val scheme = pageUrl.substring(0, mark)
        val host = pageUrl.substring(mark + 3).substringBefore('/')
        return if (host.isBlank()) null else scheme + "://" + host
    }

    private fun cookieOf(pageUrl: String): String? = runCatching {
        CookieManager.getInstance().getCookie(pageUrl)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
