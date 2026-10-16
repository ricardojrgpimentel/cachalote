package dev.ricardopimentel.cachalote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Notificacoes.appEmPrimeiroPlano = true
    }

    override fun onPause() {
        super.onPause()
        Notificacoes.appEmPrimeiroPlano = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sem isto, o Compose desenha por baixo do teclado e o botão Enviar fica
        // inalcançável com o teclado aberto (apanhado em teste de UI).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // O agente usa notificações (android_notify): pedir já, para não falhar depois.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Serviço em primeiro plano: sem ele o Android pode matar a app a meio de um turno.
        CachaloteAgentService.start(this)
        setContent { CachaloteApp() }
    }
}

// O tema vive em Tema.kt, gerado do design (tools/design-to-compose.py).

@Composable
fun CachaloteApp() {
    val vm: MainViewModel = viewModel()
    // Resultado do diálogo de permissão do Shizuku (o vm só existe aqui dentro).
    DisposableEffect(Unit) {
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            vm.onShizukuPermissionResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener) }
        onDispose { runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) } }
    }
    CachaloteTema {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(0) }
            LaunchedEffect(Unit) { vm.refreshStatus() }

            // Uma conversa aberta ocupa o ecrã todo — **sem barra de abas e sem o cabeçalho da
            // app**, como um chat do WhatsApp: em cima só o voltar, o título e o estado; em baixo
            // só a barra de escrita. Voltar (seta ou gesto do sistema) regressa à lista.
            val emConversa = tab == 0 && vm.conversaAberta && vm.sessionId != null
            if (emConversa) {
                androidx.activity.compose.BackHandler { vm.fecharConversa() }
            }

            Scaffold(
                modifier = Modifier.imePadding(),
                bottomBar = {
                    // Durante o assistente não há barra: há um caminho a seguir, não abas.
                    if (vm.wizardConcluido && !emConversa) NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Chat, null) },
                            label = { Text(stringResource(R.string.aba_conversa)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.MonitorHeart, null) },
                            label = { Text(stringResource(R.string.aba_resumo)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Settings, null) },
                            label = { Text(stringResource(R.string.aba_ajustes)) },
                        )
                    }
                },
            ) { pad ->
                // A faixa de erro flutua **por cima** do ecrã (ver abaixo), e não como irmão que
                // empurra o conteúdo: enquanto estava à vista, o cabeçalho descia 228 px e todos os
                // alvos de toque se moviam com ele (medido no S10+ em 2026-10-15 — durante a
                // passagem toquei várias vezes no sítio errado por causa disso).
                Box(modifier = Modifier.padding(pad).fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Configuração inicial: o assistente ocupa o ecrã todo enquanto não estiver feita.
                    if (!vm.wizardConcluido) {
                        Assistente(vm) { vm.concluirWizard() }
                    }
                    if (!emConversa) Header(vm, busy = vm.busy)
                    vm.approval?.let { ap -> ApprovalDialog(vm, ap) }
                    vm.configPicker?.let { opt -> ConfigPickerDialog(vm, opt) }
                    if (vm.mostrarLicencas) LicensesDialog(vm)
                    LaunchedEffect(tab) {
                        // A lista de conversas é o ecrã da aba: recarrega-se sempre que se lá chega
                        // (o motor pode ter sessões novas — de outra app, de outro turno, do boot).
                        if (tab == 0) vm.refreshSessions()
                        if (tab == 1) vm.refreshDoctor()
                        if (tab == 2) vm.refreshCapabilities()
                    }
                    // Pedido de navegação vindo de outra secção (ex.: «Iniciar» do ecrã virtual sem
                    // autorização): muda de aba para os Ajustes, que abrem a secção pedida.
                    LaunchedEffect(vm.pedidoDeSecao) {
                        if (vm.pedidoDeSecao != null) tab = 2
                    }
                    when (tab) {
                        0 -> if (emConversa) ConversaScreen(vm) else ListaDeSessoes(vm)
                        1 -> ResumoScreen(vm)
                        else -> AjustesScreen(vm)
                    }
                }
                if (vm.error.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1520)),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                vm.error,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFC9D0),
                            )
                            TextButton(onClick = { vm.error = "" }) {
                                Text(stringResource(R.string.fechar), color = Color(0xFFFFC9D0))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun Header(vm: MainViewModel, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A marca do design (baleia a mergulhar): o mesmo vector de design/logo/marca.svg.
                Icon(
                    painter = painterResource(R.drawable.ic_marca),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(26.dp),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                // O chip do design («ON-DEVICE»): diz, de relance, como o motor está a correr.
                Chip(
                    when (vm.modeKind) {
                        DaemonController.Mode.DAEMON -> "root"
                        DaemonController.Mode.SHIZUKU -> "Shizuku"
                        else -> "on-device"
                    },
                )
            }
            Text(
                if (vm.connected) stringResource(R.string.cabecalho_motor_ligado) else stringResource(R.string.cabecalho_motor_desligado),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            // Consumo de contexto: o motor já o enviava em cada turno e a app deitava fora.
            // Fica discreto, mas permanente — é a única noção de custo que o utilizador tem.
            ConsumoDeContexto(vm.uso)
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
    }
}

/**
 * O motor pede autorização antes de uma operação sensível (ACP `session/request_permission`).
 * As opções vêm do motor (permitir uma vez / sempre / recusar); mostramo-las tal como vêm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalDialog(vm: MainViewModel, ap: PendingApproval) {
    // O pedido de autorização é um painel que sobe de baixo, como no design. O conteúdo vive no
    // `UiParts.PainelDeAutorizacao` (sem estado próprio, e por isso testável na JVM); aqui fica só
    // a folha e a ligação ao modelo. Antes era um AlertDialog com um botão por opção, que decidia
    // no toque — sem se ver o que se estava a escolher.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { vm.answerApproval(null) }, sheetState = sheet) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            PainelDeAutorizacao(
                requestId = ap.requestId,
                titulo = ap.title,
                detalhe = ap.detail,
                opcoes = ap.options,
                rodape = stringResource(
                    R.string.autorizacao_rodape,
                    android.os.Process.myPid(),
                    vm.modeKind.name.lowercase(),
                ),
                onDecidir = { vm.answerApproval(it) },
            )
        }
    }
}

/** Escolha de uma opção da sessão (modelo, esforço de raciocínio) — as escolhas vêm do motor. */
@Composable
private fun ConfigPickerDialog(vm: MainViewModel, opt: ConfigOption) {
    AlertDialog(
        onDismissRequest = { vm.configPicker = null },
        title = { Text(opt.name) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(opt.choices) { _, ch ->
                    val selected = ch.value == opt.currentValue
                    TextButton(
                        onClick = { vm.chooseOption(opt.id, ch.value) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            (if (selected) "✓ " else "") + ch.label,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.configPicker = null }) { Text(stringResource(R.string.fechar)) } },
    )
}

/**
 * O adaptador entre o modelo e a lista de conversas: resolve cada sessão do motor no que a linha
 * mostra. A prévia vem do daemon (a última mensagem lida do ficheiro da sessão) e, para a conversa
 * aberta, do transcript, que é mais fresco. Sem metadados nenhuns (modo embutido), a linha mostra o
 * identificador curto em vez de um resumo inventado.
 */
@Composable
private fun ListaDeSessoes(vm: MainViewModel) {
    var aApagar by remember { mutableStateOf<String?>(null) }
    val previaDesconhecida = stringResource(R.string.sessao_curta)
    val ultima = vm.transcript.lastOrNull { it.role != "system" }
    val items = vm.sessions.map { s ->
        val activa = s.id == vm.sessionId
        ItemConversa(
            id = s.id,
            titulo = s.title,
            previa = when {
                // A conversa aberta tem o texto à mão (e é o mais fresco).
                activa && ultima != null -> ultima.text.replace(Regex("\\s+"), " ").trim().take(90)
                // As outras: a última mensagem que o daemon leu do ficheiro da sessão.
                s.previa.isNotBlank() -> s.previa.take(90)
                // Sem metadados (modo embutido) não se inventa uma prévia.
                else -> previaDesconhecida.format(s.id.take(8))
            },
            quando = quandoCurto(s.updatedAt),
            activa = activa,
            falhou = activa && vm.transcript.lastOrNull()?.role == "error",
        )
    }
    ListaDeConversas(
        conversas = items,
        onAbrir = { vm.resumeSession(it) },
        onNova = { vm.newSession() },
        onApagar = { aApagar = it },
    )

    // Apagar é sem volta: pergunta-se primeiro, com o título da conversa à vista — para não se
    // apagar a errada (o toque longo é fácil de disparar sem querer).
    aApagar?.let { id ->
        val titulo = items.firstOrNull { it.id == id }?.titulo ?: id.take(8)
        AlertDialog(
            onDismissRequest = { aApagar = null },
            title = { Text(stringResource(R.string.apagar_conversa)) },
            text = { Text(stringResource(R.string.apagar_conversa_texto, titulo)) },
            confirmButton = {
                TextButton(onClick = {
                    aApagar = null
                    vm.apagarSessao(id)
                }) { Text(stringResource(R.string.apagar)) }
            },
            dismissButton = {
                TextButton(onClick = { aApagar = null }) { Text(stringResource(R.string.cancelar)) }
            },
        )
    }
}

/**
 * Quando, no formato curto do design («14/10 21:14»). O motor dá o instante em UTC; converte-se
 * para a hora do telefone — mostrar a hora de Greenwich num telefone português era um erro de uma
 * hora que ninguém percebia de onde vinha.
 */
private fun quandoCurto(iso: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    "%02d/%02d %02d:%02d".format(t.dayOfMonth, t.monthValue, t.hour, t.minute)
}.getOrElse { iso.replace("T", " ").take(16) }

/** Uma entrada do menu dos Ajustes: rótulos (recursos), ícone e a secção que abre. */
private class EntradaDeAjuste(
    val titulo: Int,
    val detalhe: Int?,
    val icone: androidx.compose.ui.graphics.vector.ImageVector,
    val destino: String,
)

private val ENTRADAS_DE_AJUSTE = listOf(
    EntradaDeAjuste(R.string.motor, R.string.motor_detalhe, Icons.Filled.Memory, "motor"),
    EntradaDeAjuste(R.string.chave_provedor, R.string.chave_detalhe, Icons.Filled.Key, "provedor"),
    EntradaDeAjuste(R.string.capacidades_titulo, R.string.capacidades_detalhe, Icons.Filled.Tune, "capacidades"),
    EntradaDeAjuste(R.string.acessibilidade, R.string.acessibilidade_detalhe, Icons.Filled.Accessibility, "acessibilidade"),
    EntradaDeAjuste(R.string.areas_bash, R.string.areas_detalhe, Icons.Filled.Terminal, "areas"),
    EntradaDeAjuste(R.string.mcp_titulo, R.string.mcp_detalhe, Icons.Filled.Hub, "mcp"),
    EntradaDeAjuste(R.string.vscreen_titulo, R.string.vscreen_detalhe, Icons.Filled.PhoneAndroid, "vscreen"),
    EntradaDeAjuste(R.string.backup_e_licencas, R.string.backup_detalhe, Icons.Filled.Backup, "backup"),
)

/**
 * O que o agente pode fazer no telefone: interruptores por capacidade e o registo do
 * que ele já pediu. É a barreira de segurança do projecto (ver D10 no plano) — no
 * Android não há sandbox a travar o agente, por isso quem decide é o utilizador.
 */
@Composable
private fun AjustesScreen(vm: MainViewModel) {
    var secao by rememberSaveable { mutableStateOf("menu") }

    // Outra secção pode pedir para abrir uma concreta (o ecrã virtual manda para «Capacidades»
    // quando lhe falta uma autorização). Consome-se uma vez.
    LaunchedEffect(vm.pedidoDeSecao) {
        vm.pedidoDeSecao?.let {
            secao = it
            vm.pedidoDeSecao = null
        }
    }

    // O «voltar» do sistema não pode fechar a app a partir de uma secção: volta ao menu.
    // (Sem isto, o BACK numa secção terminava a Activity — medido no S10+ em 2026-10-14.)
    if (secao != "menu") {
        androidx.activity.compose.BackHandler { secao = "menu" }
    }

    // O menu é uma lista, com a forma do desenho: título de página, um grupo de linhas altas
    // (72dp, ícone em círculo tonal, título + detalhe, chevron e separador recuado) — como as
    // Definições do Android, e não as linhas finas que aqui estavam.
    if (secao == "menu") {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.ajustes_titulo),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.ajustes_modulos, 8),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    ENTRADAS_DE_AJUSTE.forEachIndexed { i, e ->
                        LinhaDeAjuste(
                            titulo = stringResource(e.titulo),
                            detalhe = e.detalhe?.let { stringResource(it) },
                            icone = e.icone,
                        ) { secao = e.destino }
                        // Sem separador depois da última: o cartão já fecha a lista.
                        if (i < ENTRADAS_DE_AJUSTE.lastIndex) DivisoriaDeAjuste()
                    }
                }
            }

            // Repetir o assistente fica fora do grupo, destacado (como no design).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
            ) {
                LinhaDeAjuste(
                    titulo = stringResource(R.string.wizard_repetir),
                    detalhe = stringResource(R.string.ajustes_repetir_detalhe),
                    icone = Icons.Filled.RestartAlt,
                ) { vm.repetirWizard() }
            }

            // O aviso de perigo, em cartão próprio (o «PERIGO» do design).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Etiqueta(stringResource(R.string.perigo), Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.aviso_root),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    // Secção aberta: ecrã próprio. Cabeçalho fixo com o «voltar» e **um só** contentor com
    // scroll — as secções já não trazem o seu por dentro. Era isso que rebentava a app com
    // «Vertically scrollable component was measured with an infinity maximum height
    // constraints» (reproduzido no S10+ em 2026-10-14, ao abrir «Motor» e «Chave do provedor»;
    // as duas secções tinham `fillMaxSize().verticalScroll()` dentro do scroll dos Ajustes).
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { secao = "menu" }) { Text("\u2190 " + stringResource(R.string.ajustes_titulo)) }
            Text(
                tituloDaSecao(secao),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (secao) {
                "motor" -> {
                    StatusScreen(vm)
                    CartaoTesteExecucao(vm)
                    CartaoKernelDownload(vm)
                }
                "provedor" -> KeyScreen(vm)
                "capacidades" -> {
                    CartaoCapacidades(vm)
                    CartaoRegisto(vm)
                }
                "acessibilidade" -> CartaoAcessibilidade()
                "areas" -> CartaoAreasBash(vm)
                "mcp" -> CartaoMcp(vm)
                "vscreen" -> CartaoVscreen(vm)
                "backup" -> {
                    CartaoBackup(vm)
                    CartaoLicencas(vm)
                }
            }
            Text(
                stringResource(R.string.aviso_root),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** Título da secção aberta, para o cabeçalho. */
@Composable
private fun tituloDaSecao(secao: String): String = stringResource(
    when (secao) {
        "motor" -> R.string.motor
        "provedor" -> R.string.chave_provedor
        "capacidades" -> R.string.capacidades_titulo
        "acessibilidade" -> R.string.acessibilidade
        "areas" -> R.string.areas_bash
        "mcp" -> R.string.mcp_titulo
        "vscreen" -> R.string.vscreen_titulo
        else -> R.string.backup_e_licencas
    },
)

@Composable
private fun CartaoAcessibilidade() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accessibilityOn = CachaloteAccessibilityService.isEnabled()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.acessibilidade), fontWeight = FontWeight.SemiBold)
            Text(
                if (accessibilityOn) stringResource(R.string.acessibilidade_servico_ligado)
                else stringResource(R.string.acessibilidade_servico_desligado),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(stringResource(R.string.abrir_acessibilidade)) }
        }
    }
}

