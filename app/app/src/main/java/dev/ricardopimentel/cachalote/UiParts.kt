package dev.ricardopimentel.cachalote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pedaços de interface **sem estado próprio**: recebem dados e devolvem acções por lambda.
 *
 * Porque é que isto existe: os diálogos e cartões estavam colados ao `MainViewModel`, o que
 * obrigava a testá-los com toques cegos de `uiautomator` (e falhava por tudo e por nada). Assim
 * qualquer um deles se compõe num teste com dados inventados e sem aparelho a responder. Ver
 * knowledge 114.
 */

/**
 * Consumo de contexto, discreto, no cabeçalho. Não desenha nada enquanto o motor não tiver dito
 * nada (`uso == null`) — nas primeiras trocas de mensagens é normal não haver números.
 */
@Composable
fun ConsumoDeContexto(uso: Uso?) {
    if (uso == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(
        uso.texto(ctx),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = Mono,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/**
 * O tema da app, num só sítio.
 *
 * Existe por um motivo concreto: o ecrã do «Modo completo» ([`WebModeActivity`]) usava
 * `MaterialTheme { }` sem esquema nenhum — o que dá o esquema **claro**, com texto quase preto,
 * sobre o fundo de janela escuro do `Theme.Cachalote`. O resultado era preto sobre preto e um ecrã
 * aparentemente vazio (medido no S10+ em 2026-10-15). Com o tema num só sítio, isso não volta a
 * acontecer sem se notar.
 */
@Composable
fun CachaloteTema(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = cachaloteColors, typography = cachaloteTypography, content = content)
}

/**
 * Fila de tarefas: uma **tira**, como o design a pede — não um cartão.
 *
 * O desenho do Stitch ([`design/ecras/conversa-sessao.png`]) mostra-a como *«a compact task-queue
 * strip»*, **só quando tem tarefas**, numa linha: o glifo, o título, a contagem e os dois botões.
 * A versão anterior era um cartão com um parágrafo de dica (que repetia o que o campo de escrita
 * já diz logo abaixo) e uma linha de botões própria — 194 dp com uma tarefa, sempre à vista.
 *
 * Quem decide se a tira aparece é o ecrã da conversa (ver `ConversaScreen`): com a fila vazia e o
 * campo de escrita vazio não há nada para mostrar.
 *
 * Tudo por dados + lambdas (nada de ViewModel) para se poder testar sem aparelho.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilaCard(
    tarefas: List<Tarefa>,
    aCorrer: Boolean,
    enabled: Boolean,
    onJuntar: () -> Unit,
    onCorrer: () -> Unit,
    onParar: () -> Unit,
    onRemover: (String) -> Unit,
    onLimpar: () -> Unit,
) {
    val pendentes = tarefas.filter { it.estado == Tarefa.PENDENTE }
    val concluidas = tarefas.size - pendentes.size
    val removerDesc = stringResource(R.string.fila_remover)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Duas disposições, escolhidas pela largura:
            //   · larga (≥ 400dp) — tudo numa linha, com os rótulos dos botões a quebrar em duas
            //     linhas dentro de um botão estreito, como no desenho («Juntar à» / «fila»);
            //   · estreita (< 400dp) — o título numa linha e os botões noutra, com a largura
            //     natural: sem isso os rótulos quebravam em três linhas e a tira ficava mais alta
            //     do que o cartão que veio substituir (medido no S10+ a 360 dp em 2026-10-15).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val estreito = maxWidth < 400.dp
                val titulo: @Composable () -> Unit = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.fila_titulo),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Text(
                            if (pendentes.isEmpty()) stringResource(R.string.fila_vazia)
                            else stringResource(R.string.fila_quantas, pendentes.size),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = Mono,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                        )
                        if (aCorrer) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                val larguraMax = if (estreito) Dp.Unspecified else 104.dp
                val botoes: @Composable () -> Unit = {
                    OutlinedButton(
                        onClick = onJuntar,
                        enabled = enabled,
                        modifier = if (estreito) Modifier else Modifier.widthIn(max = larguraMax),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { RotuloDeFila(stringResource(R.string.fila_juntar)) }
                    if (aCorrer) {
                        OutlinedButton(
                            onClick = onParar,
                            modifier = if (estreito) Modifier else Modifier.widthIn(max = larguraMax),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { RotuloDeFila(stringResource(R.string.fila_parar)) }
                    } else {
                        Button(
                            onClick = onCorrer,
                            enabled = enabled && pendentes.isNotEmpty(),
                            modifier = if (estreito) Modifier else Modifier.widthIn(max = larguraMax),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { RotuloDeFila(stringResource(R.string.fila_correr)) }
                    }
                }
                if (estreito) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        titulo()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { botoes() }
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        titulo()
                        botoes()
                    }
                }
            }
            // Só as que ainda não correram (as concluídas desaparecem com "Limpar concluídas").
            // Mostram-se no máximo quatro: uma fila de vinte não pode comer a conversa toda.
            pendentes.take(4).forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}. ${t.texto}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Ícone e não texto: o `✕` num `TextButton` tinha 40 dp de altura mínima e
                    // era ele que mandava no tamanho da linha. O rótulo vai no `Icon`, que é onde
                    // o leitor de ecrã o lê (antes anunciava só «✕»).
                    IconButton(
                        onClick = { onRemover(t.id) },
                        enabled = enabled,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = removerDesc,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (pendentes.size > 4) {
                Text(
                    stringResource(R.string.fila_e_mais, pendentes.size - 4),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // "Limpar concluídas" só existe quando há concluídas — no desenho não está na tira,
            // e não vale uma linha permanente por uma acção que quase nunca é preciso.
            if (concluidas > 0) {
                TextButton(
                    onClick = onLimpar,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text(stringResource(R.string.fila_limpar), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Rótulo de um botão da tira da fila: cabe em duas linhas, centrado, como no desenho. */
@Composable
private fun RotuloDeFila(texto: String) {
    Text(
        texto,
        maxLines = 2,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Interruptores por capacidade — é aqui que o dono decide o que o agente pode fazer. */
@Composable
fun CapabilityToggles(
    nomes: List<String>,
    autorizadas: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    Column {
        nomes.forEach { cap ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cap, modifier = Modifier.weight(1f))
                Switch(checked = autorizadas[cap] == true, onCheckedChange = { onToggle(cap, it) })
            }
        }
    }
}

/**
 * Botões do ecrã virtual e o interruptor da pré-visualização ao vivo.
 *
 * A forma é a do desenho (uma grelha de 2×2: Iniciar/Fotograma em cima, Parar/Ao vivo em baixo),
 * com o principal cheio, o «Parar» na cor do erro e o «Ao vivo» dentro de um cartão com o
 * interruptor — antes eram quatro controlos soltos numa linha, e não parecia um painel.
 * Continua sem estado próprio, para os testes de JVM o poderem compor com dados inventados.
 */
@Composable
fun VscreenControls(
    rodando: Boolean,
    aoVivo: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onAoVivo: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.iniciar))
            }
            OutlinedButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.ver_fotograma))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStop,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.parar))
            }
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ao_vivo), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    // Só faz sentido ligar "ao vivo" com o ecrã virtual a correr.
                    Switch(checked = aoVivo, onCheckedChange = onAoVivo, enabled = rodando)
                }
            }
        }
    }
}

