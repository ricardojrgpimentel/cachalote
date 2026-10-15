# Componentes de terceiros e atribuições

O **Cachalote** (este repositório) é um cliente Android para o **DeepSeek Harness**. Este
ficheiro regista o que é de quem — o que é obrigatório e o que é simplesmente honesto.

## 1. DeepSeek Harness — MIT
O motor que corre no telefone é o kernel oficial **`@deepseek-ai/dsh` 0.2.0-rc.2** (MIT),
extraído da instalação do desktop e adaptado ao Android pelos patches deste repositório
([`tools/patch-android.cjs`](tools/patch-android.cjs)). Nada do kernel é redistribuído aqui:
o pipeline extrai-o de uma instalação local que o utilizador já tem.

- Perfis, protocolo ACP/SDK, ferramentas e o frontend web: © DeepSeek, MIT.

## 2. woaiys3/deepseek-harness-android-app — MIT (**base de conhecimento**)
<https://github.com/woaiys3/deepseek-harness-android-app>

Esteve na origem de boa parte do que está documentado em
[`docs/knowledge-android.md`](docs/knowledge-android.md): as armadilhas do `targetSdk`/noexec,
os prefixos do Termux (`OPENSSLDIR`, `GIT_EXEC_PATH`), o `link()→rename()`, o dex gravável no
ART 15, o `spawnSync`, o `pkill -f`, os ids de plugins cordis e a forma de desactivar bundles.
O **código** deste repositório foi escrito de novo (shell da app, daemon, ponte, plugin,
barreira de capacidades); o **conhecimento de plataforma** veio de lá, e o overlay de patches
deles **não** foi usado (é de 0.1.5-rc.2 e parte o kernel 0.2.0-rc.2 — ver item 36 da base de
conhecimento). Obrigado ao autor.

## 3. Node.js — MIT
O runtime que corre o motor é o **Node 24** compilado para Android, obtido dos pacotes do
**Termux** (`nodejs-lts`) — ver [`tools/fetch-node-android.sh`](tools/fetch-node-android.sh).
© OpenJS Foundation e contribuidores, MIT.

## 3.1. Bibliotecas que viajam **dentro da APK** (modo sem root)

O modo embutido põe o runtime Node e as suas dependências no `nativeLibraryDir` como `lib*.so`
(`tools/prepare-embed.sh`, lista em `app/app/src/main/assets/embed-libs.txt`). Redistribuí-las
obriga a manter a atribuição e a licença de **cada uma**:

| Ficheiro na APK | Componente | Licença |
|---|---|---|
| `libnode.so` | Node.js 24.18.0 | MIT (© OpenJS Foundation e contribuidores) |
| `libc++_shared.so` | LLVM libc++ | Apache-2.0 **com** LLVM exception |
| `libssl_so_3.so`, `libcrypto_so_3.so` | OpenSSL 3 | Apache-2.0 |
| `libicudata_so_78.so`, `libicui18n_so_78.so`, `libicuuc_so_78.so` | ICU 78 | Unicode License (Unicode-3.0) |
| `libsqlite3.so` | SQLite | Domínio público |
| `libz_so_1.so` | zlib | zlib licence |
| `libcares.so` | c-ares | MIT |

Vêm dos pacotes do Termux (`nodejs-lts`, `openssl`, `libicu`, `libsqlite`, `zlib`, `c-ares`,
`libc++`) — ver [`tools/fetch-runtime.sh`](tools/fetch-runtime.sh), que procura os nomes no
índice do repositório em vez de os fixar.

**Pendente de cumprimento estrito:** as licenças Apache-2.0 pedem uma **cópia do texto** da
licença e a retenção dos ficheiros `NOTICE` das obras redistribuídas. Hoje este ficheiro
identifica e liga para os projectos; falta juntar os textos integrais numa pasta `licenses/`
(ou num ecrã de licenças na app). É mecânico e está na lista de tarefas.

## 4. Termux — GPL-3.0 (apenas a proveniência dos binários)
O binário de Node e as bibliotecas partilhadas vêm do repositório de pacotes do Termux. O
Termux está sob GPL-3.0; aqui **não** redistribuímos código do Termux, apenas descarregamos
os seus binários de Node (que são MIT, do projecto Node). Se redistribuíres uma APK com esses
binários, mantém esta nota.

## 5. Shizuku — Apache-2.0
Previsto para o flavor sem root (arrancar o daemon como *shell*). Ainda **não** implementado;
quando for, o SDK é `dev.rikka.shizuku` (Apache-2.0), © RikkaApps.

## 6. AndroidX / Compose / Material 3 — Apache-2.0
A app usa Jetpack Compose e bibliotecas AndroidX, © Google, Apache-2.0.

## 7. Sem código do Operit
O projecto de referência portou o *virtual display* do **Operit** (LGPL-3.0). O Cachalote
**não** inclui essa parte — é uma decisão em aberto no plano (D8): ou se porta cumprindo a
LGPL-3.0, ou se reimplementa. Enquanto isso, **nada de LGPL entra neste repositório**.

---

## 8. Fontes do design — SIL Open Font License 1.1 (OFL)

O tema da app é gerado do sistema de design (`design/sistema/tema.json`) e as três famílias que o
desenho usa estão **empacotadas** na app, em `app/app/src/main/res/font/` — todas variáveis (um
ficheiro por família, com o eixo de peso `wght`) e todas OFL-1.1, que permite o uso e a
redistribuição desde que a licença e o copyright acompanhem os ficheiros (é o que os
`assets/licenses.json` fazem, dentro da app, no ecrã *Ajustes → Backup e licenças*).

| Família | Onde se usa | Copyright | Fonte |
|---|---|---|---|
| Space Grotesk | títulos (`headline*`) | © 2020 The Space Grotesk Project Authors | <https://github.com/floriankarsten/space-grotesk> |
| Geist | texto (`body*`) | © 2024 The Geist Project Authors | <https://github.com/vercel/geist-font> |
| JetBrains Mono | strings técnicas (`label*`, `Mono`) | © 2020 The JetBrains Mono Project Authors | <https://github.com/JetBrains/JetBrainsMono> |

Os ficheiros vieram do repositório `google/fonts` (a versão publicada no Google Fonts) e o texto
integral da OFL está em `assets/licenses.json`. As licenças dos projectos DSH, woaiys3 e outros
mantêm-se nas secções acima.

## Licença deste repositório

MIT — ver [`LICENSE`](LICENSE). As notas acima aplicam-se aos componentes de terceiros
referidos; para os usar, cumpre as licenças respectivas.