/** Testa se o Node embutido na APK corre a partir da biblioteca nativa. */
@Composable
private fun CartaoTesteExecucao(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.testar_execucao), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.testar_execucao_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(vm.embedTest, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.testEmbeddedNode() }, enabled = !vm.busy) { Text(stringResource(R.string.testar_execucao)) }
        }
    }
}

/** Onde o modo embutido vai buscar o kernel (por download, em vez de dentro da APK). */
@Composable
private fun CartaoKernelDownload(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.kernel_titulo), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.kernel_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = vm.kernelUrlText,
                onValueChange = { vm.kernelUrlText = it },
                placeholder = { Text(stringResource(R.string.placeholder_url)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
            OutlinedButton(onClick = { vm.saveKernelUrl() }, enabled = !vm.busy) { Text(stringResource(R.string.gravar_url)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartaoCapacidades(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.capacidades_titulo), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.capacidades_explicacao),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, true) } }) {
                    Text(stringResource(R.string.autorizar_todas))
                }
                OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, false) } }) {
                    Text(stringResource(R.string.negar_todas))
                }
            }
            Spacer(Modifier.height(4.dp))
            CapabilityToggles(
                nomes = vm.capabilityNames,
                autorizadas = vm.capabilityToggles,
                onToggle = { cap, ligado -> vm.setCapability(cap, ligado) },
            )
        }
    }
}

