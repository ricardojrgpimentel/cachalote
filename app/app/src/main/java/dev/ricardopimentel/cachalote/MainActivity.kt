package dev.ricardopimentel.cachalote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
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

private val cachaloteColors = darkColorScheme(
    primary = Color(0xFF7FC4FF),
    onPrimary = Color(0xFF00243D),
    secondary = Color(0xFF9FCFFF),
    background = Color(0xFF0B1220),
    surface = Color(0xFF141C2B),
)

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
    MaterialTheme(colorScheme = cachaloteColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(0) }
            LaunchedEffect(Unit) { vm.refreshStatus() }

            Scaffold(
                modifier = Modifier.imePadding(),
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Memory, null) },
                            label = { Text("Estado") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Key, null) },
                            label = { Text("Chave") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Chat, null) },
                            label = { Text("Conversa") },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Build, null) },
                            label = { Text("Poderes") },
                        )
                    }
                },
            ) { pad ->
                Column(modifier = Modifier.padding(pad).fillMaxSize()) {
                    Header(vm, busy = vm.busy)
                    vm.approval?.let { ap -> ApprovalDialog(vm, ap) }
                    vm.configPicker?.let { opt -> ConfigPickerDialog(vm, opt) }
                    if (vm.showSessions) SessionsDialog(vm)
                    if (vm.error.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1520)),
                        ) {
                            Text(vm.error, modifier = Modifier.padding(12.dp), color = Color(0xFFFFC9D0))
                        }
                    }
                    LaunchedEffect(tab) { if (tab == 3) vm.refreshCapabilities() }
                    when (tab) {
                        0 -> StatusScreen(vm)
                        1 -> KeyScreen(vm)
                        2 -> ChatScreen(vm)
                        else -> CapabilitiesScreen(vm)
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
            Text("Cachalote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "motor DSH no telefone · " + (if (vm.connected) "ligado" else "desligado"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
    }
}

/**
 * O motor pede autorização antes de uma operação sensível (ACP `session/request_permission`).
 * As opções vêm do motor (permitir uma vez / sempre / recusar); mostramo-las tal como vêm.
 */
