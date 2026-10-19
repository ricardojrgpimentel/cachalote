package dev.ricardopimentel.cachalote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 *
 * A forma — cabeçalho com a marca e «PASSO n DE 6», progresso segmentado, cartões com contorno e
 * botão principal a toda a largura — vem do design do Stitch «assistente de configuração inicial»
 * (ecrãs dos passos 1 e 2 gerados a 2026-10-14), que substituiu o anterior, que era feio.
 */
@Composable
fun Assistente(vm: MainViewModel, onConcluir: () -> Unit) {
    var passo by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val passos = 6
    val ultimo = passo == passos - 1

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_marca),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Etiqueta(stringResource(R.string.wizard_passo, passo + 1, passos))
            }
            TextButton(onClick = onConcluir) { Text(stringResource(R.string.wizard_saltar)) }
        }

        Spacer(Modifier.height(12.dp))
        ProgressoPassos(passo, passos)
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (passo) {
                0 -> BemVindo()
                1 -> EscolhaDoModo(vm)
                2 -> LigarMotor(vm)
                3 -> DarAcesso(vm, ctx)
                4 -> DarChave(vm)
                else -> EscolherPoderes(vm, ctx)
            }
        }

        Spacer(Modifier.height(10.dp))
        // Prosa, não etiqueta: vai na fonte de texto. O mono é para código e telemetria
        // (é o que o DESIGN.md diz), e uma frase inteira a 10px em mono lê-se mal.
        Text(
            stringResource(R.string.wizard_rodape),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (passo > 0) {
                OutlinedButton(onClick = { passo-- }, modifier = Modifier.weight(1f)) {
                    Text("\u2190 " + stringResource(R.string.voltar))
                }
            }
            Button(
                onClick = { if (ultimo) onConcluir() else passo++ },
                modifier = Modifier.weight(if (passo > 0) 1.6f else 1f),
            ) {
                Text(
                    if (ultimo) stringResource(R.string.wizard_comecar)
                    else stringResource(R.string.wizard_continuar) + "  \u2192",
                )
            }
        }
        if (passo == 0) {
            TextButton(onClick = onConcluir, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wizard_ja_configurado))
            }
        }
    }
}

/** Barra de progresso segmentada: um segmento por passo, os já feitos a teal. */
@Composable
private fun ProgressoPassos(atual: Int, total: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= atual) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
            )
        }
    }
}

@Composable
private fun Passo(titulo: String, texto: String, conteudo: @Composable () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Beneficio(stringResource(R.string.wizard_bemvindo_p1))
            Beneficio(stringResource(R.string.wizard_bemvindo_p2))
            Beneficio(stringResource(R.string.wizard_bemvindo_p3))
        }
    }
}

/** Uma linha de benefício: visto a teal e o texto ao lado (o design do passo 1). */
@Composable
private fun Beneficio(texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("\u2713", color = MaterialTheme.colorScheme.primaryContainer, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(10.dp))
        Text(texto, style = MaterialTheme.typography.bodySmall)
    }
}

/** A decisão que mais muda o resto: como o motor corre. Com o que existe no telefone à vista. */
@Composable
private fun EscolhaDoModo(vm: MainViewModel) {
    // `rootOk` é tri-estado: null = ainda não perguntámos (e não se pergunta no arranque, que dava
    // um diálogo de root a quem acabou de instalar a app). Enquanto não se sabe, o cartão do root
    // fica **escolhível** — é uma escolha, não um facto — e é ao escolhê-lo que a app pergunta.
    val temRoot = vm.rootOk == true
    val rootDesconhecido = vm.rootOk == null
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
                disponivel = temRoot || rootDesconhecido,
                recomendado = temRoot,
                escolhido = vm.modeKind == DaemonController.Mode.DAEMON,
                onEscolher = { vm.escolherModo(DaemonController.Mode.DAEMON) },
            )
            OpcaoDeModo(
                titulo = stringResource(R.string.wizard_modo_shizuku),
                detalhe = stringResource(R.string.wizard_modo_shizuku_detalhe),
                risco = stringResource(R.string.wizard_modo_shizuku_risco),
                // Escolhível mesmo com o Shizuku parado: o passo seguinte explica como o ligar,
                // e é isso que falta a quem instala a app de fresco.
                disponivel = true,
                recomendado = !temRoot && temShizuku,
                escolhido = vm.modeKind == DaemonController.Mode.SHIZUKU,
                onEscolher = { vm.escolherModo(DaemonController.Mode.SHIZUKU) },
            )
            OpcaoDeModo(
                titulo = stringResource(R.string.wizard_modo_embutido),
                detalhe = stringResource(R.string.wizard_modo_embutido_detalhe),
                risco = stringResource(R.string.wizard_modo_embutido_risco),
                disponivel = true,
                recomendado = !temRoot && !temShizuku,
                escolhido = vm.modeKind == DaemonController.Mode.EMBEDDED,
                onEscolher = { vm.escolherModo(DaemonController.Mode.EMBEDDED) },
            )
            Text(
                stringResource(
                    when {
                        temRoot -> R.string.wizard_modo_detectado_root
                        rootDesconhecido -> R.string.wizard_modo_detectado_desconhecido
                        temShizuku -> R.string.wizard_modo_detectado_shizuku
                        else -> R.string.wizard_modo_detectado_nada
                    },
                ),
                // Prosa, não telemetria: fonte de texto (a frase é longa).
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
        border = BorderStroke(
            1.dp,
            if (escolhido) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        // No cartão escolhido (fundo primaryContainer) o texto tem de usar a cor "on" desse fundo,
        // senão fica escuro sobre teal e não se lê — foi o que se viu à primeira.
        val corTexto = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val corDetalhe = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (escolhido) "\u2713 " else "") + titulo,
                    fontWeight = FontWeight.SemiBold,
                    color = corTexto,
                    modifier = Modifier.weight(1f),
                )
                if (recomendado) {
                    Text(
                        stringResource(R.string.wizard_recomendado).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(detalhe, style = MaterialTheme.typography.bodySmall, color = corDetalhe)
            Text(risco, style = MaterialTheme.typography.bodySmall, color = corDetalhe)
            // O TextButton herda a cor de conteúdo do tema (`primary`), que é #BEFFE7 — um tom
            // mais claro do próprio fundo do cartão escolhido (#38EFC2). Medido no S10+ em
            // 2026-10-15: 1,30:1, ou seja, praticamente invisível. Aqui segue a cor do cartão.
            TextButton(
                onClick = onEscolher,
                enabled = disponivel,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (escolhido) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    if (!disponivel) stringResource(R.string.wizard_modo_indisponivel)
                    else if (escolhido) stringResource(R.string.wizard_modo_escolhido)
                    else stringResource(R.string.wizard_modo_usar),
                )
            }
        }
    }
}