@Composable
private fun CartaoRegisto(vm: MainViewModel) {
    // Sem isto o registo só se actualizava ao mudar de aba: abrir a secção logo a seguir a uma
    // acção mostrava «ainda não houve pedidos» com a acção já gravada (visto no S10+ em 2026-10-15).
    LaunchedEffect(Unit) { vm.refreshCapabilities() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.registo), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearAudit() }) { Text(stringResource(R.string.limpar)) }
            }
            if (vm.audit.isEmpty()) {
                Text(stringResource(R.string.sem_pedidos), style = MaterialTheme.typography.bodySmall)
            } else {
                vm.audit.take(25).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoAreasBash(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.areas_bash), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.areas_bash_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = vm.allowedRootsText,
                onValueChange = { vm.allowedRootsText = it },
                placeholder = { Text(stringResource(R.string.placeholder_areas)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.saveAllowedRoots() }, enabled = !vm.busy) { Text(stringResource(R.string.gravar_reiniciar)) }
                if (vm.allowedRootsSaved.isNotBlank()) {
                    Text(vm.allowedRootsSaved, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartaoMcp(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mcp_titulo), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.mcp_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            // O exemplo é uma linha de JSON com 159 caracteres (190 em português) e **sem um
            // único espaço**: num Text normal o motor de texto parte-o a meio dos identificadores
            // e o cartão cresce para 8–10 linhas estreitas (visto no S10+ em 2026-10-15). Aqui
            // fica em mono e rola na horizontal, que é como se lê um comando.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.mcp_exemplo),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.secondary,
                    softWrap = false,
                )
            }
            Text(
                stringResource(R.string.mcp_aviso),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = vm.mcpJsonText,
                onValueChange = { vm.mcpJsonText = it },
                placeholder = { Text(stringResource(R.string.placeholder_mcp)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { vm.saveMcpServers() }, enabled = !vm.busy) { Text(stringResource(R.string.gravar_mcp)) }
                if (vm.mcpSaved.isNotBlank()) {
                    Text(vm.mcpSaved, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * O ecrã virtual, como o desenho o pede ([`design/ecras/ecra-virtual.png`]): o monitor com a
 * moldura, o estado da pré-visualização, a resolução e a frequência, a linha técnica em
 * monospace e a latência **medida**; os quatro controlos em grelha; o cartão do isolamento; e o
 * registo em monospace com os comandos com que o agente põe apps lá dentro.
 *
 * É a última peça da comparação ecrã a ecrã do design do Stitch (ver STATUS §61–§66).
 */
@Composable
private fun CartaoVscreen(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    var copiado by remember { mutableStateOf(false) }
    val id = vm.vscreenDisplayId
    // Antes do primeiro `Iniciar` não há display nenhum: os comandos mostram o marcador, para
    // ninguém os copiar com um `--display 0` (que é o ecrã físico, não o virtual).
    val idTexto = if (id > 0) id.toString() else "<id>"
    // Os três comandos do desenho, com o display que existe **neste** telefone. O pacote da
    // calculadora era `com.android.calculator2` fixo — que não existe em muitos aparelhos (o
    // LineageOS deste usa outro). Descobre-se o que está instalado e, se não houver nenhum, o
    // comando fica com um marcador em vez de apontar para uma app que não existe.
    val pacoteCalculadora = remember {
        listOf(
            "com.android.calculator2",
            "com.google.android.calculator",
            "org.lineageos.calculator",
            "com.android.calculator",
        ).firstOrNull { p ->
            runCatching {
                vm.getApplication<android.app.Application>()
                    .packageManager.getPackageInfo(p, 0)
            }.isSuccess
        }
    }
    val comandos = listOf(
        if (pacoteCalculadora != null) {
            "am start --display $idTexto -n $pacoteCalculadora/.Calculator"
        } else {
            "am start --display $idTexto -n <pacote>/<activity>"
        },
        "input -d $idTexto tap 105 838",
        "input -d $idTexto keyevent KEYCODE_BACK",
    )

    // Ao vivo: um fotograma a cada 1,5 s enquanto o interruptor estiver ligado e o ecrã virtual
    // existir. É o que deixa o dono *ver* o agente a trabalhar.
    if (vm.vscreenAoVivo && vm.vscreenRunning) {
        LaunchedEffect(vm.vscreenRunning, vm.vscreenAoVivo) {
            while (true) {
                vm.vscreenRefresh(silencioso = true)
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Etiqueta(stringResource(R.string.vscreen_etiqueta))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.vscreen_titulo_longo),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ChipComPonto(
                texto = stringResource(
                    R.string.vscreen_display_id,
                    if (vm.vscreenRunning) id.toString() else "\u2014",
                ),
                cor = if (vm.vscreenRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
        }

        // ---- o monitor ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (textoChip, corChip) = when {
                        vm.vscreenRunning && vm.vscreenAoVivo ->
                            stringResource(R.string.ao_vivo) to MaterialTheme.colorScheme.primary
                        vm.vscreenRunning ->
                            stringResource(R.string.vscreen_em_pausa) to MaterialTheme.colorScheme.secondary
                        else ->
                            stringResource(R.string.vscreen_parado) to MaterialTheme.colorScheme.secondary
                    }
                    ChipComPonto(textoChip, corChip)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (vm.vscreenLargura > 0) "${vm.vscreenLargura}\u00D7${vm.vscreenAltura} @ ${vm.vscreenHz} Hz" else "\u2014",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Etiqueta(stringResource(R.string.vscreen_render))
                }

                // A moldura: o fotograma vive aqui dentro, com o contorno do desenho.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = vm.vscreenBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.fotograma_desc),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            if (vm.vscreenRunning) stringResource(R.string.vscreen_sem_fotograma, id)
                            else stringResource(R.string.vscreen_sem_ecra),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 44.dp),
                        )
                    }
                }

                Text(
                    stringResource(
                        R.string.vscreen_rodape,
                        idTexto,
                        if (vm.vscreenLargura > 0) "${vm.vscreenLargura}\u00D7${vm.vscreenAltura}" else "\u2014",
                        vm.vscreenKb,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ponto(if (vm.vscreenRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(R.string.vscreen_latencia, vm.vscreenLatenciaMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // Sem a autorização da capacidade, o «Iniciar» só dava um erro e o utilizador tinha de
        // descobrir sozinho onde se autoriza. Aqui diz-se e leva-se lá.
        val vscreenAutorizado = vm.capabilityToggles["vscreen_start"] == true
        if (!vscreenAutorizado) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.vscreen_falta_autorizacao),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.pedirSecao("capacidades") }) {
                        Text(stringResource(R.string.vscreen_abrir_capacidades))
                    }
                }
            }
        }

        VscreenControls(
            rodando = vm.vscreenRunning,
            aoVivo = vm.vscreenAoVivo,
            enabled = !vm.busy,
            onStart = { vm.vscreenStart() },
            onRefresh = { vm.vscreenRefresh() },
            onStop = { vm.vscreenStop() },
            onAoVivo = { vm.vscreenAoVivo = it },
        )

        // ---- porque é que isto é seguro (o cartão do desenho) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.vscreen_isolamento_titulo),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.vscreen_texto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u2713", color = MaterialTheme.colorScheme.primaryContainer)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.vscreen_isolamento_nota), style = MaterialTheme.typography.bodySmall)
                }
                Chip(stringResource(R.string.vscreen_isolamento_chip))
            }
        }

        // ---- o registo: os comandos, como no desenho ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ponto(MaterialTheme.colorScheme.error, 8)
                    Spacer(Modifier.size(5.dp))
                    Ponto(MaterialTheme.colorScheme.secondary, 8)
                    Spacer(Modifier.size(5.dp))
                    Ponto(MaterialTheme.colorScheme.primaryContainer, 8)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "$ cachalote-agent --display-target=$idTexto",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(comandos.joinToString("\n")))
                            copiado = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.vscreen_copiar),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    comandos.forEachIndexed { i, cmd ->
                        Row {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = Mono,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                cmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = Mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (copiado) {
                        Text(
                            stringResource(R.string.vscreen_copiado),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\u2713", color = MaterialTheme.colorScheme.primaryContainer)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.vscreen_registo_nota),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** O ponto de estado (verde a correr, apagado parado) — está em meia dúzia de sítios do design. */
@Composable
private fun Ponto(cor: Color, tamanho: Int = 7) {
    Box(modifier = Modifier.size(tamanho.dp).clip(CircleShape).background(cor))
}

/** Pill com um ponto à esquerda: o «AO VIVO» e o `display_id=2` do desenho. */
@Composable
private fun ChipComPonto(texto: String, cor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ponto(cor, 6)
        Spacer(Modifier.size(6.dp))
        Text(
            texto.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = cor,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartaoBackup(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.backup_titulo), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.backup_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            val exportar = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
            ) { uri -> if (uri != null) vm.exportarDefinicoes(uri) }
            val importar = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri -> if (uri != null) vm.importarDefinicoes(uri) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { exportar.launch(Backup.nomeFicheiro()) },
                    enabled = !vm.busy,
                ) { Text(stringResource(R.string.backup_exportar)) }
                OutlinedButton(
                    onClick = { importar.launch(arrayOf("application/json", "*/*")) },
                    enabled = !vm.busy,
                ) { Text(stringResource(R.string.backup_importar)) }
            }
            if (vm.backupAviso.isNotBlank()) {
                Text(vm.backupAviso, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CartaoLicencas(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.licencas_titulo), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.licencas_texto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedButton(onClick = { vm.carregarLicencas() }, enabled = !vm.busy) {
                Text(stringResource(R.string.ver_licencas))
            }
        }
    }
}

/**
 * Primeiros passos: mostra o que falta para o agente funcionar, item a item, com o botão
 * que resolve cada um. Desaparece quando está tudo pronto.
 */
@Composable
private fun OnboardingCard(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accessibility = vm.accessibilityOn
    val allowed = vm.capabilityToggles.count { it.value }
    // Os rótulos vinham todos escritos à mão em português, dentro do código: com a app em inglês
    // este cartão — o primeiro que um utilizador vê — ficava em português (visto no S10+ em
    // 2026-10-15). Passaram a recursos.
    val sKernel = stringResource(R.string.passos_kernel_extraido)
    val sAutomatico = stringResource(R.string.passos_automatico)
    val sMotorEmbutido = stringResource(R.string.passos_motor_embutido)
    val sVerificar = stringResource(R.string.passos_verificar)
    val sChave = stringResource(R.string.passos_chave_provedor)
    val sNaAbaChave = stringResource(R.string.passos_no_separador_chave)
    val sA11y = stringResource(R.string.passos_acessibilidade_ligada)
    val sAbrirA11y = stringResource(R.string.passos_abrir_acessibilidade)
    val sCapacidades = stringResource(R.string.passos_capacidades_autorizadas)
    val sNaAbaPoderes = stringResource(R.string.passos_no_separador_poderes)
    val sDaemon = stringResource(R.string.passos_daemon_instalado)
    val sLigarDaemon = stringResource(R.string.passos_ligar_daemon)
    val sRoot = stringResource(R.string.passos_root_concedido)
    val sObterToken = stringResource(R.string.passos_obter_token)
    val sToken = stringResource(R.string.passos_token_canal)
    val abrirA11y: Pair<String, () -> Unit> = sAbrirA11y to {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    val checks = if (vm.embeddedMode) listOf(
        Triple(sKernel, vm.daemonInstalled != null && vm.embeddedInfo.contains("extraído"), sAutomatico to {}),
        Triple(sMotorEmbutido, vm.embeddedInfo.contains("a correr") || vm.embeddedInfo.contains("extraído"), sVerificar to {}),
        Triple(sChave, vm.keyPresent, sNaAbaChave to {}),
        Triple(sA11y, accessibility, abrirA11y),
        Triple(sCapacidades, allowed > 0, sNaAbaPoderes to {}),
    ) else listOf(
        Triple(sDaemon, vm.daemonInstalled == true, sLigarDaemon to { vm.startDaemon() }),
        Triple(sRoot, vm.rootOk == true, sObterToken to { vm.obtainToken() }),
        Triple(sToken, vm.tokenOk, sObterToken to { vm.obtainToken() }),
        Triple(sChave, vm.keyPresent, sNaAbaChave to {}),
        Triple(sA11y, accessibility, abrirA11y),
        Triple(sCapacidades, allowed > 0, sNaAbaPoderes to {}),
    )
    val missing = checks.count { !it.second }
    if (missing == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A44)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.primeiros_passos, missing), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshStatus(); vm.refreshCapabilities() }, enabled = !vm.busy) { Text(stringResource(R.string.verificar)) }
            }
            checks.forEach { (label, done, action) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "✓" else "•", modifier = Modifier.width(20.dp))
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = if (done) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                    )
                    if (!done && action.first.isNotBlank() && !action.first.startsWith("(")) {
                        TextButton(onClick = action.second, enabled = !vm.busy) { Text(action.first) }
                    } else if (!done) {
                        Text(action.first, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusScreen(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Sem scroll próprio: quem rola é o contentor da secção (ver AjustesScreen).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingCard(vm)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.motor), fontWeight = FontWeight.SemiBold)
                Text(
                    when (vm.modeKind) {
                        DaemonController.Mode.DAEMON -> stringResource(R.string.modo_daemon_texto)
                        DaemonController.Mode.SHIZUKU -> stringResource(R.string.modo_shizuku_texto)
                        DaemonController.Mode.EMBEDDED -> stringResource(R.string.modo_embutido_texto)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        DaemonController.Mode.DAEMON to "root",
                        DaemonController.Mode.SHIZUKU to "Shizuku",
                        DaemonController.Mode.EMBEDDED to "embutido",
                    ).forEach { (kind, label) ->
                        FilterChip(
                            selected = vm.modeKind == kind,
                            onClick = { vm.switchMode(kind) },
                            enabled = !vm.busy,
                            label = { Text(label) },
                        )
                    }
                }
                if (vm.shizukuState.isNotBlank()) {
                    Text(vm.shizukuState, style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
                }
                if (vm.modeKind == DaemonController.Mode.SHIZUKU) {
                    // Se o arranque falhou por falta de permissão, pedimo-la sem o utilizador ter
                    // de descobrir o botão (o diálogo do Shizuku precisa de uma Activity).
                    LaunchedEffect(vm.precisoPermissaoShizuku) {
                        if (vm.precisoPermissaoShizuku) {
                            vm.precisoPermissaoShizuku = false
                            vm.requestShizukuPermission(ctx as android.app.Activity)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.requestShizukuPermission(ctx as android.app.Activity) },
                        enabled = !vm.busy,
                    ) { Text(stringResource(R.string.autorizar_no_shizuku)) }
                }
                if (vm.modeKind == DaemonController.Mode.EMBEDDED) {
                    OutlinedButton(onClick = { vm.downloadKernel() }, enabled = !vm.busy) {
                        Text(stringResource(R.string.descarregar_kernel))
                    }
                }
                if (vm.embeddedInfo.isNotBlank()) {
                    Text(vm.embeddedInfo, style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (vm.embeddedMode) "Motor" else "Daemon", fontWeight = FontWeight.SemiBold)
                Text(
                    vm.daemonInfo,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.acesso), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.root_rotulo, when (vm.rootOk) { true -> stringResource(R.string.root_concedido); false -> stringResource(R.string.root_negado); null -> stringResource(R.string.root_desconhecido) }))
                Text(stringResource(R.string.token_rotulo, vm.tokenState))
                Text(
                    stringResource(R.string.diagnostico, android.os.Process.myPid(), vm.accessibilityOn.toString(), vm.a11yTicks, CachaloteAccessibilityService.isEnabled().toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.motor_rotulo, vm.agentInfo.ifBlank { stringResource(R.string.handshake_pendente) }))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { vm.refreshStatus() }, enabled = !vm.busy) { Text(stringResource(R.string.atualizar)) }
            OutlinedButton(onClick = { vm.startDaemon() }, enabled = !vm.busy) { Text(stringResource(R.string.ligar_daemon)) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.obtainToken() }, enabled = !vm.busy) { Text(stringResource(R.string.obter_token)) }
            OutlinedButton(onClick = { vm.restartEngine() }, enabled = !vm.busy) { Text(stringResource(R.string.reiniciar_motor)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (CachaloteAgentService.running) stringResource(R.string.servico_ligado)
                else stringResource(R.string.servico_desligado),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                val ctx2 = ctx
                if (CachaloteAgentService.running) CachaloteAgentService.stop(ctx2)
                else CachaloteAgentService.start(ctx2)
            }) { Text(if (CachaloteAgentService.running) stringResource(R.string.servico_desligar) else stringResource(R.string.servico_ligar)) }
        }
        OutlinedButton(onClick = {
            // Arranca o motor do perfil web (se preciso) e abre a interface completa.
            vm.busy = true
            ctx.startActivity(android.content.Intent(ctx, WebModeActivity::class.java))
        }, enabled = !vm.busy) { Text(stringResource(R.string.modo_completo)) }
    }
}

