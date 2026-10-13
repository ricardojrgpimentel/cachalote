package dev.ricardopimentel.cachalote

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Modo completo": a interface web nativa do DeepSeek Harness (o mesmo frontend do
 * desktop), servida pelo perfil `web` do motor no próprio telefone.
 *
 * Existe para paridade total — definições de modelos/provedores, gestão de plugins,
 * anexos — enquanto a UI nativa cobre o dia-a-dia (conversa, sessões, capacidades).
 *
 * A UI nativa continua a funcionar em paralelo: o perfil `web` corre ao lado do ACP.
 */
class WebModeActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var status by remember { mutableStateOf("a obter o URL do motor web…") }
                    var url by remember { mutableStateOf("") }
                    var reloadKey by remember { mutableStateOf(0) }

                    // O perfil `web` exige o URL com token que o motor imprime no arranque;
                    // buscamo-lo por `su` (o ficheiro é root-only).
                    androidx.compose.runtime.LaunchedEffect(reloadKey) {
                        status = runCatching {
                            val u = RootShell.cachalote("web-url", 20).getOrThrow().lineSequence()
                                .firstOrNull { it.startsWith("http") }?.trim()
                                ?: error("sem URL (liga o motor web)")
                            url = u
                            "interface web: " + u.substringBefore("?") + " (com token)"
                        }.getOrElse { "motor web não está a correr: ${it.message}" }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { reloadKey++ }) { Text("Recarregar") }
                            OutlinedButton(onClick = {
                                lifecycleScope.launch {
                                    status = "a arrancar o motor web…"
                                    status = runCatching { RootShell.cachalote("web-start", 90).getOrThrow() }
                                        .getOrElse { "falhou: ${it.message}" }
                                    reloadKey++
                                }
                            }) { Text("Ligar web") }
                            OutlinedButton(onClick = {
                                finish()
                            }) { Text("Voltar") }
                        }

                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, u: String?) {
                                            status = "interface web: $u"
                                        }
                                    }
                                }
                            },
                            update = { wv -> if (url.isNotBlank() && wv.url != url) wv.loadUrl(url) },
                        )
                    }
                }
            }
        }
    }
}