/**
 * Passo 3 — ligar o motor.
 *
 * O autor fez o assistente todo neste passo e ficou sem perceber o que os botões faziam: «Ligar
 * daemon» e «Obter token (root)» não diziam nada a quem nunca viu isto, e o «Atualizar» parecia
 * um botão de acção igual aos outros (S10+, 2026-10-16). Agora cada botão diz o que faz, os
 * rótulos seguem o modo escolhido (não se fala em root a quem escolheu Shizuku), e o actualizar
 * é o que sempre foi — uma consulta de estado —, por isso virou um ícone no canto do cartão.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LigarMotor(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shizuku = vm.modeKind == DaemonController.Mode.SHIZUKU
    val embutido = vm.modeKind == DaemonController.Mode.EMBEDDED
    Passo(
        titulo = stringResource(R.string.wizard_motor_titulo),
        texto = stringResource(R.string.wizard_motor_texto),
    ) {
        // Consulta o sistema ao entrar neste passo: é aqui que o utilizador está a olhar para o
        // estado do motor, e é aqui que faz sentido o diálogo do root (se o escolheu).
        LaunchedEffect(Unit) { vm.refreshStatus(consultarSistema = true) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Estado, com o botão de consultar no canto — é uma leitura, não uma acção.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.wizard_motor_estado),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { vm.refreshStatus(consultarSistema = true) },
                            enabled = !vm.busy,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.wizard_motor_atualizar),
                                tint = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (embutido) {
                        // No modo embutido não há daemon nem token: o que se prepara é o kernel
                        // dentro da app.
                        EstadoLinha(stringResource(R.string.wizard_motor_kernel), vm.daemonInstalled == true)
                    } else {
                        EstadoLinha(stringResource(R.string.daemon_instalado), vm.daemonInstalled == true)
                        EstadoLinha(stringResource(R.string.token_do_canal), vm.tokenOk)
                    }
                    EstadoLinha(stringResource(R.string.motor_responde), vm.agentInfo.isNotBlank())
                    if (embutido && vm.embeddedInfo.isNotBlank()) {
                        Text(
                            vm.embeddedInfo,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = Mono,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            // O aviso do Shizuku, com o botão que **pede** a permissão. Era isto que faltava por
            // completo: o pedido estava implementado e ninguém o chamava.
            if (shizuku && vm.precisoPermissaoShizuku) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Etiqueta(stringResource(R.string.shizuku_falta), cor = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.shizuku_falta_texto),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { vm.requestShizukuPermission(ctx as? android.app.Activity ?: return@Button) },
                            enabled = !vm.busy,
                        ) { Text(stringResource(R.string.shizuku_autorizar)) }
                        if (vm.shizukuState.isNotBlank()) {
                            Text(
                                vm.shizukuState,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = Mono,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            // Como ligar o Shizuku — o que faltava a quem escolhe este modo.
            if (shizuku) {
                Passos(
                    titulo = stringResource(R.string.shizuku_como_titulo),
                    itens = listOf(
                        stringResource(R.string.shizuku_como_1),
                        stringResource(R.string.shizuku_como_2),
                        stringResource(R.string.shizuku_como_3),
                    ),
                )
            }

            // Sem motor instalado, o primeiro cartão é o que o instala — aqui, no telefone. Era
            // este o passo que obrigava a ligar o telefone a um computador.
            if (embutido) {
                // No modo embutido o «motor» é o kernel extraído dentro da app: não há daemon, e
                // mostrar aqui o cartão de instalação root era pedir ao utilizador que instalasse
                // uma coisa que ele explicitamente não quer.
                if (vm.daemonInstalled != true) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.wizard_kernel_titulo),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.wizard_kernel_texto),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { vm.downloadKernel() },
                                enabled = !vm.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.wizard_kernel_botao)) }
                        }
                    }
                }
            } else if (vm.daemonInstalled != true) {
                CartaoInstalarMotor(
                    shizuku = shizuku,
                    fracao = vm.instalacaoFracao,
                    passo = vm.instalacaoPasso,
                    podeInstalar = !vm.busy,
                    onInstalar = { vm.instalarMotor(ctx as? android.app.Activity) },
                    onAutorizarShizuku = {
                        (ctx as? android.app.Activity)?.let { vm.requestShizukuPermission(it) }
                    },
                )
            }

            // As acções, cada uma com a sua explicação. No modo embutido não há daemon nem token
            // para ligar/obter: há um motor dentro da app, que se liga (ou reinicia) aqui.
            if (embutido) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(
                        onClick = { vm.restartEngine() },
                        enabled = !vm.busy && vm.daemonInstalled == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wizard_motor_ligar)) }
                    Text(
                        stringResource(R.string.wizard_motor_embutido_nota),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(
                        onClick = { vm.startDaemon() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (shizuku) R.string.ligar_motor_shizuku else R.string.ligar_daemon,
                            ),
                        )
                    }
                    Text(
                        stringResource(R.string.ligar_daemon_nota),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(
                        onClick = { vm.obtainToken() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (shizuku) R.string.obter_token_shizuku else R.string.obter_token,
                            ),
                        )
                    }
                    Text(
                        stringResource(R.string.obter_token_nota),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/** Uma lista numerada de instruções, como o design as desenha. */