/** Uma mensagem da conversa: bolha do utilizador, cartão do agente, cartão de erro. */
@Composable
private fun CartaoDeMensagem(line: ChatLine) {
    val ehUser = line.role == "user"
    val ehErro = line.role == "error"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // O erro fica discreto (fundo igual aos outros) e é o contorno e a etiqueta que o
            // marcam — em vermelho cheio era agressivo a mais, e o design não é assim.
            containerColor = if (ehUser) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            1.dp,
            if (ehErro) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                ehErro -> Etiqueta(stringResource(R.string.etiqueta_erro), cor = MaterialTheme.colorScheme.error)
                ehUser -> Etiqueta(stringResource(R.string.etiqueta_tu))
                else -> Etiqueta(stringResource(R.string.etiqueta_agente))
            }
            Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ehErro) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
}

/** O cartão de uma ferramenta: nome em monospace, chip do estado, à maneira do design. */
@Composable
private fun CartaoFerramenta(line: ChatLine) {
    val estado = line.estado.orEmpty()
    val texto: String
    val cor: Color
    when {
        estado.startsWith("completed") -> {
            texto = stringResource(R.string.ferramenta_concluida)
            cor = MaterialTheme.colorScheme.primaryContainer
        }
        estado.startsWith("failed") -> {
            texto = stringResource(R.string.ferramenta_falhada)
            cor = MaterialTheme.colorScheme.error
        }
        // Linhas antigas (retomadas de uma sessão gravada antes de o estado ser campo próprio):
        // só se sabe se acabaram. Sem isto ficavam eternamente «a correr».
        estado.isBlank() && line.done -> {
            texto = stringResource(R.string.ferramenta_concluida)
            cor = MaterialTheme.colorScheme.primaryContainer
        }
        else -> {
            texto = stringResource(R.string.ferramenta_a_correr)
            cor = MaterialTheme.colorScheme.secondary
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = Mono,
                modifier = Modifier.weight(1f),
            )
            Chip(texto, cor)
        }
    }
}

