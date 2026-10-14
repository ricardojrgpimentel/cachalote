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
import androidx.compose.ui.res.stringResource
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
                            label = { Text(stringResource(R.string.aba_estado)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Key, null) },
                            label = { Text(stringResource(R.string.aba_chave)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Chat, null) },
                            label = { Text(stringResource(R.string.aba_conversa)) },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Build, null) },
                            label = { Text(stringResource(R.string.aba_poderes)) },
                        )
                    }
                },
            ) { pad ->
                Column(modifier = Modifier.padding(pad).fillMaxSize()) {
                    Header(vm, busy = vm.busy)
                    vm.approval?.let { ap -> ApprovalDialog(vm, ap) }
                    vm.configPicker?.let { opt -> ConfigPickerDialog(vm, opt) }
                    if (vm.showSessions) SessionsDialog(vm)
                    if (vm.mostrarLicencas) LicensesDialog(vm)
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (vm.connected) stringResource(R.string.cabecalho_motor_ligado) else stringResource(R.string.cabecalho_motor_desligado),
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
        title = { Text(stringResource(R.string.dialogo_autorizacao)) },
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
            TextButton(onClick = { vm.answerApproval(null) }) { Text(stringResource(R.string.recusar_turno)) }
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
        dismissButton = { TextButton(onClick = { vm.configPicker = null }) { Text(stringResource(R.string.fechar)) } },
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
                Text(stringResource(R.string.seccoes))
                Text(
                    if (atual.isNullOrBlank()) "nenhuma sessão activa" else "activa: " + atual.take(8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        text = {
            if (vm.sessions.isEmpty()) {
                Text(stringResource(R.string.sem_sessoes))
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
                        Text(stringResource(R.string.fechar_sessao_activa))
                    }
                }
                Text(
                    stringResource(R.string.legenda_sessoes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(onClick = { vm.showSessions = false }) { Text(stringResource(R.string.fechar)) }
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
                Text(stringResource(R.string.acessibilidade), fontWeight = FontWeight.SemiBold)
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
                }) { Text(stringResource(R.string.abrir_acessibilidade)) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.vscreen_titulo), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.testar_execucao_texto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(vm.embedTest, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.testEmbeddedNode() }, enabled = !vm.busy) { Text(stringResource(R.string.testar_execucao)) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.capacidades_titulo), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.capacidades_explicacao),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, true) } }) {
                        Text(stringResource(R.string.autorizar_todas))
                    }
                    OutlinedButton(onClick = { vm.capabilityNames.forEach { vm.setCapability(it, false) } }) {
                        Text(stringResource(R.string.negar_todas))
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
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.vscreen_titulo), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.vscreen_texto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.vscreenStart() }, enabled = !vm.busy) { Text(stringResource(R.string.iniciar)) }
                    OutlinedButton(onClick = { vm.vscreenRefresh() }, enabled = !vm.busy) { Text(stringResource(R.string.ver_fotograma)) }
                    OutlinedButton(onClick = { vm.vscreenStop() }, enabled = !vm.busy) { Text(stringResource(R.string.parar)) }
                }
                if (vm.vscreenInfo.isNotBlank()) {
                    Text(vm.vscreenInfo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                vm.vscreenBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.fotograma_desc),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.mcp_titulo), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.mcp_texto) + "\n" +
                        stringResource(R.string.mcp_exemplo) + "\n" +
                        stringResource(R.string.mcp_aviso),
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
                    OutlinedButton(onClick = { vm.saveMcpServers() }, enabled = !vm.busy) { Text(stringResource(R.string.gravar_mcp)) }
                    if (vm.mcpSaved.isNotBlank()) {
                        Text(vm.mcpSaved, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.aviso_root),
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
                    Text(vm.shizukuState, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (vm.modeKind == DaemonController.Mode.SHIZUKU) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshStatus() }, enabled = !vm.busy) { Text(stringResource(R.string.atualizar)) }
            OutlinedButton(onClick = { vm.startDaemon() }, enabled = !vm.busy) { Text(stringResource(R.string.ligar_daemon)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.obtainToken() }, enabled = !vm.busy) { Text(stringResource(R.string.obter_token)) }
            OutlinedButton(onClick = { vm.restartEngine() }, enabled = !vm.busy) { Text(stringResource(R.string.reiniciar_motor)) }
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
        }, enabled = !vm.busy) { Text(stringResource(R.string.modo_completo)) }
        Text(
            stringResource(R.string.aviso_root),
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
                Text(stringResource(R.string.chave_provedor), fontWeight = FontWeight.SemiBold)
                Text(
                    if (vm.keyPresent) "presente · ${vm.keyLength} caracteres · ${vm.keyUpdatedAt}"
                    else "ainda não há chave guardada",
                )
                Text(
                    stringResource(R.string.chave_explicacao),
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
            ) { Text(stringResource(R.string.guardar_no_daemon)) }
            OutlinedButton(onClick = { visible = !visible }) { Text(if (visible) "Esconder" else "Mostrar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.refreshKeyStatus() }, enabled = !vm.busy) { Text(stringResource(R.string.verificar_estado)) }
            TextButton(onClick = { vm.removeKey() }, enabled = !vm.busy) { Text(stringResource(R.string.remover_chave)) }
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
                stringResource(R.string.sessao_rotulo, vm.sessionId?.take(8) ?: stringResource(R.string.nenhuma)) +
                    (if (vm.reconnecting) "  · a reconectar…" else ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.refreshSessions() }, enabled = !vm.busy) { Text(stringResource(R.string.seccoes)) }
            TextButton(onClick = { vm.newSession() }, enabled = !vm.busy) { Text(stringResource(R.string.nova_sessao)) }
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
                TextButton(onClick = { vm.clearImage() }) { Text(stringResource(R.string.remover)) }
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
            TextButton(onClick = { picker.launch("image/*") }, enabled = !vm.busy) { Text(stringResource(R.string.anexar)) }
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
            ) { Text(stringResource(R.string.ditar_texto)) }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.escreve_tarefa)) },
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
                ) { Text(stringResource(R.string.enviar)) }
                if (vm.busy) {
                    TextButton(onClick = { vm.cancelTurn() }) { Text(stringResource(R.string.parar)) }
                }
            }
        }
    }
}

@Composable
private fun LicensesDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.mostrarLicencas = false; vm.licencaAberta = null },
        title = { Text(stringResource(R.string.licencas_titulo)) },
        text = {
            val aberta = vm.licencaAberta
            if (aberta == null) {
                if (vm.licencas.isEmpty()) {
                    Text(stringResource(R.string.sem_pedidos))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        itemsIndexed(vm.licencas) { _, lic ->
                            TextButton(onClick = { vm.licencaAberta = lic }) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(lic.nome, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        lic.licenca,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column {
                    Text(aberta.nome, fontWeight = FontWeight.SemiBold)
                    Text(
                        aberta.licenca + " · " + aberta.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        item {
                            Text(
                                aberta.texto,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
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