@Composable
fun Passos(titulo: String, itens: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titulo, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            itens.forEachIndexed { i, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    )
                    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    val embutido = vm.modeKind == DaemonController.Mode.EMBEDDED
    Passo(
        titulo = stringResource(R.string.wizard_chave_titulo),
        texto = stringResource(R.string.wizard_chave_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // `keyPresent` e não `keyLength`: no modo Shizuku o estado vem do CLI, que só diz
            // «presente/ausente» — a chave estava gravada e o passo ficava ✗ (S10+, 2026-10-19).
            EstadoLinha(stringResource(R.string.chave_provedor), vm.keyPresent)
            OutlinedTextField(
                value = valor,
                onValueChange = { valor = it },
                placeholder = { Text("sk-…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = { vm.saveKey(valor); valor = "" }, enabled = valor.isNotBlank() && !vm.busy) {
                // Embutido não tem daemon: dizer «guardar no daemon» a quem escolheu «sem nada»
                // era a mesma mentira que o cartão de instalação root (visto a 2026-10-19).
                Text(stringResource(if (embutido) R.string.guardar_na_app else R.string.guardar_no_daemon))
            }
            Text(
                stringResource(if (embutido) R.string.chave_explicacao_embutida else R.string.chave_explicacao),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun EscolherPoderes(vm: MainViewModel, ctx: android.content.Context) {
    Passo(
        titulo = stringResource(R.string.wizard_poderes_titulo),
        texto = stringResource(R.string.wizard_poderes_texto),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                listOf("notify", "screen", "tap", "type", "key", "screenshot").forEach { vm.setCapability(it, true) }
                // A capacidade `notify` sem a permissão do Android não faz nada: o atalho tem de
                // pedir POST_NOTIFICATIONS, como o interruptor individual já faz. Faltava aqui —
                // numa instalação limpa a UI dizia que estava ligado e `pm` dizia `granted=false`
                // (S10+, 2026-10-19).
                (ctx as? android.app.Activity)?.let { vm.pedirNotificacoes(it) }
            }) { Text(stringResource(R.string.wizard_poderes_essenciais)) }
            CapabilityToggles(
                nomes = vm.capabilityNames,
                autorizadas = vm.capabilityToggles,
                onToggle = { cap, ligado -> vm.setCapability(cap, ligado) },
                // A permissão de notificações do Android é pedida **aqui**, quando o utilizador
                // liga a capacidade — e não mal a app abre, como estava (o autor apanhou-o).
                onPrecisaNotificacoes = { vm.pedirNotificacoes(ctx as? android.app.Activity ?: return@CapabilityToggles) },
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
private fun EstadoLinha(rotulo: String, feito: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // ✓ para o que está feito, **✗** para o que falta. Era um `•`, que se lê como um item de
        // lista e não como «isto ainda não aconteceu» (o autor notou-o no S10+ em 2026-10-16).
        Text(
            if (feito) "\u2713" else "\u2717",
            color = if (feito) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(8.dp))
        Text(rotulo, style = MaterialTheme.typography.bodySmall)
    }
}