@Composable
private fun KeyScreen(vm: MainViewModel) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    // Sem scroll próprio: quem rola é o contentor da secção (ver AjustesScreen).
    // A forma segue o ecrã «Chave de API» do Stitch: etiqueta + título, cartão da chave com o
    // chip «HTTPS ONLY», botão principal a toda a largura e o cartão de privacidade.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Etiqueta(stringResource(R.string.chave_sistema))
        Text(
            stringResource(R.string.chave_acesso),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chave_provedor),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(stringResource(R.string.chave_https))
                }
                Text(
                    stringResource(R.string.chave_explicacao),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text("DEEPSEEK_API_KEY") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {}),
                    supportingText = { Text(stringResource(R.string.chave_segredo)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.keyPresent) stringResource(R.string.chave_presente, vm.keyLength, vm.keyUpdatedAt)
                        else stringResource(R.string.chave_ausente),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.esconder else R.string.mostrar))
                    }
                }
            }
        }

        // O botão principal é o que interessa neste ecrã: largura toda, como no design.
        Button(
            onClick = { vm.saveKey(value); value = "" },
            enabled = !vm.busy && value.length >= 8,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.guardar_no_daemon)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.refreshKeyStatus() },
                enabled = !vm.busy,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.verificar_estado)) }
            OutlinedButton(
                onClick = { vm.removeKey() },
                enabled = !vm.busy,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.remover_chave)) }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Etiqueta(stringResource(R.string.chave_privacidade), Modifier.weight(1f))
                    Chip(stringResource(R.string.chave_auditavel))
                }
                Text(
                    stringResource(R.string.chave_privacidade_texto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinhaValor(stringResource(R.string.chave_posix), "0600 (-rw-------)")
                LinhaValor(stringResource(R.string.chave_telemetria), stringResource(R.string.chave_zero))
                LinhaValor(stringResource(R.string.chave_armazenamento), stringResource(R.string.chave_efemero))
            }
        }
    }
}

