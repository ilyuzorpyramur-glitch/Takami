package eu.kanade.tachiyomi.ui.takami

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.takami.fetch.Fetcher

/**
 * Видимое окно проверки: один сайт, адрес на виду.
 *
 * Капчу решает человек в этом окне — приложение за него ничего не нажимает.
 * Ссылки на другие сайты уходят в системный браузер: окно всегда остаётся
 * окном одного домена.
 */
class VerificationScreen(private val host: String) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val address = "https://" + host + "/"

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "Проверка сайта") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Назад",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Text(text = host, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Text(
                    text = "Сайт просит подтвердить, что вы человек. Пройдите проверку в окне ниже " +
                        "и нажмите «Готово». Приложение само ничего не нажимает.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = Fetcher.USER_AGENT
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String,
                                ): Boolean {
                                    if (url.startsWith(address) || url.startsWith("https://" + host)) {
                                        return false
                                    }
                                    val out = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    runCatching { viewContext.startActivity(out) }
                                    return true
                                }
                            }
                            loadUrl(address)
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Fetcher.get().acceptCookies(address, host)
                            navigator.pop()
                        },
                    ) {
                        Text("Готово")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { navigator.pop() },
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}