/**
 * O **painel de autorização** do design: o agente pede para correr uma ferramenta fora das áreas
 * permitidas e é aqui que o dono decide.
 *
 * Está separado do `ModalBottomSheet` (que fica no `MainActivity`) precisamente para se poder
 * compor num teste de JVM com um pedido inventado: era a última peça do design sem verificação
 * automática, porque o painel só aparecia com um turno real do modelo — e um turno real precisa de
 * rede e de chave. Ver STATUS §67.
 *
 * `onDecidir(null)` é o «recusar o turno»; com um id, é a opção escolhida (a primeira vem
 * selecionada, como no desenho).
 */
@Composable
fun PainelDeAutorizacao(
    requestId: Int,
    titulo: String,
    detalhe: String,
    opcoes: List<Pair<String, String>>,
    rodape: String,
    onDecidir: (String?) -> Unit,
) {
    var escolhida by remember(requestId) { mutableStateOf(opcoes.firstOrNull()?.first) }
    val foraDoSandbox = detalhe.contains("sandbox", ignoreCase = true) ||
        titulo.contains("sandbox", ignoreCase = true)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.dialogo_autorizacao),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDecidir(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.recusar_turno))
            }
        }

        if (foraDoSandbox) {
            Chip(stringResource(R.string.autorizacao_fora_sandbox), cor = MaterialTheme.colorScheme.error)
        }

        Etiqueta(stringResource(R.string.autorizacao_agente_quer))
        Text(titulo, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium)

        if (detalhe.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    detalhe,
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }

        Text(
            stringResource(R.string.autorizacao_explicacao),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        opcoes.forEach { (id, nome) ->
            val sel = id == escolhida
            Card(
                modifier = Modifier.fillMaxWidth().clickable { escolhida = id },
                colors = CardDefaults.cardColors(
                    containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                border = BorderStroke(
                    1.dp,
                    if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (sel) "\u25C9" else "\u25CB",
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        nome,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Button(
            onClick = { onDecidir(escolhida) },
            enabled = escolhida != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.autorizacao_confirmar)) }

        TextButton(onClick = { onDecidir(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recusar_turno))
        }

        Text(
            rodape,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * Uma linha da lista de conversas, já resolvida para o que o ecrã mostra.
 *
 * A `previa` só existe para a sessão que está aberta — é a única cujo texto a app tem (as outras
 * vivem no motor, e o `session/list` não devolve conteúdo; ver knowledge 97). Para essas, a linha
 * mostra o identificador curto em vez de inventar um resumo.
 */
data class ItemConversa(
    val id: String,
    val titulo: String,
    val previa: String,
    val quando: String,
    val activa: Boolean,
    val falhou: Boolean,
)

/**
 * A **lista de conversas** — o ecrã da aba «Conversa», como a lista de chats do WhatsApp: uma
 * linha por sessão, o botão «Nova» a pairar em baixo à direita, e o estado vazio quando ainda não
 * há nada. Tocar numa linha entra na conversa (é quem chama que decide o que isso significa).
 *
 * Sem estado próprio: recebe os itens já resolvidos e devolve as acções por lambda (ver knowledge
 * 114 — é o que permite testá-la na JVM, sem aparelho).
 */
@Composable
fun ListaDeConversas(
    conversas: List<ItemConversa>,
    onAbrir: (String) -> Unit,
    onNova: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (conversas.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    stringResource(R.string.conversa_vazia_titulo),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.conversa_vazia_texto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(4.dp))
                Button(onClick = onNova) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.conversa_nova_longa))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    if (conversas.size == 1) stringResource(R.string.conversa_contagem_uma)
                    else stringResource(R.string.conversa_contagem, conversas.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(conversas, key = { it.id }) { c ->
                        LinhaDeConversa(c, onAbrir)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNova,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nova_sessao))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.conversa_nova))
        }
    }
}