/**
 * A **conversa aberta**, a ocupar o ecrã todo. É o ecrã a que se chega tocando numa linha da lista
 * e — como num chat do WhatsApp — **não tem barra de abas nem o cabeçalho da app**: em cima só o
 * voltar, o título e o estado; em baixo só a barra de escrita.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConversaScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Levar a vista ao fim: o último item do transcript — ou o «A trabalhar…», que é o item
    // seguinte enquanto o turno corre. Sem contar com ele, o indicador nascia sempre abaixo da
    // área visível e não se via nada (visto no S10+ em 2026-10-15).
    LaunchedEffect(vm.transcript.size, vm.busy) {
        val alvo = if (vm.busy) vm.transcript.size else vm.transcript.size - 1
        if (alvo >= 0) listState.animateScrollToItem(alvo)
    }

    // Cronómetro do turno: só conta enquanto o agente trabalha (a linha «em direto» do design).
    var inicio by remember { mutableStateOf(0L) }
    var agora by remember { mutableStateOf(0L) }
    LaunchedEffect(vm.busy) {
        if (vm.busy) {
            inicio = System.currentTimeMillis()
            agora = inicio
            while (true) {
                kotlinx.coroutines.delay(1000)
                agora = System.currentTimeMillis()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CabecalhoDaConversa(vm)
        LinhaDeTelemetria(vm, if (vm.busy) ((agora - inicio) / 1000).coerceAtLeast(0) else 0)

        // O modelo e o esforço saíram daqui: vivem no menu de overflow do cabeçalho, como o
        // design pedia. Dois chips por cima da conversa custavam ~100 dp de altura em todas as
        // sessões — e o autor notou-o («usa muito height de espaço, e não está bonito»). O modelo
        // continua à vista na linha técnica do cabeçalho, que já o mostrava.
        Divider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(vm.transcript) { i, line ->
                when (line.role) {
                    // A ferramenta tem cartão próprio (nome em monospace + chip do estado), como no
                    // design; o pensamento e as notas do sistema ficam discretos, sem cartão.
                    "tool" -> CartaoFerramenta(line)
                    // O erro mais recente traz o «Tentar novamente» do design: repete o último
                    // prompt do utilizador (é o que se quer depois de um turno que falhou).
                    "error" -> {
                        CartaoDeMensagem(line)
                        val ultimo = vm.transcript.lastOrNull { it.role == "user" }?.text
                        if (i == vm.transcript.lastIndex && !vm.busy && !ultimo.isNullOrBlank()) {
                            TextButton(onClick = { vm.send(ultimo) }) {
                                Text(stringResource(R.string.tentar_novamente))
                            }
                        }
                    }
                    "thought" -> BlocoDePensamento(line.text)
                    "system" -> Text(
                        line.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                    else -> CartaoDeMensagem(line)
                }
            }
            // «A trabalhar…» no fundo da conversa, enquanto o agente tem um turno a correr: é o
            // que diz que a app não morreu, e a frase segue o que ele está mesmo a fazer.
            if (vm.busy) {
                item { EstadoDeTrabalho(vm) }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Divider()
        // Imagem anexada pelo utilizador: fica à espera do próximo prompt (o motor já aceita
        // blocos `image` — é o mesmo caminho que o `android_see` usa para mostrar o ecrã).
        vm.pendingImage?.let { img ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.imagem_anexo, img.name, img.kb),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.clearImage() }) { Text(stringResource(R.string.remover)) }
            }
        }
        // Fila de tarefas: a tira do design, e **só quando há algo para mostrar** — com tarefas na
        // fila, ou enquanto se escreve (que é exactamente quando «Juntar à fila» faz falta). Com a
        // fila vazia e o campo vazio não aparece nada: é espaço que volta para a conversa.
        if (vm.tarefas.isNotEmpty() || input.isNotBlank()) {
            FilaCard(
                tarefas = vm.tarefas,
                aCorrer = vm.filaACorrer,
                enabled = !vm.busy,
                onJuntar = {
                    vm.juntarATexto(input)
                    input = ""
                },
                onCorrer = { vm.correrFila() },
                onParar = { vm.pararFila() },
                onRemover = { vm.removerDaFila(it) },
                onLimpar = { vm.limparConcluidas() },
            )
        }
        BarraDeInput(vm, input) { input = it }
    }
}

/**
 * O cabeçalho da conversa aberta: voltar, o título da sessão, a linha técnica em monospace
 * («root · deepseek-v4-flash · contexto …») e o botão que fecha a sessão. A barra de abas não
 * aparece aqui — é isso que faz a conversa parecer um chat do WhatsApp.
 */
