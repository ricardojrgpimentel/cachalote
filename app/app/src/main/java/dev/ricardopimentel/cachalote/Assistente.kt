package dev.ricardopimentel.cachalote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * **Assistente de configuração inicial.**
 *
 * Existe por uma razão prática: quem instala isto pela primeira vez tem de decidir entre três
 * formas de correr o motor (root, Shizuku ou nada) — e essa decisão muda o que o agente consegue
 * fazer e, mais importante, **quanto o sistema operativo o trava**. Sem isto, ficava a adivinhar
 * (e uma app que não arranca por falta de uma permissão não explica nada a ninguém).
 *
 * Cada passo reaproveita o que a app já tem (os modos do `DaemonController`, o estado do daemon, a
 * chave do provedor, os interruptores das capacidades) — o assistente só **guia** e explica.
 */
@Composable
fun Assistente(vm: MainViewModel, onConcluir: () -> Unit) {
    var passo by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val passos = 6

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_marca),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.wizard_passo, passo + 1, passos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            TextButton(onClick = onConcluir) { Text(stringResource(R.string.wizard_saltar)) }
        }
        Spacer(Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (passo) {
                0 -> BemVindo()
                1 -> EscolhaDoModo(vm)
                2 -> LigarMotor(vm)
                3 -> DarAcesso(vm, ctx)
                4 -> DarChave(vm)
                5 -> EscolherPoderes(vm)
                else -> Pronto()
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (passo > 0) {
                OutlinedButton(onClick = { passo-- }) { Text(stringResource(R.string.voltar)) }
            }
            Spacer(Modifier.weight(1f))
            if (passo < passos - 1) {
                OutlinedButton(onClick = { passo++ }) { Text(stringResource(R.string.wizard_continuar)) }
            } else {
                OutlinedButton(onClick = onConcluir) { Text(stringResource(R.string.wizard_comecar)) }
            }
        }
    }
}

@Composable
private fun Passo(titulo: String, texto: String, conteudo: @Composable () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            conteudo()
        }
    }
}