/** Uma linha da lista: iniciais, título, prévia, quando e o estado — com o realce da activa. */
@Composable
private fun LinhaDeConversa(c: ItemConversa, onAbrir: (String) -> Unit) {
    val destaque = MaterialTheme.colorScheme.primaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAbrir(c.id) }
            .background(
                if (c.activa) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            // A barra de acento da linha activa, como a linha seleccionada do WhatsApp.
            .drawBehind {
                if (c.activa) drawRect(color = destaque, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (c.activa) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                iniciais(c.titulo),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = Mono,
                color = if (c.activa) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                c.titulo,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                c.previa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                c.quando,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.secondary,
            )
            when {
                c.falhou -> Chip(stringResource(R.string.sessao_estado_falhou), cor = MaterialTheme.colorScheme.error)
                c.activa -> Chip(stringResource(R.string.sessao_estado_activa), cor = MaterialTheme.colorScheme.primaryContainer)
            }
        }
    }
}

/**
 * As duas primeiras letras da **primeira palavra** do título, para o avatar da lista — é o que o
 * desenho mostra («corre o comando…» → `CO`). Nunca devolve vazio.
 */
internal fun iniciais(titulo: String): String {
    val primeira = titulo.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: return "?"
    return primeira.take(2).uppercase()
}

/**
 * Uma linha do menu dos Ajustes, como as das **Definições do Android**: 72dp de altura, o ícone
 * dentro de um círculo tonal de 40dp, o título com a linha de apoio por baixo, um chevron fino à
 * direita e o toque em toda a linha (com ripple).
 *
 * Antes era um `TextButton` com um ícone solto e pouco padding: as linhas ficavam finas e
 * encostadas umas às outras, e não se pareciam com nada do sistema — foi o autor que o notou
 * («o design não é igual aos menus do Android, são muito finos»).
 */
