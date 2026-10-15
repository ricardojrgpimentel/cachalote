package dev.ricardopimentel.cachalote

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.res.stringResource
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
@OptIn(ExperimentalLayoutApi::class)
class WebModeActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // O tema da app (e não `MaterialTheme { }`): sem isto o esquema é o claro, com texto
            // quase preto sobre o fundo de janela escuro — preto sobre preto.
            CachaloteTema {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var status by remember { mutableStateOf(getString(R.string.web_a_obter)) }
                    var url by remember { mutableStateOf("") }
                    var reloadKey by remember { mutableStateOf(0) }

                    // O perfil `web` exige o URL com token que o motor imprime no arranque;
                    // buscamo-lo por `su` (o ficheiro é root-only).
                    androidx.compose.runtime.LaunchedEffect(reloadKey) {
                        status = runCatching {
                            val u = RootShell.cachalote("web-url", 20).getOrThrow().lineSequence()
                                .firstOrNull { it.startsWith("http") }?.trim()
                                ?: error(getString(R.string.web_sem_url))
                            url = u
                            getString(R.string.web_interface_token, u.substringBefore("?"))
                        }.getOrElse { getString(R.string.web_falhou, it.message ?: getString(R.string.web_sem_url)) }
                    }

                    // Insets: sem isto os controlos ficam debaixo da barra de estado e o SystemUI
                    // engole-lhes os toques (medido no S10+ em 2026-10-15).
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        // FlowRow e não Row: são três botões, e a 360 dp não caberiam numa linha.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { reloadKey++ }) { Text(stringResource(R.string.web_recarregar)) }
                            OutlinedButton(onClick = {
                                lifecycleScope.launch {
                                    status = getString(R.string.web_a_arrancar)
                                    status = runCatching { RootShell.cachalote("web-start", 90).getOrThrow() }
                                        .getOrElse { getString(R.string.web_falhou, it.message ?: "?") }
                                    reloadKey++
                                }
                            }) { Text(stringResource(R.string.web_ligar)) }
                            OutlinedButton(onClick = {
                                finish()
                            }) { Text(stringResource(R.string.web_voltar)) }
                        }

                        if (url.isBlank()) {
                            // Sem motor web não há nada para mostrar: um `WebView` vazio pinta
                            // preto e tapava o ecrã todo (era o «ecrã preto» deste ecrã). Aqui
                            // explica-se o que falta, em vez de criar um browser sem nada.
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.web_sem_motor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            AndroidView(
                                // `weight(1f)` e não `fillMaxSize()`: numa Column, `fillMaxSize` pede
                                // a altura toda do pai e passa por cima da linha de controlos.
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        // O fundo do WebView herda o do tema, que é escuro: sem isto
                                        // fica preto e confunde-se com uma falha.
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageFinished(view: WebView?, u: String?) {
                                                status = getString(R.string.web_interface, u ?: "")
                                            }
                                        }
                                    }
                                },
                                update = { wv -> if (wv.url != url) wv.loadUrl(url) },
                            )
                        }
                    }
                }
            }
        }
    }
}
