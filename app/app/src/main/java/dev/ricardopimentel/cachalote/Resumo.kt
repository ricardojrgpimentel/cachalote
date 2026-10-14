package dev.ricardopimentel.cachalote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * **Resumo** — o estado das coisas num só sítio: o consumo de contexto desta sessão e o
 * diagnóstico da cadeia toda, sem ir ao terminal.
 *
 * O diagnóstico vem do próprio `cachalote doctor`: a ponte corre-o e devolve estruturado
 * (`cachalote.doctor`). Quem manda é ele — aqui só se mostra, para não haver duas listas de
 * verificações a divergir.
 */
@Composable
fun ResumoScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------------------------------------------------------- consumo
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Consumo e sessão", fontWeight = FontWeight.SemiBold)
                val uso = vm.uso
                Text(
                    uso?.texto() ?: "sem números ainda — o motor só os manda depois de um turno",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (uso != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Linea("sessão", vm.sessionId?.take(8) ?: "nenhuma")
                Linea("modelo", vm.configOptions.firstOrNull { it.id == "model" }?.currentLabel ?: "?")
                Linea("esforço", vm.configOptions.firstOrNull { it.id == "reasoning_effort" }?.currentLabel ?: "?")
                Linea("fila de tarefas", vm.tarefas.count { it.estado == Tarefa.PENDENTE }.toString() + " por fazer")
                Linea("imagens anexadas", vm.transcript.count { it.role == "user" && it.text.contains("[imagem") }.toString())
            }
        }

        // ---------------------------------------------------------------- diagnóstico
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Diagnóstico", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.refreshDoctor() }, enabled = !vm.busy) { Text("Verificar") }
                }
                val d = vm.doctor
                when {
                    vm.embeddedMode -> Text(
                        "No modo embutido não há daemon para diagnosticar: o motor corre dentro da app e o sandbox do Android é o limite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    d == null -> Text(
                        "ainda não verifiquei — toca em Verificar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    else -> {
                        val problemas = d.optInt("problemas", 0)
                        val avisos = d.optInt("avisos", 0)
                        Text(
                            d.optString("resultado"),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (problemas == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "$problemas problema(s) · $avisos aviso(s) · ${d.optString("base")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        val secoes = d.optJSONArray("secoes")
                        if (secoes != null) {
                            for (i in 0 until secoes.length()) {
                                val sec = secoes.optJSONObject(i) ?: continue
                                val itens = sec.optJSONArray("itens") ?: continue
                                // Só as secções com algo a dizer: o resto é ruído verde.
                                val relevantes = (0 until itens.length()).mapNotNull { itens.optJSONObject(it) }
                                    .filter { it.optString("estado") != "ok" }
                                if (relevantes.isEmpty()) continue
                                Text(
                                    sec.optString("titulo"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                relevantes.forEach { it ->
                                    val estado = it.optString("estado")
                                    Text(
                                        (if (estado == "aviso") "⚠ " else "✕ ") + it.optString("texto"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (estado == "aviso") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        Text(
                            "tudo o resto está verde (${d.optJSONArray("secoes")?.length() ?: 0} secções verificadas)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Linea(rotulo: String, valor: String) {
    Row {
        Text(rotulo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        Text(valor, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
