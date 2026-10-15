package dev.ricardopimentel.cachalote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * **Resumo** — o estado das coisas num só sítio: o consumo de contexto desta sessão, o
 * diagnóstico da cadeia toda e a fila de tarefas, sem ir ao terminal.
 *
 * O diagnóstico vem do próprio `cachalote doctor`: a ponte corre-o e devolve estruturado
 * (`cachalote.doctor`). Quem manda é ele — aqui só se mostra, para não haver duas listas de
 * verificações a divergir.
 *
 * A forma (micro-etiquetas, número grande em monospace com barra, tabela de verificações com
 * visto e a fila em cartão) segue o ecrã «Resumo» desenhado no Stitch a 2026-10-14 com as três
 * abas da app — [`design/ecras/resumo-3abas.png`](../../../../../../design/ecras/resumo-3abas.png).
 */
@Composable
fun ResumoScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CartaoConsumo(vm)
        CartaoDiagnostico(vm)
        CartaoFila(vm)
    }
}

/** Cartão do design: cartões com contorno subtil e fundo `surfaceContainer`. */
@Composable
private fun Cartao(conteudo: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = conteudo)
    }
}

// ------------------------------------------------------------------------------- consumo
@Composable
private fun CartaoConsumo(vm: MainViewModel) {
    val uso = vm.uso
    Cartao {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(stringResource(R.string.resumo_consumo), Modifier.weight(1f))
            if (uso != null && uso.limite > 0) Chip("${uso.percentagem}%")
        }

        if (uso == null) {
            Text(
                stringResource(R.string.resumo_sem_numeros),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (uso.limite > 0) "${Uso.milhares(uso.usado)} / ${Uso.milhares(uso.limite)}"
                    else Uso.milhares(uso.usado),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.resumo_tokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (uso.limite > 0) BarraDeProgresso(uso.percentagem)
        }

        LinhaValor(stringResource(R.string.rotulo_sessao), vm.sessionId?.take(8) ?: stringResource(R.string.nenhuma))
        LinhaValor(stringResource(R.string.rotulo_modelo), vm.configOptions.firstOrNull { it.id == "model" }?.currentLabel ?: "?")
        LinhaValor(stringResource(R.string.rotulo_esforco), vm.configOptions.firstOrNull { it.id == "reasoning_effort" }?.currentLabel ?: "?")
        LinhaValor(
            stringResource(R.string.rotulo_fila),
            stringResource(R.string.resumo_por_fazer, vm.tarefas.count { it.estado == Tarefa.PENDENTE }),
        )
        LinhaValor(
            stringResource(R.string.rotulo_imagens),
            vm.transcript.count { it.role == "user" && it.text.contains("[imagem") }.toString(),
        )
    }
}

// ------------------------------------------------------------------------------- diagnóstico
@Composable
private fun CartaoDiagnostico(vm: MainViewModel) {
    Cartao {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(stringResource(R.string.resumo_diagnostico), Modifier.weight(1f))
            OutlinedButton(onClick = { vm.refreshDoctor() }, enabled = !vm.busy) {
                Text(stringResource(R.string.verificar))
            }
        }

        val d = vm.doctor
        when {
            vm.embeddedMode -> Text(
                stringResource(R.string.resumo_embutido),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            d == null -> Text(
                stringResource(R.string.resumo_nao_verifiquei),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            else -> {
                val problemas = d.optInt("problemas", 0)
                val avisos = d.optInt("avisos", 0)
                // O título é composto pela app (e não pelo texto do daemon): o daemon escreve em
                // português e trazia «1 problema(s) e 4 aviso(s)» — o sufixo «(s)» à mostra e a
                // mesma contagem repetida na linha de baixo (visto no S10+ em 2026-10-15).
                val titulo = when {
                    problemas == 0 && avisos == 0 -> stringResource(R.string.resumo_tudo_bem)
                    problemas == 0 -> stringResource(
                        R.string.resumo_sem_problemas,
                        pluralStringResource(R.plurals.resumo_avisos, avisos, avisos),
                    )
                    avisos == 0 -> pluralStringResource(R.plurals.resumo_problemas, problemas, problemas)
                    else -> stringResource(
                        R.string.resumo_com_ambos,
                        pluralStringResource(R.plurals.resumo_problemas, problemas, problemas),
                        pluralStringResource(R.plurals.resumo_avisos, avisos, avisos),
                    )
                }
                Text(
                    titulo,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (problemas == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.resumo_base, d.optString("base")),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.secondary,
                )
                val secoes = d.optJSONArray("secoes")
                if (secoes != null) {
                    for (i in 0 until secoes.length()) {
                        val sec = secoes.optJSONObject(i) ?: continue
                        val itens = sec.optJSONArray("itens") ?: continue
                        if (itens.length() == 0) continue
                        Etiqueta(sec.optString("titulo"), Modifier.padding(top = 6.dp))
                        for (j in 0 until itens.length()) {
                            val item = itens.optJSONObject(j) ?: continue
                            LinhaVerificacaoDeItem(item)
                        }
                    }
                    Text(
                        stringResource(R.string.resumo_verde, secoes.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * Uma linha do doctor. O texto vem com o valor colado («versão do kernel: 0.2.0-rc.2»); quando há
 * um «: » separa-se o valor para a coluna da direita, em monospace, como no design.
 */
@Composable
private fun LinhaVerificacaoDeItem(item: JSONObject) {
    val texto = item.optString("texto")
    val corte = texto.lastIndexOf(": ")
    if (corte > 0) {
        LinhaVerificacao(texto.substring(0, corte), item.optString("estado"), texto.substring(corte + 2))
    } else {
        LinhaVerificacao(texto, item.optString("estado"))
    }
}

// ------------------------------------------------------------------------------- fila
@Composable
private fun CartaoFila(vm: MainViewModel) {
    val pendentes = vm.tarefas.count { it.estado == Tarefa.PENDENTE }
    Cartao {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(stringResource(R.string.fila_titulo), Modifier.weight(1f))
            Text(
                if (pendentes == 0) stringResource(R.string.fila_vazia) else stringResource(R.string.fila_quantas, pendentes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (vm.tarefas.isEmpty()) {
            Text(
                stringResource(R.string.fila_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            vm.tarefas.take(8).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.texto, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Chip(
                        when (t.estado) {
                            Tarefa.FEITA -> stringResource(R.string.fila_feita)
                            Tarefa.FALHADA -> stringResource(R.string.fila_falhada)
                            else -> stringResource(R.string.fila_pendente)
                        },
                        cor = if (t.estado == Tarefa.PENDENTE) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
        }
    }
}
