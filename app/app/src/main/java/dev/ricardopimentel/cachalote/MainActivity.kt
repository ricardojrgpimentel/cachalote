package dev.ricardopimentel.cachalote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    MaterialTheme(colorScheme = cachaloteColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(0) }
            LaunchedEffect(Unit) { vm.refreshStatus() }

            Scaffold(
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
                    }
                },
            ) { pad ->
                Column(modifier = Modifier.padding(pad).fillMaxSize()) {
                    Header(vm, busy = vm.busy)
                    if (vm.error.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1520)),
                        ) {
                            Text(vm.error, modifier = Modifier.padding(12.dp), color = Color(0xFFFFC9D0))
                        }
                    }
                    when (tab) {
                        0 -> StatusScreen(vm)
                        1 -> KeyScreen(vm)
                        else -> ChatScreen(vm)
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

@Composable
private fun StatusScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Daemon", fontWeight = FontWeight.SemiBold)
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
                "sessão: " + (vm.sessionId?.take(8) ?: "nenhuma"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.newSession() }, enabled = !vm.busy) { Text("Nova sessão") }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Escreve uma tarefa para o agente") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { val t = input; input = ""; vm.send(t) },
                enabled = !vm.busy && input.isNotBlank(),
            ) { Text("Enviar") }
        }
    }
}