@Composable
private fun CabecalhoDaConversa(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val modo = when (vm.modeKind) {
        DaemonController.Mode.DAEMON -> "root"
        DaemonController.Mode.SHIZUKU -> "Shizuku"
        else -> "on-device"
    }
    var menuAberto by remember { mutableStateOf(false) }
    val modelo = vm.configOptions.firstOrNull { it.id == "model" }?.currentLabel
    val tecnica = listOfNotNull(modo, modelo, vm.uso?.texto(ctx)).joinToString("  \u00B7  ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.fecharConversa() }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.voltar_conversas),
                tint = MaterialTheme.colorScheme.primaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                vm.sessionTitles[vm.sessionId]
                    ?: stringResource(R.string.sessao_curta, vm.sessionId?.take(8) ?: stringResource(R.string.nenhuma)),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tecnica,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (vm.reconnecting) {
            Text(
                stringResource(R.string.a_reconectar),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        // Menu de overflow, como o design o pede: o modelo e o esforço vivem aqui, e não em chips
        // por cima da conversa (que comiam ~100 dp e empurravam o transcript para baixo), e o
        // «fechar a sessão» passou a ter **nome** em vez de ser um ícone de power que ninguém sabia
        // o que fazia.
        Box {
            IconButton(onClick = { menuAberto = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.menu_conversa),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            DropdownMenu(expanded = menuAberto, onDismissRequest = { menuAberto = false }) {
                vm.configOptions.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(opt.name)
                                Text(
                                    opt.currentLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = Mono,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                        onClick = {
                            menuAberto = false
                            vm.configPicker = opt
                        },
                    )
                }
                if (vm.configOptions.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fechar_sessao_menu)) },
                    enabled = !vm.busy,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        menuAberto = false
                        vm.sessionId?.let { vm.closeSession(it) }
                    },
                )
            }
        }
    }
}