@Composable
fun LinhaDeAjuste(
    titulo: String,
    detalhe: String? = null,
    icone: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icone != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (!detalhe.isNullOrBlank()) {
                Spacer(Modifier.size(2.dp))
                // Duas linhas e não uma: com `maxLines = 1` o menu de Ajustes cortava 1 a 6 das
                // linhas de apoio conforme o tamanho do ecrã, sempre a meio de uma palavra
                // («…e dia…», «…the p…»). Medido no S10+ em 2026-10-15, a 457 dp, a 360 dp e com a
                // fonte a 1,3×. A 72 dp a linha continua a caber; só cresce quando precisa.
                Text(
                    detalhe,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A separação entre linhas do menu, recuada até onde começa o texto (não passa sob o ícone). */
@Composable
fun DivisoriaDeAjuste(recuo: Dp = 72.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = recuo),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Corpo do diálogo das licenças: a lista ou o texto integral da escolhida. */
@Composable
fun LicensesBody(
    licencas: List<Licenca>,
    aberta: Licenca?,
    onAbrir: (Licenca) -> Unit,
) {
    if (aberta == null) {
        LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(licencas) { _, lic ->
                TextButton(onClick = { onAbrir(lic) }) {
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
        return
    }
    Column {
        Text(aberta.nome, fontWeight = FontWeight.SemiBold)
        Text(
            aberta.licenca + " · " + aberta.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyColumn(modifier = Modifier.padding(top = 8.dp).heightIn(max = 420.dp)) {
            item {
                Text(
                    aberta.texto,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Peças do design (Stitch): micro-etiquetas, chips, barras e linhas de tabela. Ficam aqui para o
// Resumo, os Ajustes e a Chave falarem a mesma língua visual — era o que faltava da auditoria de
// 2026-10-14 (o tema estava aplicado, os ecrãs não).

/** Micro-etiqueta em maiúsculas com tracking — o rótulo do design («RESUMO DA APP»). */
@Composable
fun Etiqueta(texto: String, modifier: Modifier = Modifier, cor: Color = MaterialTheme.colorScheme.secondary) {
    Text(
        texto.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = cor,
        letterSpacing = 1.sp,
    )
}

/** Pill pequeno com contorno subtil — o «ON-DEVICE» / «HTTPS ONLY» do design. */
@Composable
fun Chip(texto: String, cor: Color = MaterialTheme.colorScheme.secondary) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = cor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Barra fina de progresso, 0..100 (o consumo de contexto do design). */
@Composable
fun BarraDeProgresso(percentagem: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percentagem.coerceIn(0, 100) / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
    }
}

/** Linha rótulo/valor: rótulo discreto à esquerda, valor em monospace à direita. */
@Composable
fun LinhaValor(rotulo: String, valor: String, cor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            rotulo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            valor,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            color = cor ?: Color.Unspecified,
        )
    }
}

/** Linha de verificação: ✓ / ⚠ / ✕, o texto e (quando há) o valor técnico em monospace. */
@Composable
fun LinhaVerificacao(texto: String, estado: String, valor: String = "") {
    val cor = when (estado) {
        "ok" -> MaterialTheme.colorScheme.primaryContainer
        "aviso" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    val glifo = when (estado) {
        "ok" -> "\u2713"
        "aviso" -> "\u26A0"
        else -> "\u2715"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(glifo, color = cor, modifier = Modifier.width(22.dp))
        Text(texto, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (valor.isNotBlank()) {
            Text(
                valor,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