@Composable
private fun ApprovalDialog(vm: MainViewModel, ap: PendingApproval) {
    AlertDialog(
        onDismissRequest = { vm.answerApproval(null) },
        title = { Text("Autorização") },
        text = {
            Column {
                Text(ap.title, fontWeight = FontWeight.SemiBold)
                if (ap.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ap.detail,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ap.options.forEach { (optionId, name) ->
                    Button(onClick = { vm.answerApproval(optionId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(name)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.answerApproval(null) }) { Text("Recusar o turno") }
        },
    )
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
        dismissButton = { TextButton(onClick = { vm.configPicker = null }) { Text("Fechar") } },
    )
}

/** Lista das sessões persistidas no motor, para retomar uma conversa anterior. */
@Composable
private fun SessionsDialog(vm: MainViewModel) {
    // A sessão activa é lida UMA vez, no topo: dentro dos itens do LazyColumn o valor podia
    // ficar preso na primeira composição (o ✓ e o ✕ nunca apareciam — ver STATUS §34).
    val atual = vm.sessionId
    AlertDialog(
        onDismissRequest = { vm.showSessions = false },
        title = {
            Column {
                Text("Sessões")
                Text(
                    if (atual.isNullOrBlank()) "nenhuma sessão activa" else "activa: " + atual.take(8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        text = {
            if (vm.sessions.isEmpty()) {
                Text("não há sessões guardadas no motor")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(vm.sessions) { _, row ->
                        val current = row.id == atual
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { vm.resumeSession(row.id) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        (if (current) "✓ " else "") + row.title,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                    Text(
                                        row.id.take(8) + "  ·  " + row.updatedAt.replace("T", " ").take(19),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            // O motor só fecha a sessão **activa** (`session/close` responde
                            // "unknown session" às do histórico): o ✕ aparece só na linha da
                            // sessão actual (ver knowledge 93).
                            if (current) {
                                TextButton(onClick = { vm.closeSession(row.id) }, enabled = !vm.busy) {
                                    Text("✕", color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Column {
                if (!atual.isNullOrBlank()) {
                    // Caminho curto para o que interessa: fechar a sessão em uso, sem ter de a
                    // encontrar na lista (a lista tem dezenas de sessões antigas).
                    TextButton(onClick = { vm.closeSession(atual) }, enabled = !vm.busy) {
                        Text("Fechar a sessão activa")
                    }
                }
                Text(
                    "✕ fecha a sessão activa · toca numa linha para retomar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(onClick = { vm.showSessions = false }) { Text("Fechar") }
            }
        },
    )
}

/**
 * O que o agente pode fazer no telefone: interruptores por capacidade e o registo do
 * que ele já pediu. É a barreira de segurança do projecto (ver D10 no plano) — no
 * Android não há sandbox a travar o agente, por isso quem decide é o utilizador.
 */
@Composable
private fun CapabilitiesScreen(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accessibilityOn = CachaloteAccessibilityService.isEnabled()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Acessibilidade", fontWeight = FontWeight.SemiBold)
                Text(
                    if (accessibilityOn) "serviço ligado — o agente consegue ver e tocar no ecrã"
                    else "serviço desligado — sem ele o agente só envia notificações",
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
                }) { Text("Abrir Definições → Acessibilidade") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Motor embutido (sem root)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Testa se o Node embutido na APK consegue executar-se a partir do " +
                        "nativeLibraryDir — é o que permite correr o motor dentro da app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(vm.embedTest, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.testEmbeddedNode() }, enabled = !vm.busy) { Text("Testar execução") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Capacidades autorizadas", fontWeight = FontWeight.SemiBold)
                Text(
                    "O agente pede; tu decides. Cada pedido fica registado em baixo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, true) } }) {
                        Text("Autorizar todas")
                    }
                    OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, false) } }) {
                        Text("Negar todas")
                    }
                }
                Spacer(Modifier.height(4.dp))
                vm.capabilityNames.forEach { cap ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cap, modifier = Modifier.weight(1f))
                        Switch(
                            checked = vm.capabilityToggles[cap] == true,
                            onCheckedChange = { vm.setCapability(cap, it) },
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Registo", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearAudit() }) { Text("Limpar") }
                }
                if (vm.audit.isEmpty()) {
                    Text("ainda não houve pedidos", style = MaterialTheme.typography.bodySmall)
                } else {
                    vm.audit.take(25).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Áreas permitidas do bash", fontWeight = FontWeight.SemiBold)
                Text(
                    "Fora destas pastas o agente tem de pedir autorização no telefone. " +
                        "Uma por linha; o workspace da sessão é sempre permitido.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedTextField(
                    value = vm.allowedRootsText,
                    onValueChange = { vm.allowedRootsText = it },
                    placeholder = { Text("/sdcard/Download") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.saveAllowedRoots() }, enabled = !vm.busy) { Text("Gravar e reiniciar") }
                    if (vm.allowedRootsSaved.isNotBlank()) {
                        Text(vm.allowedRootsSaved, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Kernel por download (modo embutido)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Sem asset na APK, a app vai buscar o kernel a este URL e verifica o sha256. " +
                        "Vazio = usa o URL gravado no build. Serve para hospedagem própria.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedTextField(
                    value = vm.kernelUrlText,
                    onValueChange = { vm.kernelUrlText = it },
                    placeholder = { Text("https://…/kernel.zip") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                OutlinedButton(onClick = { vm.saveKernelUrl() }, enabled = !vm.busy) { Text("Gravar URL") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ecrã virtual", fontWeight = FontWeight.SemiBold)
                Text(
                    "Uma segunda tela onde o agente trabalha sem mexer no teu ecrã. Põe apps lá com " +
                        "bash: am start --display <id> -n <pkg>/<activity>; toca com input -d <id>. " +
                        "Vive dentro desta app: se ela for fechada, o ecrã virtual desaparece.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.vscreenStart() }, enabled = !vm.busy) { Text("Iniciar") }
                    OutlinedButton(onClick = { vm.vscreenRefresh() }, enabled = !vm.busy) { Text("Ver fotograma") }
                    OutlinedButton(onClick = { vm.vscreenStop() }, enabled = !vm.busy) { Text("Parar") }
                }
                if (vm.vscreenInfo.isNotBlank()) {
                    Text(vm.vscreenInfo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                vm.vscreenBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "fotograma do ecrã virtual",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Servidores MCP", fontWeight = FontWeight.SemiBold)
                Text(
                    "JSON no formato do ACP, enviado em cada sessão nova. Exemplo stdio:\n" +
                        "[{\"name\":\"ping\",\"command\":\"/data/adb/cachalote/runtime/bin/node\"," +
                        "\"args\":[\"/data/local/tmp/mcp-ping-server.mjs\"]," +
                        "\"env\":[{\"name\":\"LD_LIBRARY_PATH\",\"value\":\"/data/adb/cachalote/runtime/lib\"}]}]\n" +
                        "⚠ O comando tem de ser um caminho ABSOLUTO e, se usar o Node do daemon, precisa " +
                        "do LD_LIBRARY_PATH no env (senão morre no linker).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedTextField(
                    value = vm.mcpJsonText,
                    onValueChange = { vm.mcpJsonText = it },
                    placeholder = { Text("[{\"name\":\"…\",\"command\":\"/caminho/absoluto\"}]") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.saveMcpServers() }, enabled = !vm.busy) { Text("Gravar MCP") }
                    if (vm.mcpSaved.isNotBlank()) {
                        Text(vm.mcpSaved, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(
            "Aviso: o motor corre como root e o Android não impõe o sandbox do DSH. " +
                "Estes interruptores são a barreira real — desliga o que não precisares.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
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
    val checks = if (vm.embeddedMode) listOf(
        Triple("kernel extraído", vm.daemonInstalled != null && vm.embeddedInfo.contains("extraído"), "(automático)" to {}),
        Triple("motor embutido", vm.embeddedInfo.contains("a correr") || vm.embeddedInfo.contains("extraído"), "Verificar" to {}),
        Triple("chave do provedor", vm.keyPresent, "(no separador Chave)" to {}),
        Triple("acessibilidade ligada", accessibility, "Abrir Acessibilidade" to {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            Unit
        }),
        Triple("capacidades autorizadas", allowed > 0, "(no separador Poderes)" to {}),
    ) else listOf(
        Triple("daemon instalado", vm.daemonInstalled == true, "Ligar daemon" to { vm.startDaemon() }),
        Triple("root concedido", vm.rootOk == true, "Obter token (root)" to { vm.obtainToken() }),
        Triple("token do canal", vm.tokenState.contains("obtido"), "Obter token (root)" to { vm.obtainToken() }),
        Triple("chave do provedor", vm.keyPresent, "(no separador Chave)" to {}),
        Triple("acessibilidade ligada", accessibility, "Abrir Acessibilidade" to {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            Unit
        }),
        Triple("capacidades autorizadas", allowed > 0, "(no separador Poderes)" to {}),
    )
    val missing = checks.count { !it.second }
    if (missing == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A44)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Primeiros passos — faltam $missing", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.refreshStatus(); vm.refreshCapabilities() }, enabled = !vm.busy) { Text("Verificar") }
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

@Composable
private fun StatusScreen(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingCard(vm)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Motor", fontWeight = FontWeight.SemiBold)
                Text(
                    when (vm.modeKind) {
                        DaemonController.Mode.DAEMON -> "daemon com root: arranca no boot, bash como root com guarda de caminhos"
                        DaemonController.Mode.SHIZUKU -> "daemon via Shizuku: corre como shell (uid 2000), sem root"
                        DaemonController.Mode.EMBEDDED -> "embutido: motor dentro da app, o sandbox do Android é o limite"
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
                    Text(vm.shizukuState, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (vm.modeKind == DaemonController.Mode.SHIZUKU) {
                    OutlinedButton(
                        onClick = { vm.requestShizukuPermission(ctx as android.app.Activity) },
                        enabled = !vm.busy,
                    ) { Text("Autorizar no Shizuku") }
                }
                if (vm.modeKind == DaemonController.Mode.EMBEDDED) {
                    OutlinedButton(onClick = { vm.downloadKernel() }, enabled = !vm.busy) {
                        Text("Descarregar kernel")
                    }
                }
                if (vm.embeddedInfo.isNotBlank()) {
                    Text(vm.embeddedInfo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (vm.embeddedMode) "Motor" else "Daemon", fontWeight = FontWeight.SemiBold)
                Text(
                    vm.daemonInfo,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Acesso", fontWeight = FontWeight.SemiBold)
                Text("root: " + when (vm.rootOk) { true -> "concedido"; false -> "negado"; null -> "?" })
                Text("token: ${vm.tokenState}")
                Text(
                    "diagnóstico: pid ${android.os.Process.myPid()} · a11y ${vm.accessibilityOn} · ticks ${vm.a11yTicks} · direto ${CachaloteAccessibilityService.isEnabled()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("motor: ${vm.agentInfo.ifBlank { "(handshake ainda não feito)" }}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshStatus() }, enabled = !vm.busy) { Text("Atualizar") }
            OutlinedButton(onClick = { vm.startDaemon() }, enabled = !vm.busy) { Text("Ligar daemon") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.obtainToken() }, enabled = !vm.busy) { Text("Obter token (root)") }
            OutlinedButton(onClick = { vm.restartEngine() }, enabled = !vm.busy) { Text("Reiniciar motor") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (CachaloteAgentService.running) "serviço de agente: ligado"
                else "serviço de agente: desligado",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                val ctx2 = ctx
                if (CachaloteAgentService.running) CachaloteAgentService.stop(ctx2)
                else CachaloteAgentService.start(ctx2)
            }) { Text(if (CachaloteAgentService.running) "Desligar" else "Ligar") }
        }
        OutlinedButton(onClick = {
            // Arranca o motor do perfil web (se preciso) e abre a interface completa.
            vm.busy = true
            ctx.startActivity(android.content.Intent(ctx, WebModeActivity::class.java))
        }, enabled = !vm.busy) { Text("Modo completo (interface web do DSH)") }
        Text(
            "O daemon corre como root e o sandbox do Android não é imposto: quem trava comandos " +
                "fora do workspace é a aprovação. Ver D10 no plano.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun KeyScreen(vm: MainViewModel) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Chave do provedor", fontWeight = FontWeight.SemiBold)
                Text(
                    if (vm.keyPresent) "presente · ${vm.keyLength} caracteres · ${vm.keyUpdatedAt}"
                    else "ainda não há chave guardada",
                )
                Text(
                    "A chave é enviada ao daemon, que a escreve em home/.env com modo 600. " +
                        "A app não a guarda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = { value = it.trim() },
            label = { Text("DEEPSEEK_API_KEY") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveKey(value); value = "" },
                enabled = !vm.busy && value.length >= 8,
            ) { Text("Guardar no daemon") }
            OutlinedButton(onClick = { visible = !visible }) { Text(if (visible) "Esconder" else "Mostrar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.refreshKeyStatus() }, enabled = !vm.busy) { Text("Verificar estado") }
            TextButton(onClick = { vm.removeKey() }, enabled = !vm.busy) { Text("Remover chave") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.transcript.size) {
        if (vm.transcript.isNotEmpty()) listState.animateScrollToItem(vm.transcript.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "sessão: " + (vm.sessionId?.take(8) ?: "nenhuma") +
                    (if (vm.reconnecting) "  · a reconectar…" else ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.refreshSessions() }, enabled = !vm.busy) { Text("Sessões") }
            TextButton(onClick = { vm.newSession() }, enabled = !vm.busy) { Text("Nova sessão") }
        }
        if (vm.configOptions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.configOptions.forEach { opt ->
                    AssistChip(
                        onClick = { vm.configPicker = opt },
                        label = { Text(opt.name + ": " + opt.currentLabel.take(28)) },
                    )
                }
            }
        }
        Divider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(vm.transcript) { _, line ->
                val bg = when (line.role) {
                    "user" -> Color(0xFF1E3A5F)
                    "assistant" -> Color(0xFF17283C)
                    "tool" -> Color(0xFF2A2440)
                    "error" -> Color(0xFF4A1520)
                    else -> Color.Transparent
                }
                val label = when (line.role) {
                    "user" -> "tu"
                    "assistant" -> "agente"
                    "thought" -> "pensamento"
                    "tool" -> "ferramenta"
                    "error" -> "erro"
                    else -> "sistema"
                }
                Box(
                    modifier = Modifier.fillMaxWidth().background(bg).padding(8.dp),
                ) {
                    Column {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            line.text,
                            style = if (line.role == "thought") MaterialTheme.typography.bodySmall
                            else MaterialTheme.typography.bodyMedium,
                            color = if (line.role == "thought") MaterialTheme.colorScheme.secondary
                            else Color.Unspecified,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Divider()
        // Imagem anexada pelo utilizador: fica à espera do próximo prompt (o motor já aceita
        // blocos `image` — é o mesmo caminho que o `android_see` usa para mostrar o ecrã).
        vm.pendingImage?.let { img ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "imagem: ${img.name} · ${img.kb} KB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.clearImage() }) { Text("Remover") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) vm.attachImage(uri)
            }
            // Voz: usa o reconhecedor do sistema (`RecognizerIntent`), que é quem pede o
            // microfone — a app não precisa de permissão nenhuma para isto e funciona com o
            // que o telefone já tiver. O texto reconhecido entra no campo, onde pode ser
            // corrigido antes de enviar: ditar e enviar à cegueira é pior do que ditar e ver.
            val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                when {
                    result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank() ->
                        input = if (input.isBlank()) spoken else "$input $spoken"
                    result.resultCode == Activity.RESULT_OK -> vm.error = "não percebi nada — tenta outra vez"
                    // Cancelado pelo utilizador: não é erro.
                }
            }
            TextButton(onClick = { picker.launch("image/*") }, enabled = !vm.busy) { Text("Anexar") }
            TextButton(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Fala para escrever")
                    }
                    runCatching { voice.launch(intent) }
                        .onFailure { vm.error = "não há reconhecedor de voz neste dispositivo" }
                },
                enabled = !vm.busy,
            ) { Text("Falar") }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Escreve uma tarefa para o agente") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val t = input
                    if (t.isNotBlank() || vm.pendingImage != null) { input = ""; vm.send(t) }
                }),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Button(
                    onClick = { val t = input; input = ""; vm.send(t) },
                    enabled = !vm.busy && (input.isNotBlank() || vm.pendingImage != null),
                ) { Text("Enviar") }
                if (vm.busy) {
                    TextButton(onClick = { vm.cancelTurn() }) { Text("Parar") }
                }
            }
        }
    }
}