@Composable
private fun BemVindo() {
    Passo(
        titulo = stringResource(R.string.wizard_bemvindo_titulo),
        texto = stringResource(R.string.wizard_bemvindo_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("• " + stringResource(R.string.wizard_bemvindo_p1), style = MaterialTheme.typography.bodySmall)
            Text("• " + stringResource(R.string.wizard_bemvindo_p2), style = MaterialTheme.typography.bodySmall)
            Text("• " + stringResource(R.string.wizard_bemvindo_p3), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A decisão que mais muda o resto: como o motor corre. Com o que existe no telefone à vista. */
@Composable
private fun EscolhaDoModo(vm: MainViewModel) {
    val temRoot = vm.rootOk == true
    val temShizuku = ShizukuBridge.available()
    Passo(
        titulo = stringResource(R.string.wizard_modo_titulo),
        texto = stringResource(R.string.wizard_modo_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OpcaoDeModo(
                titulo = stringResource(R.string.wizard_modo_root),
                detalhe = stringResource(R.string.wizard_modo_root_detalhe),
                risco = stringResource(R.string.wizard_modo_root_risco),
                disponivel = temRoot,
                recomendado = temRoot,
                escolhido = vm.modeKind == DaemonController.Mode.DAEMON,
                onEscolher = { vm.switchMode(DaemonController.Mode.DAEMON) },
            )
            OpcaoDeModo(
                titulo = stringResource(R.string.wizard_modo_shizuku),
                detalhe = stringResource(R.string.wizard_modo_shizuku_detalhe),
                risco = stringResource(R.string.wizard_modo_shizuku_risco),
                disponivel = temShizuku,
                recomendado = !temRoot && temShizuku,
                escolhido = vm.modeKind == DaemonController.Mode.SHIZUKU,
                onEscolher = { vm.switchMode(DaemonController.Mode.SHIZUKU) },
            )
            OpcaoDeModo(
                titulo = stringResource(R.string.wizard_modo_embutido),
                detalhe = stringResource(R.string.wizard_modo_embutido_detalhe),
                risco = stringResource(R.string.wizard_modo_embutido_risco),
                disponivel = true,
                recomendado = !temRoot && !temShizuku,
                escolhido = vm.modeKind == DaemonController.Mode.EMBEDDED,
                onEscolher = { vm.switchMode(DaemonController.Mode.EMBEDDED) },
            )
            Text(
                stringResource(
                    if (temRoot) R.string.wizard_modo_detectado_root
                    else if (temShizuku) R.string.wizard_modo_detectado_shizuku
                    else R.string.wizard_modo_detectado_nada,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun OpcaoDeModo(
    titulo: String,
    detalhe: String,
    risco: String,
    disponivel: Boolean,
    recomendado: Boolean,
    escolhido: Boolean,
    onEscolher: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (escolhido) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // No cartão escolhido (fundo primaryContainer) o texto tem de usar a cor "on" desse fundo,
        // senão fica escuro sobre teal e não se lê — foi o que se viu à primeira.
        val corTexto = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val corDetalhe = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (escolhido) "✓ " else "") + titulo,
                    fontWeight = FontWeight.SemiBold,
                    color = corTexto,
                    modifier = Modifier.weight(1f),
                )
                if (recomendado) {
                    Text(
                        stringResource(R.string.wizard_recomendado),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(detalhe, style = MaterialTheme.typography.bodySmall, color = corDetalhe)
            Text(risco, style = MaterialTheme.typography.bodySmall, color = corDetalhe)
            TextButton(onClick = onEscolher, enabled = disponivel) {
                Text(
                    if (!disponivel) stringResource(R.string.wizard_modo_indisponivel)
                    else if (escolhido) stringResource(R.string.wizard_modo_escolhido)
                    else stringResource(R.string.wizard_modo_usar),
                )
            }
        }
    }
}

@Composable
private fun LigarMotor(vm: MainViewModel) {
    Passo(
        titulo = stringResource(R.string.wizard_motor_titulo),
        texto = stringResource(R.string.wizard_motor_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EstadoLinha(stringResource(R.string.daemon_instalado), vm.daemonInstalled == true)
            EstadoLinha(stringResource(R.string.token_do_canal), vm.tokenState.isNotBlank() && vm.tokenState != "(por obter)")
            EstadoLinha(stringResource(R.string.motor_responde), vm.agentInfo.isNotBlank())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.startDaemon() }, enabled = !vm.busy) { Text(stringResource(R.string.ligar_daemon)) }
                OutlinedButton(onClick = { vm.obtainToken() }, enabled = !vm.busy) { Text(stringResource(R.string.obter_token)) }
                OutlinedButton(onClick = { vm.refreshStatus() }, enabled = !vm.busy) { Text(stringResource(R.string.atualizar)) }
            }
        }
    }
}

@Composable
private fun DarAcesso(vm: MainViewModel, ctx: android.content.Context) {
    Passo(
        titulo = stringResource(R.string.wizard_acesso_titulo),
        texto = stringResource(R.string.wizard_acesso_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EstadoLinha(stringResource(R.string.acessibilidade), vm.accessibilityOn)
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(stringResource(R.string.abrir_acessibilidade)) }
            Text(
                stringResource(R.string.wizard_acesso_nota),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun DarChave(vm: MainViewModel) {
    var valor by remember { mutableStateOf("") }
    Passo(
        titulo = stringResource(R.string.wizard_chave_titulo),
        texto = stringResource(R.string.wizard_chave_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EstadoLinha(stringResource(R.string.chave_provedor), vm.keyLength > 0)
            OutlinedTextField(
                value = valor,
                onValueChange = { valor = it },
                placeholder = { Text("sk-…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = { vm.saveKey(valor); valor = "" }, enabled = valor.isNotBlank() && !vm.busy) {
                Text(stringResource(R.string.guardar_no_daemon))
            }
            Text(
                stringResource(R.string.chave_explicacao),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun EscolherPoderes(vm: MainViewModel) {
    Passo(
        titulo = stringResource(R.string.wizard_poderes_titulo),
        texto = stringResource(R.string.wizard_poderes_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                listOf("notify", "screen", "tap", "type", "key", "screenshot").forEach { vm.setCapability(it, true) }
            }) { Text(stringResource(R.string.wizard_poderes_essenciais)) }
            CapabilityToggles(
                nomes = vm.capabilityNames,
                autorizadas = vm.capabilityToggles,
                onToggle = { cap, ligado -> vm.setCapability(cap, ligado) },
            )
            Text(
                stringResource(R.string.capacidades_explicacao),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun Pronto() {
    Passo(
        titulo = stringResource(R.string.wizard_pronto_titulo),
        texto = stringResource(R.string.wizard_pronto_texto),
    )
}

@Composable
private fun EstadoLinha(rotulo: String, feito: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (feito) "✓" else "•", color = if (feito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.size(8.dp))
        Text(rotulo, style = MaterialTheme.typography.bodySmall)
    }
}