/**
 * O que o agente pensou, **fechado por omissão**.
 *
 * O design já o pedia («reasoning blocks: muted italic text with a small sparkle glyph and a chevron
 * to collapse them») e o autor confirmou-o com um caso concreto: a um «olá» respondia com uma
 * resposta curta e um bloco enorme de pensamento à frente dela, que empurrava a resposta para fora
 * do ecrã. Fechado, mostra-se o que é e quanto ocupa; aberto, mostra-se tudo.
 */
@Composable
private fun BlocoDePensamento(texto: String) {
    var aberto by rememberSaveable(texto.take(40)) { mutableStateOf(false) }
    val linhas = texto.count { it == '\n' } + 1
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { aberto = !aberto }
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.pensamento),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                stringResource(
                    if (aberto) R.string.pensamento_esconder else R.string.pensamento_ver,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (aberto) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (aberto) R.string.pensamento_esconder else R.string.pensamento_ver,
                ),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (aberto) {
            Text(
                texto,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A linha «em direto» do design: enquanto o agente trabalha, mostra há quanto tempo, quantas
 * ferramentas já chamou e o consumo de contexto. Sem turno a correr e sem números do motor, não
 * ocupa espaço nenhum.
 */
/**
 * «A pensar…», «A correr um comando…» — o estado do agente, no fundo da conversa.
 *
 * A frase vem da ferramenta que está a correr ([`recursoDaFerramenta`]); só quando não há nenhuma
 * — o agente está a pensar — é que as palavras rodam entre si. Rodar palavras ao acaso enquanto
 * ele corre um comando seria bonito e falso.
 */
@Composable
private fun EstadoDeTrabalho(vm: MainViewModel) {
    val ferramenta = vm.transcript.lastOrNull { it.role == "tool" && !it.done }?.text
    val especifica = ferramenta?.let { recursoDaFerramenta(it) }
    val genericas = listOf(
        R.string.trabalho_pensar,
        R.string.trabalho_raciocinar,
        R.string.trabalho_preparar,
        R.string.trabalho_responder,
    )
    var volta by remember { mutableStateOf(0) }
    LaunchedEffect(especifica) {
        volta = 0
        // Só roda quando não há nada de concreto para dizer.
        if (especifica == null) {
            while (true) {
                kotlinx.coroutines.delay(2_600)
                volta++
            }
        }
    }
    val frase = if (especifica != null) {
        stringResource(especifica)
    } else {
        stringResource(genericas[volta % genericas.size])
    }
    // Os pontinhos, a mudar ao ritmo de sempre.
    var pontos by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(420)
            pontos = pontos % 3 + 1
        }
    }
    // O ponto pulsa de leve: dá movimento sem chamar mais atenção do que a resposta que vai chegar.
    val pulso = rememberInfiniteTransition(label = "trabalho")
    val alfa by pulso.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alfa",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .alpha(alfa)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Text(
            frase + ".".repeat(pontos),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun LinhaDeTelemetria(vm: MainViewModel, segundos: Long) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uso = vm.uso
    if (!vm.busy && uso == null) return

    val ferramentas = vm.transcript.count { it.role == "tool" }
    val aCorrer = vm.transcript.count { it.role == "tool" && !it.done }
    val partes = ArrayList<String>(3)
    if (ferramentas > 0) partes.add(stringResource(R.string.telemetria_ferramentas, ferramentas, aCorrer))
    uso?.let { partes.add(it.texto(ctx)) }
    if (vm.busy) partes.add(stringResource(R.string.telemetria_tempo, segundos))

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.busy) Chip(stringResource(R.string.em_direto), cor = MaterialTheme.colorScheme.primaryContainer)
        Text(
            partes.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A barra de escrita: **um** campo largo com os ícones por dentro (anexar à esquerda, ditar à
 * direita) e o botão de enviar redondo ao lado — como no design. Antes disto eram dois botões de
 * texto encostados a uma caixa pequena, e era a parte mais feia do ecrã.
 */
@Composable
private fun BarraDeInput(vm: MainViewModel, input: String, onInput: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.attachImage(uri)
    }
    // Voz: usa o reconhecedor do sistema (`RecognizerIntent`), que é quem pede o microfone — a app
    // não precisa de permissão nenhuma para isto. O texto reconhecido entra no campo, onde pode ser
    // corrigido antes de enviar: ditar e enviar à cegueira é pior do que ditar e ver.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()
        when {
            result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank() ->
                onInput(if (input.isBlank()) spoken else "$input $spoken")
            result.resultCode == Activity.RESULT_OK -> vm.error = ctx.getString(R.string.voz_nao_percebi)
            // Cancelado pelo utilizador: não é erro.
        }
    }
    fun enviar() {
        if (input.isNotBlank() || vm.pendingImage != null) {
            onInput("")
            vm.send(input)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch("image/*") }, enabled = !vm.busy) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.anexar),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                TextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = {
                        Text(stringResource(R.string.escreve_tarefa), style = MaterialTheme.typography.bodyMedium)
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { enviar() }),
                )
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, ctx.getString(R.string.voz_prompt))
                        }
                        runCatching { voice.launch(intent) }
                            .onFailure { vm.error = ctx.getString(R.string.voz_sem_reconhecedor) }
                    },
                    enabled = !vm.busy,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.ditar_texto),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        if (vm.busy) {
            FilledIconButton(
                onClick = { vm.cancelTurn() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.parar)) }
        } else {
            FilledIconButton(
                onClick = { enviar() },
                enabled = input.isNotBlank() || vm.pendingImage != null,
            ) { Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.enviar)) }
        }
    }
}

@Composable
private fun LicensesDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.mostrarLicencas = false; vm.licencaAberta = null },
        title = { Text(stringResource(R.string.licencas_titulo)) },
        text = {
            LicensesBody(
                licencas = vm.licencas,
                aberta = vm.licencaAberta,
                onAbrir = { vm.licencaAberta = it },
            )
        },
        confirmButton = {
            if (vm.licencaAberta != null) {
                TextButton(onClick = { vm.licencaAberta = null }) { Text(stringResource(R.string.voltar)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.mostrarLicencas = false; vm.licencaAberta = null }) {
                Text(stringResource(R.string.fechar))
            }
        },
    )
}
