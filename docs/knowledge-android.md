# Armadilhas de correr o DSH no Android — base de conhecimento

Cada item: **sintoma → causa → solução → origem**. Objectivo: não voltar a pagar nenhum destes bugs.

Legenda de origem:
- **[DSHAA]** — verificado no código/documentação de [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) (MIT; analisado localmente em `repo/`)
- **[LOCAL]** — verificado por nós, neste dispositivo (S10+ / LineageOS 23.2 / Android 16 / Magisk)
- **[DOC]** — documentação oficial da plataforma

---

## A. Runtime e execução

### 1. `targetSdk ≥ 29` torna o directório privado da app não-executável
- **Sintoma:** `node: EACCES` (Permission denied) ao lançar o binário; instalação “funciona” mas o motor nunca arranca.
- **Causa:** a partir da API 29, apps com `targetSdk ≥ 29` não podem executar ficheiros em `app_data_file`. Com `targetSdk ≤ 28` o SELinux coloca o processo no domínio legado `untrusted_app_27`, que mantém `execute_no_trans`.
- **Solução (escolher):** (a) fixar `targetSdk 28` — é o que o **[DSHAA]** faz e o que o Termux faz; (b) empacotar o executável como `jniLibs/arm64-v8a/lib*.so` — o `nativeLibraryDir` é executável mesmo com `targetSdk ≥ 29`; (c) **não correr o motor dentro do sandbox** (daemon lançado por `su`/Shizuku) — é a nossa decisão D2.
- **Origem:** **[DSHAA]** (`docs/开发指南.md` nota 1), **[LOCAL]**, **[DOC]**. Evidência local: `plat_seapp_contexts` tem `user=_app minTargetSdkVersion=28 domain=untrusted_app_27` e a política contém `(allow untrusted_app_27 app_data_file (file (execute_no_trans)))`; `untrusted_app`, `_29`, `_30`, `_32` têm **zero** regras `allow … app_data_file`.

### 2. O kernel DSH exige `--expose-internals`
- **Sintoma:** o motor arranca a meio, HMR/internals rebentam; arranque falha sem mensagem óbvia.
- **Causa:** o kernel usa módulos internos do Node.
- **Solução:** arrancar sempre com `node --expose-internals <dsh>/lib/bin.js …`.
- **Origem:** **[DSHAA]**, **[LOCAL]** — confirmado no lançador oficial do desktop: `ELECTRON_RUN_AS_NODE=1 … --expose-internals app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js`.

### 3. `LD_LIBRARY_PATH` obrigatório para o node
- **Sintoma:** `CANNOT LINK EXECUTABLE "node": library "libz.so.1" not found`.
- **Causa:** runtime portátil com `.so` ao lado, não no `system`.
- **Solução:** `LD_LIBRARY_PATH=<runtime>/lib` ao lançar o node (e em qualquer `PayloadScript`).
- **Origem:** **[DSHAA]**.

### 4. Binários compilados para Termux têm prefixos fixos no código
- **Sintoma:** em máquinas com Termux instalado, o node morre à nascença com `EACCES` ao ler `/data/data/com.termux/files/usr/etc/...`; sem Termux, HTTPS falha com `(77) error adding trust anchors`, e `git` diz `unable to find remote helper for 'https'`.
- **Causa:** `OPENSSLDIR`, `GIT_EXEC_PATH`, `GIT_TEMPLATE_DIR` ficam compilados a apontar para o prefixo do Termux.
- **Solução:** injectar no ambiente do motor: `OPENSSL_CONF` (cópia local do cnf), `SSL_CERT_FILE`/`CURL_CA_BUNDLE`/`GIT_SSL_CAINFO` (bundle de CAs sintetizado do sistema), `GIT_EXEC_PATH`, `GIT_TEMPLATE_DIR`, `GIT_PAGER=cat`, `GIT_TERMINAL_PROMPT=0`, e reescrever URLs `ssh://`→`https://` via **ficheiro** de config (`GIT_CONFIG_GLOBAL`), **não** por env vars.
- **Porquê o ficheiro e não env:** o DSH limpa o ambiente dos subprocessos com o padrão `/KEY|PASSWORD|SECRET|TOKEN/i`; `GIT_CONFIG_KEY_0` cai nesse filtro enquanto `GIT_CONFIG_COUNT` sobrevive → `error: missing config key GIT_CONFIG_KEY_0`.
- **Origem:** **[DSHAA]** (comentários em `spawnNode`).

### 5. Não há `/usr/bin/env` no Android
- **Sintoma:** wrappers falham com `No such file or directory` apesar de existirem.
- **Solução:** shebang `#!/system/bin/sh` em todos os wrappers (bash→sh, pnpm, etc.).
- **Origem:** **[DSHAA]**.

---

## B. Sistema de ficheiros e SELinux

### 6. SELinux proíbe `link()` — escritas atómicas por `rename()`
- **Sintoma:** persistência de sessões e anexos falham; erros de `EPERM`/`EXDEV` em gravações “atómicas”.
- **Causa:** hardlinks bloqueados (e `/sdcard` é FUSE).
- **Solução:** patch ao `dsh-session-persistence-jsonl` e ao `dsh-attachment-local`: `link → rename`.
- **Origem:** **[DSHAA]** (`dsh-patches/README.md`).

### 7. Symlinks não sobrevivem ao empacotamento em APK
- **Sintoma:** bibliotecas “presentes” não carregam.
- **Solução:** empacotar ficheiros reais + um `LINKS.txt` e reconstruir os symlinks na primeira execução.
- **Origem:** **[DSHAA]**.

### 8. Auto-extração de 25 k ficheiros precisa de marcadores e de nunca tocar nos dados do utilizador
- **Sintoma:** arranques de 50–60 s; upgrades que apagam config/credenciais; “engines” duplicados.
- **Solução adoptada por eles:** extrair para armazenamento **interno** (rápido), marcadores `REVISION` / `.extracted` / `.complete`, modo incremental (`*-add`), limpeza de órfãos só no namespace `@deepseek-ai/dsh*`, e **jamais** sobrescrever `settings.yaml` / `.credentials.yaml`. Nós evitamos o problema por desenho (D2: o payload vive fora do APK).
- **Origem:** **[DSHAA]**.

### 9. Android 15+ recusa carregar dex “gravável”
- **Sintoma:** `Writable dex file is not allowed` → `Abort` no `app_process`.
- **Solução:** `chmod 444` no dex antes de cada invocação, com auto-reparo.
- **Origem:** **[DSHAA]** (`ensureDexReadOnly`).

---

## C. Privilégios

### 10. `rish` (Shizuku em subprocesso) é frágil; a API na app não é
- **Sintoma:** `Request timeout. The connection between the current app … and Shizuku app …`; funciona e depois deixa de funcionar.
- **Causa:** alguns ROM (ColorOS) congelam/matam a app Shizuku; o subprocesso `rish` fica à espera e expira.
- **Solução:** executar privilégios através da **API do Shizuku no processo da app** (rota local autenticada), deixando `rish` apenas como fallback; pedir autorização por **package name/uid reais** (nunca hardcoded entre flavors).
- **Origem:** **[DSHAA]**.

### 11. `app_process`/`su` herdam um `LD_LIBRARY_PATH` envenenado
- **Sintoma:** `cannot find libz.so from verneed[1]` ao lançar `app_process`.
- **Causa:** o `libz.so` do runtime do DSH contamina o linker do sistema.
- **Solução:** `sanitizeEnv()` — remover `LD_LIBRARY_PATH`, `LD_PRELOAD`, `LD_DEBUG` antes de invocar `app_process`/`su`.
- **Origem:** **[DSHAA]**.

### 12. Nunca usar `spawnSync` para comandos demorados
- **Sintoma:** o frontend mostra `Failed to fetch` enquanto o comando corre.
- **Causa:** bloqueia o event loop do node → HTTP/WebSocket param.
- **Solução:** `spawn` assíncrono + timeout + streaming.
- **Origem:** **[DSHAA]**.

### 13. `pkill -f <padrão>` pode matar-se a si próprio
- **Sintoma:** o motor morre quando tenta limpar processos antigos.
- **Causa:** a própria linha de comando contém o padrão (self-match).
- **Solução:** `pkill -x node` (ou filtrar por PID).
- **Origem:** **[DSHAA]** (`docs/开发指南.md` nota 5).

### 14. Tecto do Shizuku (uid 2000) — o que não dá
- ✅ ler `/system`, `/vendor`; ler/escrever `/data/local/tmp` e `/sdcard`; `pm`/`am`/`settings`/`dumpsys`; `input`; `screencap`.
- ❌ dados privados de outras apps, `/data/system`, escrita em `/system`, root real. `pm install` falha em alguns ROM (ColorOS) por limites de binder.
- **Origem:** **[DSHAA]** (`docs/开发指南.md` §9, `plugins/README.md`).

---

## D. Acessibilidade e ecrã

### 15. A via sem privilégios existe e é complementar
- `AccessibilityService` dá ler a árvore de controlos, tocar por texto/descrição/coordenada, escrever texto, voltar/home, scroll e screenshot — **sem root nem Shizuku**. Em WebView/inputs de página, usar colagem por clipboard (`paste:true`) em vez de injectar teclas.
- **Origem:** **[DSHAA]** (`plugins/dsh-tool-accessibility`, README).

### 16. Virtual display exige identidade *shell*
- Criar um display virtual, lançar apps nele, capturar e injectar toques só funciona a partir de um servidor Java lançado com `app_process` sob Shizuku/root. Preview por **MediaCodec H.264 → decode → overlays**, não por polling de screenshots. Display do tipo `PUBLIC` comporta-se de forma diferente do principal com IME/popups.
- **Licença:** a implementação deles é derivada do Operit → **LGPL-3.0** (ver D8 do plano).
- **Origem:** **[DSHAA]**.

---

## E. UI / WebView

### 17. `prefers-color-scheme` do WebView depende do tema da app, não do `uiMode`
- **Sintoma:** tema claro/escuro não acompanha o sistema; barra de estado com cor errada.
- **Causa:** Chromium decide por `android:isLightTheme` do tema aplicado.
- **Solução:** `setTheme()` entre variantes `Theme`/`Theme.Light` no `onCreate`, não incluir `uiMode` em `configChanges` (o recreate é o que faz o tema voltar a aplicar), e declarar `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` (temas Holo não o fazem ⇒ `setStatusBarColor` vira no-op).
- **Origem:** **[DSHAA]** (`docs/开发指南.md` §4).

### 18. WebView antiga (Chromium < 94) → página branca
- **Sintoma:** motor OK, interface toda branca sem erro.
- **Solução:** detecção de versão + mensagem accionável; ou flavor “compat” com GeckoView (custa +100 MB e `minSdk 26`).
- **Origem:** **[DSHAA]** (issue #38, README v1.17.3).

### 19. `configChanges` incompleto ⇒ o sistema recria a Activity
- **Sintoma:** “flash branco” e re-play do ecrã de arranque ao arrastar o divisor no modo dividido em tablet.
- **Solução:** incluir `screenLayout|smallestScreenSize` além de `orientation|screenSize|keyboardHidden`.
- **Origem:** **[DSHAA]** (issue #37).

### 20. Views nativas não podem ser montadas duas vezes
- **Sintoma:** crash `IllegalStateException` ao alternar entre ecrãs.
- **Solução:** `detachView()` antes de voltar a anexar (ou trocar WebView por Compose/`AndroidView` bem gerido — o nosso caso).
- **Origem:** **[DSHAA]** (`docs/开发指南.md` nota 9).

### 21. Actualizações de UI a partir de callbacks de binder
- **Sintoma:** crash/“only the original thread that created a view hierarchy can touch its views”.
- **Solução:** publicar sempre para a main thread.
- **Origem:** **[DSHAA]** (nota 10).

---

## F. Ciclo de vida e keep-alive

### 22. O motor morre com a app sem foreground service
- **Sintoma:** tarefa longa morre quando o ecrã apaga ou a app vai para background.
- **Solução deles:** `ForegroundService` com notificação persistente (`START_STICKY`), mais `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` e aviso em ROM agressivos (ColorOS).
- **Atenção:** o Android 15 introduziu **limite de tempo** para o tipo `dataSync` (janela de ~6 h por 24 h) — para um motor “sempre ligado” isso é um problema; preferir `specialUse` (justificação na loja) **ou** o nosso D2 (daemon fora do AMS, que não tem este limite).
- **Origem:** **[DSHAA]**, **[DOC]**.

### 23. Tarefas de fundo agendadas
- O kernel 0.2.0 já traz `@deepseek-ai/dsh-schedule` (entrega follow-up na sessão); eles removeram o agendador próprio. Não vale a pena reimplementar alarmes.
- **Origem:** **[DSHAA]** (README v1.19).

### 24. Modos de resgate são obrigatórios num produto destes
- **Sintoma:** plugin/plugin-config com YAML inválido ⇒ motor recusa arrancar ⇒ utilizador sem app.
- **Solução deles:** “modo seguro” que põe de lado a camada de utilizador e arranca com config de fábrica; exportação/importação total de dados; auto-verificação de integridade do kernel com relatório (`sha256` por ficheiro) e ledger de reparações; nunca “reparar” apagando a sessão.
- **Origem:** **[DSHAA]** (README v1.15+, v1.19.1+).

---

## G. Segurança

### 25. Loopback não é um canal privado
- **Sintoma (risco):** qualquer app no dispositivo pode chamar `127.0.0.1:<porta>` e, se as portas derivam do package name, adivinhá-las — ganhando leitura de ecrã, screenshot, injecção de gestos, alteração de definições e shell.
- **Solução deles:** token aleatório por instalação (partilhado app↔motor por env var e enviado em header) em todos os serviços locais; verificação de `sha256` do jar privilegiado antes de o lançar.
- **A nossa solução (D4):** **um** AF_UNIX em `0600` + token, em vez de quatro servidores TCP.
- **Origem:** **[DSHAA]** (v1.18.0), decisão nossa.

### 26. Credenciais
- API keys nunca dentro do APK nem em ficheiros empacotados; o build deles tem uma verificação que falha se aparecer `.credentials.yaml`/chaves no payload. No nosso caso, a chave fica no `home/` do daemon (fora do APK) com `0600`.
- **Origem:** **[DSHAA]**.

---

## H. Kernel / composição (cordis)

### 27. Desactivar plugins por `id` errado é um no-op silencioso
- **Sintoma:** o motor arranca e fica **pendente** para sempre (`waiting for sandbox service`), node a morrer e a ser relançado.
- **Causa:** o `id` do loader não é o nome do pacote (`pwsh-sandbox` ≠ `dsh-pwsh-sandbox`).
- **Solução:** confirmar o `id` real na árvore; verificar serviços pendentes no fim do arranque.
- **Origem:** **[DSHAA]** (`config/cordis.patch.yml`).

### 28. Não desactivar `dsh-sandbox-local` sem perceber as consequências
- **Sintoma:** **todas** as sessões falham a criar/retomar (`workflow-ptc: waiting for ptcRuntime`).
- **Causa:** `ptc-runtime` injecta `sandbox`, e os presets montam `workflow-ptc`.
- **Solução:** manter activo; ele só falha *fail-closed* quando `confine()` é chamado (o preset por omissão é `danger-full-access`, que não confina).
- **Origem:** **[DSHAA]**.

### 29. Substituições obrigatórias de módulos nativos
- `node-pty` → `child_process`; `sharp` → parser de cabeçalhos em JS puro; carregar `koffi` de forma **lazy** (o `sandbox-local` só o procura em runtime).
- **Porquê (confirmado com versões):** `sharp` 0.35.5 publica 25 pacotes de plataforma e **nenhum para Android**; `node-pty` 1.1.0 (já Node-API, não NAN) só traz prebuilds darwin/win32; `@vscode/ripgrep` não é addon, é um downloader de binário. **Só `koffi` tem prebuild oficial Android** (`@koromix/koffi-android-arm64`).
- **Pacotes a desactivar** (não têm sentido/estrutura no Android): `bash-sandbox` (substituído por `bash-local`), `pwsh-sandbox` (não há `pwsh`), telemetria OTel, `session-log-download`.
- **Origem:** **[DSHAA]** (`dsh-patches/`, `config/cordis.patch.yml`), [`research/bun-vs-node-android.md`](../research/bun-vs-node-android.md).

### 30. Perfis: o que é preciso materializar
- Um perfil = directório com `cordis.yml` (array vazio), `cordis.patch.yml`, `package.json` (`dsh.profile.bundles`), `pnpm-workspace.yaml`. Templates do kernel: `web`=[`dsh-base`,`dsh-web-app`], `headless`=[`dsh-base`,`dsh-headless`], `sdk`=[`dsh-base`,`dsh-sdk-app`], `sdk-minimal`=[`dsh-sdk-minimal`]. O projecto de referência só materializa `web/` — daí a limitação de só expor a UI.
- **Origem:** **[LOCAL]** (`~/.dsh/profiles/desktop/`), **[DSHAA]** (issue #32).

---

## I. Build

### 31. Cadeia de build sem Gradle (só para referência)
Eles usam `aapt → javac → d8 → zipalign → apksigner` com `android.jar` explícito, `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`, sem lambdas sob bootclasspath e com a regra “não pôr comentários XML dentro da lista de atributos de um elemento” (aapt dá `not well-formed`). **Nós usamos Gradle + AGP**, portanto estes itens são históricos — excepto o lembrete de que ferramentas antigas de AAPT são frágeis com comentários e encoding.
- **Origem:** **[DSHAA]** (`docs/开发指南.md` §5).

### 32. Emulador x86_64 + node arm64
Se algum dia testarmos em emulador: precisa de `-writable-system`, das `.so` de x86_64 com nome “nu” no `LD_LIBRARY_PATH`, e do Shizuku relançado por adb após cada arranque.
- **Origem:** **[DSHAA]** (`docs/开发指南.md` §6).

---

## J. Runtime: becos sem saída (para não repetir a investigação)

### 33. Bun **não** é alternativa ao Node para o kernel DSH
- **Três bloqueios independentes, cada um suficiente:** (a) o `--expose-internals` do Bun só resolve os internals *do próprio Bun* — `internal/modules/{esm/loader,cjs/loader,helpers,esm/utils,esm/resolve}`, que o `dsh-app-boot` pede por `node-addon-require-builtin` **sem `try/catch`**, dão `MODULE_NOT_FOUND` (testado a executar os dois runtimes); (b) o Bun é JavaScriptCore, não V8 ⇒ `node:sea`, `--build-snapshot`/`--snapshot-blob` e `v8.serialize` não existem/divergem, `process.binding` é parcial, e a própria documentação admite `AsyncLocalStorage` que não atravessa `Worker`/`MessagePort` e `http` `keepAlive`/`listen(fd)`/`signal` como no-ops; (c) o binário Android oficial (`bun-linux-aarch64-android`, Bionic, 94,7 MB vs 45,3 MB do node do Termux) **é morto por `SIGSYS` em `close_range`** em qualquer dispositivo stock (issue #30766 aberto, correcção por mergear, zero `SYS_SECCOMP` na árvore 1.4.3) — o Termux só o traz com 11 patches. Além disso, o Bun **ignora flags desconhecidas em silêncio**, o que transforma falhas de compatibilidade em avarias tardias e confusas.
- **Origem:** [`research/bun-vs-node-android.md`](../research/bun-vs-node-android.md) (experiências em primeira mão, Bun 1.4.3).

### 34. Empacotamento SEA/snapshot é específico de arch + OS + versão de Node
- **Sintoma:** um snapshot/SEA construído no Mac (ou noutra versão de Node) não arranca no telemóvel.
- **Causa:** o blob serializa o heap/builtins do V8 daquele binário concreto.
- **Solução:** construir **no binário aarch64-android** (e na mesma versão de Node que vai correr). O `dsh/lib/bin.js` é ESM ⇒ precisa de wrapper CJS; o builder de snapshot recusa módulos de utilizador extra ⇒ tudo inline num só entry. Compatível com `--expose-internals` via `execArgv`.
- **`NODE_COMPILE_CACHE`:** perda líquida **antes** de empacotar (187,6 → 247 ms; 4 712 ficheiros/5,3 MiB), ganho **depois** (60,0 → 51,0 ms) — usar `portable:true` e pré-aquecer durante a primeira extracção.
- **Origem:** [`research/bun-vs-node-android.md`](../research/bun-vs-node-android.md) §5.

---

## K. Kernel: o que impede mesmo o arranque no Android (descoberto no F0)

### 35. `dsh-app-boot` exige o addon nativo `node-addon-require-builtin`
- **Sintoma:** arranque aborta com `dsh: fatal uncaught exception: Error: dsh: host preparation failed: No usable native binding found for node-addon-require-builtin-android-arm64 (auto)`. Nada arranca — nem o perfil `web`.
- **Causa:** `dsh-app-boot/lib/index.js` (~linha 1575) faz `createRequire(...)("node-addon-require-builtin")` e usa `addon.requireBuiltin(...)` para carregar `internal/modules/{esm/loader,cjs/loader,helpers,esm/utils,esm/resolve}`. O addon é Node-API com prebuilds **só** darwin/win32/linux-gnu — **não existe Android**. O caminho não tem `try/catch`.
- **Solução (verificada no dispositivo):** o Node 24.18.0 com `--expose-internals` carrega os **cinco** internals por `require()` normal. Logo o addon é dispensável: tornar o carregamento opcional e cair para `require(id)`. 5 linhas, sem NDK, sem compilar nada. Ver `tools/patch-android.cjs`.
- **Origem:** **[LOCAL]** (S10+, `/data/local/tmp`, uid shell).

### 36. O overlay do projecto de referência é de 0.1.5-rc.2 — não serve para 0.2.0-rc.2
- **Sintoma:** com o overlay aplicado, o motor arranca mas o loader recusa plugins: `@deepseek-ai/dsh-tool-fs-search@0.1.5-rc.2 is incompatible with dsh 0.2.0-rc.2`, e `subprocess (dsh-subprocess-local): failed to import`, `sandbox (dsh-sandbox-local): failed to import` ⇒ `permission`, `tool-bash`, `ptc-runtime`, `workflow-ptc`, `tool-workflow` ficam **pending** (o agente não consegue correr ferramentas).
- **Causa:** os ficheiros em `dsh-patches/overlay/` são de 0.1.5-rc.2 (verificar `version` nos `package.json`), apesar de o README deles falar de 0.2.0-rc.2. Misturar versões na mesma árvore é exactamente o que a funcionalidade deles de "auto-cura de árvore misturada" remenda.
- **Solução:** **não aplicar o overlay deles**. Escrever patches mínimos contra os originais da versão em uso (ver a tabela no `PLANO.md`, secção F0).
- **Origem:** **[LOCAL]**.

### 37. Imports nativos **estáticos** que matam o arranque (0.2.0-rc.2)
- `dsh-session-persistence-jsonl/lib/index.js:11` → `import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock"` (bloqueia `session/new` com `ACP session persistence flush failed`).
- `dsh-sandbox-local/lib/index.js:6` → `import { LAUNCHER_BIN, grantArgs, launcherPath, probe } from "@deepseek-ai/node-addon-system/landlock-run"`.
- **Solução:** stubs de JS para esses dois subpaths. O daemon é mono-processo (o `flock` cruzado não é necessário) e o sandbox por omissão não confina (`danger-full-access`), portanto o `confine()` continua *fail-closed* se algum dia for chamado.
- **Nota:** `node-pty` (subprocess), `sharp` (attachment) e `@vscode/ripgrep` (fs-search) são **lazy** em 0.2.0-rc.2 — não impedem o arranque; só rebentariam no primeiro uso.
- **Origem:** **[LOCAL]**.

---

## L. Armadilhas encontradas no F1 (daemon, composição e credenciais)

### 38. `@deepseek-ai/dsh-win32-process` é importado **estaticamente** em qualquer plataforma
- **Sintoma:** depois de cortar "pacotes de outras plataformas" da árvore, o motor arranca mas
  `subprocess` e `sandbox` dão `failed to import`; tudo o que dependa de `ctx.shell`
  (`permission`, `tool-bash`, `ptc-runtime`, `workflow-ptc`) fica pendente.
- **Causa:** `dsh-subprocess-local` e `dsh-sandbox-windows-acl` fazem `import` de
  `@deepseek-ai/dsh-win32-process` no topo do módulo, **mesmo em Linux/Android**. É JS (não é
  nativo) e **não pode ser cortado**.
- **Solução:** manter o pacote no trim. (Foi o único pacote de "outra plataforma" que se
  revelou obrigatório.)
- **Origem:** **[LOCAL]**.

### 39. `dsh-tool-bash` lê `ctx.get("sandboxPolicy")` sem o declarar em `inject`
- **Sintoma:** `tool-bash: the mounted bash executor confines but ctx.sandboxPolicy is missing`
  — e, por arrasto, o `session/new` do ACP falha (`ACP session teardown failed: … persistence
  flush failed`), porque o erro visível vem do *teardown* de uma criação que já tinha falhado.
- **Causa:** corrida de composição. Com o executor do desktop (`bash-sandbox`) o
  `sandbox-policy` calhava já construído; com o `bash-local` (que só injecta `subprocess`) o
  `tool-bash` monta primeiro e lê `undefined`.
- **Solução:** acrescentar `"sandboxPolicy"` à lista `inject` do `dsh-tool-bash`. O
  `sandbox-policy` faz parte do `dsh-base` em qualquer perfil, portanto a dependência é segura.
- **Origem:** **[LOCAL]**.

### 40. `dsh-permission-presets` recusa montar sem `sandboxMode` no executor
- **Sintoma:** `permission: the mounted bash executor does not confine (no sandboxMode) — presets
  bundle a sandbox mode, so composing this plugin over an unconfined executor is a
  misconfiguration`.
- **Causa:** o executor do `dsh-bash-local` herda um `sandboxMode` indefinido.
- **Solução:** `get sandboxMode() { return "workspace-write"; }` no `LocalBashExecutor`.
  ⚠ **No Android isto é declarativo** (não há Landlock): quem trava é a aprovação. Ver D10.
- **Origem:** **[DSHAA]** (faziam o mesmo) + **[LOCAL]** (confirmado no 0.2.0-rc.2).

### 41. `link(2)` é negado pelo SELinux também em `/data/local/tmp` e `/data/adb`
- **Sintoma:** `EACCES: permission denied, link '…/session.v4.jsonl.zstd.5af…'` ao criar sessão
  (o ACP reporta-o como `ACP session persistence flush failed`).
- **Causa:** publicar o artefacto atómico com `link()` + `unlink()` do temporário.
- **Solução:** **5 pontos** de `link()` → `rename()` em dois pacotes:
  `dsh-session-persistence-jsonl` (2 + o objecto `defaultFileSystem`) e
  `dsh-attachment-local` (2). `rename()` no mesmo sistema de ficheiros é atómico e permitido.
  Atenção: `rename` tem de vir de `node:fs/promises` (as chamadas são `await`).
- **Origem:** **[DSHAA]** (item 6) + **[LOCAL]** (localização exacta e correcção).
- **Nota histórica:** o **overlay deles é 0.1.5-rc.2** e misturá-lo com 0.2.0-rc.2 faz o
  loader recusar plugins; por isso escrevemos os nossos patches (ver item 36).

### 42. Credenciais: onde é que o motor as lê (e como as meter sem as expor)
- **Precedência:** ambiente herdado > `$DSH_HOME/.credentials.yaml` > `<cwd>/.env` > `$DSH_HOME/.env`.
- **No perfil ACP a variável é `DEEPSEEK_API_KEY`** (a mensagem de erro diz explicitamente
  `store DEEPSEEK_API_KEY through the credentials service … or export DEEPSEEK_API_KEY`).
- **Solução adoptada:** `$DSH_HOME/.env` com modo **600**, escrito por um script que o
  utilizador corre (`daemon/set-key.sh`): a chave vai por **stdin** (nunca em `argv`, nunca no
  histórico, nunca ecoada). Verificado ponta-a-ponta com um valor de teste: o pedido chega à
  API e volta `Authentication Fails … is invalid`.
- **Origem:** **[LOCAL]**.

### 43. O daemon pode (e deve) viver fora do sandbox da app
- Correr o motor a partir de `/data/adb/cachalote` como root **elimina** de uma vez: o problema
  do `targetSdk`/noexec (item 1), o limite de 6 h/24 h do foreground service `dataSync`, a
  re-extração de 10 k+ ficheiros a cada actualização do APK e a morte por LMK do processo da app.
- **Custo:** precisa de root (Magisk) ou Shizuku; e a app passa a ser um *cliente* (socket), não
  o dono do motor.
- **Origem:** decisão nossa (D2), verificada no dispositivo.

---

## M. App ↔ daemon: o que o SELinux permite (e como se contorna)

### 44. Uma app normal **não** se consegue ligar ao socket AF_UNIX em `/data/adb`
- **Sintoma:** a app tenta `connect()` ao socket do daemon e recebe `EACCES`/timeout, mesmo
  com o ficheiro legível.
- **Causa:** o SELinux rotula `/data/adb` (e tudo lá dentro) como **`adb_data_file`**, dono
  root, modo 0600. Não existe regra que permita `untrusted_app` escrever/ligar-se a esse tipo
  — e o mesmo vale para `shell_data_file`, onde `untrusted_app_all` só tem `read/getattr` de
  ficheiros, não `unix_stream_socket connectto`.
- **Solução adoptada:** o daemon abre **também** um canal **TCP em 127.0.0.1** autenticado por
  **token de 128 bits** (`home/app.token`, 0600, root). A app obtém o token uma vez por
  `su -c 'cachalote app-token'` e guarda-o nas suas prefs privadas. Verificado no S10+.
- **Alternativas:** socket dentro do directório privado da app (o daemon criaria e faria
  `chown` para o uid da app — exige o daemon saber o pacote); ou `su -c` por operação.
- **Origem:** **[LOCAL]** (política do dispositivo + teste real).

### 45. Como um cliente dirige o agente por ACP (receita verificada)
- **Handshake:** `initialize` `{protocolVersion: 1, clientCapabilities: {}, clientInfo: {...}}`
  → `{protocolVersion, agentInfo:{name:"deepseek-harness-acp",version:"0.0.1"}, agentCapabilities, authMethods: []}`.
  **Não** pede autenticação (`authMethods: []`) — a credencial do modelo vem do ambiente/ficheiro.
- **Sessão:** `session/new` `{cwd, mcpServers: []}` → `{sessionId, configOptions:[…]}`.
  O `sessionId` é escolhido pelo servidor; guarda-o para os turnos seguintes.
- **Turno:** `session/prompt` `{sessionId, prompt:[{type:"text",text:"…"}]}`. A resposta
  **só chega no fim do turno** (`{stopReason}`) — o texto vem pelas notificações.
- **Streaming:** notificações `session/update` com
  `params.update.sessionUpdate` ∈ `agent_message_chunk` (`content.text`),
  `agent_thought_chunk`, `tool_call` (`title`, `toolCallId`, `status`, `rawInput`),
  `tool_call_update`, `usage_update`, `config_option_update`. O cliente deve **filtrar por
  `params.sessionId`**. O pensamento vem como `content.type = "reasoning"` → chunk separado.
- **Cancelamento:** `session/cancel` (notificação) — o ACP traz o ciclo de vida completo
  (`session/list`, `session/resume`, `session/close`, `session/set_config_option`), ao
  contrário do perfil `sdk`, que só tem `initialize`, `session/prompt` e `shutdown`.
- **Origem:** **[LOCAL]** (execução real) + código do `dsh-acp` 0.2.0-rc.2.

### 46. Compilar APK neste Mac: JDK e Gradle
- **JDK:** o do Android Studio serve — `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
  (OpenJDK 21). Não há Java no PATH do sistema.
- **Gradle:** existe `gradle-8.12-all` em cache em `~/.gradle/wrapper/dists/` (usar esse).
  ⚠ Uma distribuição 8.13 descarregada à mão falhou com
  `Could not initialize native services / Failed to load native library 'libnative-platform.dylib'`,
  apesar de o ficheiro existir — usar a distribuição em cache ou `-Dorg.gradle.native=false`.
- **SDK:** `~/Library/Android/sdk` (build-tools 34/36, platforms 35/36). `app/local.properties`
  leva `sdk.dir` e **não** é versionado.
- **Origem:** **[LOCAL]**.

---

## N. F2: app nativa — o que morde

### 47. O motor lê as credenciais **no arranque**
- **Sintoma:** a chave é gravada em `home/.env` (confirmado, modo 600) mas o turno seguinte
  ainda responde `llm-deepseek: no API key for provider route "deepseek-official"`.
- **Causa:** o serviço de credenciais resolve as chaves quando o perfil arranca; uma alteração
  ao ficheiro com o motor a correr **não** chega ao turno (apesar de o `dsh-credentials-local`
  falar em *hot-publish*, a escrita por `rename` não foi apanhada neste perfil).
- **Solução:** reiniciar o motor depois de alterar credenciais. No daemon, o
  `cachalote.credentials.set`/`remove` **agenda** o restart (700 ms depois, para a resposta ao
  cliente sair primeiro). Sem isto, o utilizador grava a chave e continua sem resposta.
- **Confirmação:** depois do restart, o mesmo prompt devolve
  `Authentication Fails, Your api key: ****7890 is invalid (request_id: …)` — ou seja, a chave
  chegou à API.
- **Origem:** **[LOCAL]** (teste de UI ponta-a-ponta).

### 48. `NetworkOnMainThreadException` ao falar com o daemon
- **Sintoma:** `prompt: NetworkOnMainThreadException` (ou em qualquer operação) no ecrã da app.
- **Causa:** o cliente fazia `socket.write()` no dispatcher de chamada, que no
  `viewModelScope` é o **Main**.
- **Solução:** todo o `call()` corre em `Dispatchers.IO` (`withContext`), incluindo a escrita do
  frame e a espera da resposta. Regra: nenhum acesso a socket fora do IO.
- **Origem:** **[LOCAL]**.

### 49. Compilar APK dentro de um sandbox de ficheiros
- **Sintoma:** `Could not initialize native services / Failed to load native library
  'libnative-platform.dylib'` seguido de, mais fundo,
  `FileNotFoundException: ~/.gradle/native/…/libnative-platform.dylib.lock (Operation not permitted)`;
  e depois `Unable to create debug keystore in ~/.android because it is not writable`.
- **Causa:** o harness só deixa escrever dentro do workspace; o Gradle e o AGP querem escrever
  em `~/.gradle` e `~/.android`.
- **Solução:** `GRADLE_USER_HOME=<workspace>/.work/gradle-home` e
  `ANDROID_USER_HOME=<workspace>/.work/android-home` (o keystore de debug passa a ser criado
  lá dentro). JDK: o do Android Studio (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
  ⚠ O erro inicial aponta para a biblioteca nativa, mas a causa real é a permissão de escrita
  no `.lock` — ler o `--stacktrace` até ao fim poupa muito tempo.
- **Origem:** **[LOCAL]**.

---

## O. F3: aprovações, teclado e a verdade incómoda do sandbox

### 50. Um pedido do motor é um frame com `id` **e** `method`
- **Sintoma:** o turno fica pendurado quando o agente pede autorização; nenhum erro visível.
- **Causa:** o cliente tratava **qualquer** frame com `id` como resposta a uma chamada sua
  (`pending.remove(id)`) — e um `session/request_permission` tem `id` **e** `method`. Era
  descartado em silêncio, e o motor ficava à espera de resposta.
- **Solução:** distinguir os três casos: `id+method` = **pedido** (responder com
  `{result:{outcome:{outcome:"selected",optionId}}}` ou `{outcome:{outcome:"cancelled"}}`);
  `id` só = resposta; sem `id` = notificação.
- **Origem:** **[LOCAL]** (implementado e testado).

### 51. Compose por baixo do teclado (e o Enter que não envia)
- **Sintoma:** com o teclado aberto, o botão *Enviar* fica inalcançável — os toques caem no
  teclado; e premir Enter não faz nada.
- **Causa:** sem tratar os insets do IME (o `adjustResize` do manifest não chega em Compose
  com edge-to-edge) e sem `KeyboardActions`.
- **Solução:** `WindowCompat.setDecorFitsSystemWindows(window, false)` + `Modifier.imePadding()`
  no `Scaffold`, e `KeyboardOptions(imeAction = ImeAction.Send)` +
  `KeyboardActions(onSend = …)` no campo.
- **Origem:** **[LOCAL]**.

### 52. ⚠ No Android as aprovações de `bash` **não disparam** (consequência de D10)
- **Observação:** pedi ao agente para executar `touch /sdcard/…` (fora do workspace). Com o
  preset `workspace-write` e `approval: ask`, **não houve pedido de autorização** — o comando
  correu e devolveu "Comando executado com sucesso".
- **Porquê:** o fluxo de aprovação do DSH é accionado pelo *escalonamento*: o comando falha
  porque o sandbox o impede (Landlock), e só então o `tool-bash` pede autorização para um modo
  mais largo. **No Android não há sandbox a falhar**, logo nunca há escalonamento nem pedido.
- **Consequência prática:** enquanto o motor correr como root, "workspace-write" é uma
  declaração sem força — a UI de aprovação existe (item 50) e serve para os casos em que o
  motor pede, mas **não é a barreira** para escrita fora do workspace.
- **O que fazer (F3):** a barreira tem de ser nossa — impor allowlist de caminhos e auditoria
  na ponte `host.*`, ou (no flavor sem privilégios) deixar o sandbox do Android ser o limite.
- **Origem:** **[LOCAL]** (teste ponta-a-ponta com o agente real).

---

## P. F3: capacidades Android, canal reverso e sobrevivência

### 53. Um plugin posto à mão na árvore **não é resolvido por nome** pelo loader
- **Sintoma:** `tool-cachalote (@deepseek-ai/dsh-tool-cachalote): failed to import` — sem mais
  detalhe (o loader só diz isto quando `entry.fiber === undefined`, ou seja quando o *import*
  falhou).
- **Diagnóstico:** `import("@deepseek-ai/dsh-tool-cachalote")` a partir de
  `.../kernel/dsh/` funciona, e `import.meta.resolve` resolve para o ficheiro certo. Mas o
  loader importa com o `bareModuleBaseUrl` do **perfil** (não da árvore do kernel), e a nossa
  pasta não está nesse caminho de resolução.
- **Solução:** no `cordis.patch.yml`, referir o plugin por **caminho absoluto** —
  `name: '/data/adb/cachalote/kernel/dsh/node_modules/@deepseek-ai/dsh-tool-cachalote/lib/index.js'`.
  O loader trata `isAbsolute(name)` com `pathToFileURL`, e as dependências do plugin resolvem
  a partir da localização do ficheiro.
- **Bónus:** as `peerDependencies` têm de casar com o que está instalado; copiar as de um
  plugin shipped resolve o problema (`"@deepseek-ai/dsh-tools": "0.2.0-rc.2"`,
  `"@deepseek-ai/cordis": "~4.0.4"`).
- **Origem:** **[LOCAL]**.

### 54. Canal reverso: o motor pede, a app executa
- **Desenho:** ferramenta (plugin no kernel) → daemon (`cachalote.host.call`, cliente TCP com
  papel `tool`) → daemon → app (`host.call`, pedido com id **string** `host-N`) → app executa
  → responde → daemon → plugin. Ver §D11 do plano.
- **Porque ids string:** os pedidos que o daemon faz à app não podem colidir com os ids
  numéricos que a app e o servidor ACP usam nas suas próprias chamadas. `host-1`, `host-2`…
  são inconfundíveis.
- **Porque papel no auth:** a app e o plugin ligam-se ao **mesmo** porto TCP com o mesmo
  token; só o cliente que se identifica como `app` recebe os pedidos `host.call`.
- **Resultado verificado:** o agente correu `android_status`, lançou a calculadora por bash,
  leu 36 elementos do ecrã, tocou no botão `7` e confirmou `formula = "7"`.
- **Origem:** **[LOCAL]**.

### 55. Ligar o serviço de acessibilidade: `settings put` e a reinstalação
- **Observação:** `settings put secure enabled_accessibility_services <pkg>/<pkg>.<Serviço>` +
  `accessibility_enabled 1` funciona — mas **uma reinstalação do APK desliga o serviço** (o
  Android revalida os serviços de acessibilidade no update). O caminho do utilizador é
  Definições → Acessibilidade; a app deve guiar (botão que abre esse ecrã) e mostrar o estado.
- **Verificação:** `dumpsys accessibility` → `Bound services:{Service[label=Cachalote, …]}`.
- **Origem:** **[LOCAL]**.

### 56. Manter o agente vivo: `specialUse`, não `dataSync`
- **Problema:** a meio de um turno longo, com o ecrã apagado, o Android mata o processo — e o
  agente perde as "mãos" (a ligação ao daemon e as capacidades) a meio da tarefa.
- **Solução:** serviço em primeiro plano com `foregroundServiceType="specialUse"`
  (+ `FOREGROUND_SERVICE_SPECIAL_USE`). O `dataSync` tem limite de ~6 h/24 h no Android 15+ e
  não descreve o caso; `specialUse` é o tipo documentado para "manter uma ligação de agente".
- **Verificado:** 95 s com o ecrã bloqueado → `appConnected: true`, `isForeground=true
  foregroundId=42 types=0x40000000`, acessibilidade ainda `Bound`.
- **Nota:** o serviço de acessibilidade, por si só, já ajuda a manter o processo vivo, mas não
  é garantido — o FGS é explícito.
- **Origem:** **[LOCAL]**.

### 57. `ACTION_IME_ENTER` é uma `AccessibilityAction`
- **Sintoma:** `unresolved reference 'ACTION_IME_ENTER'` no Kotlin com compileSdk 35.
- **Solução:** `AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id`.
- **Origem:** **[LOCAL]**.

### 58. O perfil `web` do DSH exige o URL com token
- **Sintoma:** o WebView mostra `dsh web authentication required; reopen the URL printed by
  dsh web.`
- **Causa:** o `dsh web` gera um token por arranque e imprime
  `http://127.0.0.1:<porta>/?token=<...>`; sem esse parâmetro a SPA recusa servir.
- **Solução:** o daemon captura o URL do log (`grep -aoE 'http://127\.0\.0\.1:[0-9]+/\?token=…'`),
  guarda-o em `home/web.url` (0600) e expõe-no por `cachalote web-url`; a app busca-o por `su`
  e carrega *esse* URL no WebView. Não contornar: o token é a autenticação do motor web.
- **Origem:** **[LOCAL]**.

---

## Q. Fecho do plano: guarda do bash e runtime embutido

### 59. O waterfall `tools/pre-execute` **não** chega a listeners registados na raiz
- **Sintoma:** um plugin registado (`ctx.on("tools/pre-execute", …)`) nunca é chamado; o
  comando corre sem pedir autorização e nenhum log aparece — o plugin carrega sem avisos.
- **Causa:** o kernel despacha o waterfall num **escopo derivado do agente**
  (`ctx.waterfall(scopeTarget(this, exec.agent), "tools/pre-execute", …)`); listeners
  registados no contexto raiz não recebem esse evento.
- **Solução:** não usar o seam para enforcement global — fazer a guarda **dentro da própria
  ferramenta** (patch a `dsh-tool-bash`), onde o `ctx` e o `exec` estão à mão. Fica no
  pipeline de patches ([`tools/patch-android.cjs`](../tools/patch-android.cjs), patch 7).
- **Origem:** **[LOCAL]** (tentado e verificado que não dispara).

### 60. A guarda de caminhos do `bash` (a barreira que substitui o sandbox)
- **Problema:** sem Landlock no Android, o `bash` corre como root e as aprovações do DSH
  nunca eram pedidas (nascem do escalonamento, que só existe quando o sandbox falha).
- **Solução:** antes de executar, extrair do comando os caminhos absolutos que ficam **fora**
  da área permitida (`CACHALOTE_ALLOWED_ROOTS` + `cwd` da sessão + `$DSH_HOME`; caminhos de
  sistema são só leitura) e pedir autorização pelo serviço `approval`. O perfil ACP transforma
  esse pedido num `session/request_permission` e a **app mostra o diálogo no telefone**.
- **Recusa:** o comando recebe um erro claro — `cachalote guard: o utilizador não autorizou
  (rejected)` — e o agente não tenta contornar (verificado: respondeu explicitamente que não
  ia procurar outra via).
- **Verificação A/B:** antes de aprovar, `ls /sdcard/cachalote-guard.txt` → *No such file*;
  depois de "Allow once" → criado. Com "Reject" → não criado.
- **Origem:** **[LOCAL]**.

### 61. Runtime Node embutido na APK como `libnode.so` (flavor sem root)
- **Problema:** com `targetSdk ≥ 29` o directório privado da app é noexec — não se pode
  executar o node a partir de `files/`.
- **Solução verificada:** o `nativeLibraryDir` **é** executável: renomear o node para
  `libnode.so` e pô-lo em `jniLibs` (`extractNativeLibs=true` +
  `packaging.jniLibs.useLegacyPackaging=true`) e correr esse caminho directamente.
  Resultado no dispositivo: `libnode.so executou: v24.18.0 [exit 0]`.
- **Segunda armadilha:** a AGP **só empacota `lib*.so`** — os sonames versionados
  (`libz.so.1`, `libcrypto.so.3`, `libicu*.so.78`) eram descartados em silêncio e o linker
  falhava com `library "libz.so.1" not found`. Solução: embarcá-los com alias `.so` e a app
  cria uma **farm de symlinks** (`soname → alias`) no seu `filesDir`; o `dlopen` segue o link
  e o alvo está no `nativeLibraryDir` (executável). Manifesto gerado por
  [`tools/prepare-embed.sh`](../tools/prepare-embed.sh).
- **Tamanho:** 89 MB (só o fecho de `DT_NEEDED`; copiar todos os nomes dava 173 MB).
- **Origem:** **[LOCAL]**.

### 62. Composables com scroll não se aninham (e rebentam a app)
- **Sintoma:** a app fecha-se ao abrir um ecrã: `IllegalStateException: Vertically scrollable
  component was measured with an infinity maximum height constraints`.
- **Causa:** envolvi um ecrã que já tinha `Column(verticalScroll)` dentro de outro
  `Column(verticalScroll)` para pôr um cartão no topo.
- **Solução:** o cabeçalho entra **dentro** do scroll existente (primeiro filho), ou como
  `item {}` se o contentor for um `LazyColumn`.
- **Origem:** **[LOCAL]**.

### 63. `force-stop` **desliga** o serviço de acessibilidade
- **Observação:** depois de `adb shell am force-stop <pkg>` (ou de uma reinstalação da APK),
  `settings get secure enabled_accessibility_services` fica `null`/vazio e
  `dumpsys accessibility` mostra `Bound services:{}`. O Android revalida/limpa os serviços de
  acessibilidade quando a app é actualizada ou forçada a parar.
- **Consequência para o produto:** o utilizador (e os nossos testes) tem de **religar** o
  serviço; a app não pode assumir que continua ligado. O cartão de primeiros passos detecta e
  oferece o botão "Abrir Acessibilidade" — foi assim que se descobriu, não por acidente.
- **Nos testes:** `tools/app-capabilities-test.sh` religa-o automaticamente quando falta.
- **Origem:** **[LOCAL]**.

### 64. Estado lido de um singleton não pode ficar preso num `remember` local
- **Sintoma:** o cartão de primeiros passos mostrava "acessibilidade ligada" como pendente
  enquanto uma linha de diagnóstico, na **mesma** composição, imprimia `a11y true`.
- **Causa:** o valor vinha de `remember { mutableStateOf(Servico.isEnabled()) }` inicializado
  quando o serviço ainda não estava ligado; o `LaunchedEffect` local que o devia refrescar não
  corria como esperado.
- **Solução:** o estado vive no **ViewModel** (`accessibilityOn`) e é refrescado por um laço
  do `viewModelScope` — independente do ciclo de composição. A UI só lê.
- **Origem:** **[LOCAL]**.

### 65. Depois de um reboot, o serviço de acessibilidade pode demorar a ligar ao processo novo
- **Observação:** logo após reiniciar, `dumpsys accessibility` já mostra o serviço *bound* e a
  definição intacta, mas a app (processo arrancado depois) ainda reportava
  `accessibility: false`; minutos depois, e sem intervenção, passou a `true`.
- **Causa:** o sistema religa o serviço ao processo do app de forma assíncrona; entre o
  arranque do processo e o `onServiceConnected` há uma janela.
- **Solução:** o estado da acessibilidade é lido por um laço do ViewModel (2 s) e a UI mostra
  o item como pendente com o botão "Abrir Acessibilidade" enquanto não estiver ligado — não se
  assume nada no arranque.
- **Verificado no reboot real:** daemon arrancou sozinho (pid baixo, `handshake OK`), chave
  intacta (53 bytes, 0600) e a acessibilidade voltou a ligar sozinha.
- **Origem:** **[LOCAL]**.

### 66. A flag do singleton do serviço pode discordar do que o serviço faz
- **Sintoma:** a UI mostrava "acessibilidade ligada" como pendente (`isEnabled()` → `false`)
  **enquanto** um `android_screen` real devolvia elementos — no mesmo processo, com o mesmo
  `dumpsys accessibility` a dizer `Bound services:{Service[label=Cachalote…]}`.
- **Diagnóstico:** instrumentei com PID e contador; confirmou-se o mesmo processo (ex.: 25789)
  a responder `accessibility: true` pelo canal e `false` na composição, com o contador do laço
  congelado — a composição não estava a acompanhar as actualizações de estado.
- **Solução:** ler o estado pela **mesma via que o agente usa** (`HostCapabilities.status()`,
  que é o que o `android_status` devolve) e fazê-lo no `refreshStatus` (arranque e botão
  *Atualizar*/*Verificar*), em vez de depender de um laço de estado dentro da composição.
- **Lição:** para "o agente consegue mesmo fazer X?", a resposta tem de vir do caminho do
  agente — não de uma flag paralela que pode divergir.
- **Origem:** **[LOCAL]**.

### 67. Nunca testar a chave "à mão" — usar o caminho guardado do teste
- **O que aconteceu:** para diagnosticar o passo da chave, escrevi uma chave de teste
  directamente na UI. O teste automático tem backup/restauro (`trap EXIT`), mas o meu passo
  manual **substituiu a chave real do utilizador** e não havia cópia no dispositivo (o daemon
  só regista o *comprimento*, e o registo de auditoria só guarda capacidades `host.*`, nunca
  `credentials.set`).
- **Regras que ficam:**
  1. Qualquer experiência com chaves passa pelo `tools/app-ui-test.sh` (que guarda
     `home/.env.bak`, repõe no fim e reinicia o motor) ou pelo `daemon/set-key.sh`.
  2. O teste **confirma que está no ecrã da chave** antes de escrever; se não chegar lá,
     aborta sem tocar em nada (era isto que faltava).
  3. O `hide_kb` só manda BACK quando o teclado está aberto — sem teclado, o BACK **fecha a
     app** e o resto do teste escreve no vazio.
- **Boa notícia de segurança:** o valor da chave **nunca** entrou no registo de auditoria
  (só capacidades `host.*` são registadas) nem no log do daemon (só o comprimento).
- **Origem:** **[LOCAL]**.

---

## R. Modo sem root (motor embutido na app)

### 68. Extrair o zip do kernel para o sítio certo
- **Sintoma:** `falta kernel.zip` / `kernel não extraído` depois de extrair com sucesso.
- **Causa:** o zip tem a raiz `dsh/`; extrair para `filesDir` deixa a árvore em `files/dsh/`,
  mas o código procura `files/kernel/dsh/...`.
- **Solução:** extrair sempre para `files/kernel/` (e o marcador `.extracted` lá dentro).
  Mesma classe de erro do tar do daemon (ver itens 36 e F1).
- **Origem:** **[LOCAL]**.

### 69. A farm de symlinks morre quando o caminho da instalação muda
- **Sintoma:** `CANNOT LINK EXECUTABLE ".../libnode.so": library "libz.so.1" not found` depois de
  reinstalar a APK (o caminho `/data/app/~~<hash>/...` muda).
- **Causa:** `File.exists()` **segue** o symlink; para um link morto devolve `false`, portanto o
  código antigo não o apagava e a criação falhava com "já existe".
- **Solução:** guardar o `nativeLibraryDir` actual num marcador dentro da farm e, se mudou,
  apagar a farm inteira; e usar `Files.deleteIfExists` (que remove o link mesmo morto) antes de
  recriar.
- **Origem:** **[LOCAL]**.

### 70. `getLoopbackAddress()` pode dar `::1` — e o cliente liga a `127.0.0.1`
- **Sintoma:** `connect ECONNREFUSED 127.0.0.1:39732` com o servidor "a correr".
- **Causa:** o `ServerSocket` ligou-se ao loopback IPv6 (`::1`) e o cliente usa IPv4.
- **Solução:** `InetAddress.getByName("127.0.0.1")` explícito. (O daemon usa
  `listen(PORT, "127.0.0.1")` e nunca teve este problema.)
- **Origem:** **[LOCAL]**.

### 71. O `workspace` da sessão tem de depender do modo
- **Sintoma:** a sessão embutida é criada mas o turno não avança (ficheiro de sessão só com o
  cabeçalho).
- **Causa:** o `session/new` enviava o `cwd` do daemon (`/data/adb/cachalote/home/ws`), que é
  root-only — o motor embutido corre com o uid da app e não lá chega.
- **Solução:** o `workspace` é derivado do modo (`files/dsh-home/ws` no embutido). O caminho
  aparece no slug da sessão (`--data-user-0-...-files-dsh-home-ws--`), o que torna o erro visível.
- **Origem:** **[LOCAL]**.

### 72. Arrancar o motor embutido tem de ser idempotente e sincronizado
- **Sintoma:** dois processos `libnode.so` a competir pelo mesmo DSH_HOME (bloqueios e sessões
  estranhas).
- **Causa:** `start()` era chamado por dois caminhos concorrentes (arranque da app + laço de
  reconexão) sem protecção.
- **Solução:** guardar os streams actuais e devolvê-los se o processo estiver vivo, com
  `synchronized` no arranque.
- **Origem:** **[LOCAL]**.

### 73. A camada `cordis.patch.yml` do DSH_HOME embutido é gerada pela app
- **Sintoma:** no modo embutido o motor arranca mas as nossas ferramentas não existem e o OTel
  falha no boot.
- **Causa:** o `$DSH_HOME` embutido não tinha a camada de patch do perfil (o equivalente ao
  `config/cachalote.patch.yml` do daemon) — e o caminho do plugin não pode ser o do daemon.
- **Solução:** a app escreve `files/dsh-home/cordis.patch.yml` no arranque do motor, com o
  caminho **absoluto** do plugin dentro do armazenamento privado (o loader não resolve plugins
  postos à mão por nome — item 53).
- **Origem:** **[LOCAL]**.

### 74. Áreas permitidas do bash: onde a configuração vive e por onde passa
- **Desenho:** a app grava `$BASE/home/allowed-roots` (uma pasta por linha, via `su` + base64
  para não haver problemas de aspas); a **ponte do daemon** lê esse ficheiro em cada arranque do
  motor e passa-o como `CACHALOTE_ALLOWED_ROOTS` (separado por `:`) no ambiente do motor; o
  patch ao `dsh-bash-local` (guarda de caminhos) usa-o para decidir se pede autorização.
- **No modo embutido** o caminho é o mesmo conceito mas sem daemon: a app passa a variável
  directamente ao processo filho.
- **Verificado:** com `/sdcard` autorizado, `touch /sdcard/...` corre sem diálogo; sem
  autorização, o mesmo comando pede aprovação no telefone e fica bloqueado até à decisão.
- **Default seguro:** sem o ficheiro, a área permitida é só o workspace da sessão + `$DSH_HOME`
  (o teste deixou-o assim).
- **Origem:** **[LOCAL]**.

---

## S. Modo Shizuku (daemon como shell, sem root)

### 75. `/data/local/tmp` nega o socket AF_UNIX ao domínio `shell`
- **Sintoma:** `ERRO no servidor unix: listen EACCES: permission denied /data/local/tmp/cachalote/run/cachalote.sock`
  e o daemon a morrer (o handler fazia `process.exit(1)`).
- **Causa:** `/data/local/tmp` é `shell_data_file`; criar um socket unix lá não é permitido ao
  domínio que o cria. Por isso é que a app fala **TCP** (foi D11) — o socket unix é só um extra
  para o CLI/adb.
- **Solução:** o socket unix passou a ser **opcional** (aviso no log, daemon continua só com TCP).
- **Segunda metade (só apareceu no emulador, §21 do STATUS):** tornar o socket opcional **não
  chegava**. O arranque do motor estava *dentro* do callback de sucesso do `listen` do unix —
  logo, quando o socket falhava, o daemon ficava de pé, anunciava "sigo só com TCP" e **nunca
  arrancava o motor**. O `cachalote status` dizia `daemon: a correr` e `motor: parado`, sem erro
  nenhum. O arranque passou a ser uma função idempotente (`bootEngine()`) chamada em qualquer dos
  caminhos: sucesso do unix, falha do unix, ou `listen` do TCP. Lição: ao tornar um passo
  opcional, verificar **o que estava pendurado nele**.
- **Bónus:** dois daemons no mesmo telefone precisam de portas diferentes — o porto passou a ser
  escrito em `$BASE/home/port` pelo `install.sh` (root 39731, `/data/local/tmp` 39733).
- **Origem:** **[LOCAL]**.

### 76. Shizuku: `newProcess` é API oculta; o *user service* exige o provider e chave de versão
- **Sintoma 1:** nada funciona e o Shizuku regista
  `E Service: provider is null <package>.shizuku` → o `ShizukuProvider` **não estava no
  manifest** (o meu patch falhou em silêncio). Sem provider, `Shizuku.pingBinder()` é sempre false.
- **Sintoma 2:** com provider, `bindUserService` nunca ligava e o servidor registava
  `UserServiceRecord: Service record … is not started in 30000 ms` — o registo do serviço estava
  em cache a apontar para o caminho da APK anterior (reinstalações). A chave do registo é o
  `version(...)`: passar o `versionCode` da app invalida-o.
- **Decisão:** o `newProcess` (que os exemplos usam) existe no artefacto mas é **`private`**;
  chama-se por **reflexão** (`setAccessible(true)`). É bem mais simples do que o user service e
  foi o que ficou. Se um dia a assinatura mudar, o user service é a alternativa.
- **Origem:** **[LOCAL]**.

### 77. `install.sh --base` tem de reescrever **todos** os caminhos gravados
- **Sintoma:** daemon instalado em `/data/local/tmp` mas a arrancar o de `/data/adb`; e o motor
  sem as nossas ferramentas (`failed to import`), porque o `cordis.patch.yml` apontava o plugin
  para a árvore do install root.
- **Causa:** `bin/cachalote`, `bridge.cjs`, `home/cordis.patch.yml` e `bin/client-smoke.cjs` têm
  o base/path gravado dentro do ficheiro.
- **Solução:** o `install.sh` faz `sed` do base antigo para o novo em todos eles (com as aspas
  escapadas dentro do bloco `su -c`).
- **Origem:** **[LOCAL]**.

### 78. O plugin precisa de saber **qual** daemon
- **Sintoma:** capacidades falham com `token inválido` no modo Shizuku.
- **Causa:** o plugin do kernel usa `CACHALOTE_PORT` (por omissão 39731) — ou seja, falava com o
  daemon **root** enquanto corria no daemon do Shizuku (39733).
- **Solução:** a ponte passa `CACHALOTE_PORT` (o seu) e `CACHALOTE_TOKEN_FILE` ao motor.
- **Origem:** **[LOCAL]**.

### 79. O handshake ACP tem de tolerar o motor a reiniciar
- **Sintoma:** ao mudar de motor, a app falhava com `cachalote: o motor não está a correr` — e um
  segundo depois o mesmo pedido funcionava.
- **Causa:** o daemon responde isso enquanto está a (re)arrancar o motor (acontece ao gravar a
  chave, ao reiniciar e ao arrancar).
- **Solução:** `ensureAcp()` tenta 4 vezes com 2 s de intervalo (e reconecta se a ligação caiu).
- **Origem:** **[LOCAL]**.

---

## T. Visão: a captura de ecrã chega ao modelo

### 80. `sharp` não existe para bionic — e o `link()`→`rename()` deixou um `unlink` órfão
- **Sintoma:** `android_see` corria mas o modelo nunca via nada; o motor respondia
  `Internal error: unable to persist the prompt image batch` e o `attachments/` ficava vazio.
- **Duas causas, ambas no `dsh-attachment-local`:**
  1. **`sharp`** (libvips) é obrigatório para ler metadados e normalizar imagens. Não há build
     para **bionic** (o prebuild linux-arm64 é glibc; o musl também não serve) — daí o porto
     original o ter cortado. Solução (patch 9): ler os factos em **JS puro** (PNG: IHDR +
     varrimento de chunks; JPEG: marcadores até ao SOF) e deixar o harness usar o caminho de
     **passagem directa** que já existe para PNG 8-bit sRGB sem metadados e dentro dos limites.
     O que precise mesmo de re-encodar falha com mensagem clara (não há libvips no Android).
  2. **O nosso próprio patch** `link()`→`rename()` (item 41) deixou um `await unlink(staged.path)`
     a seguir: com hard link o nome antigo continuava a existir, com `rename` já não — o
     `unlink` rebentava com ENOENT e a publicação falhava. Correcção: `unlink(...).catch(() => {})`.
- **Modelo:** o perfil fixa `deepseek-v4-flash`, que **não** está no catálogo por omissão — um
  modelo fora do catálogo é tratado como só-texto e o `initialize` anunciava
  `promptCapabilities.image: false` (o agente respondia *"I can't read images"*). Declarar o
  modelo com `inputModalities: ["text","image"]` na nossa camada de patch resolve.
- **Verificação (só a imagem, sem texto a descrevê-la):** captura da calculadora com **512** no
  visor → `session/prompt` com bloco `image` → o motor registou
  `agent_thought_chunk: 'The image shows "512"'` e respondeu **512**; objecto guardado em
  `$DSH_HOME/attachments/v1/objects/61/…` (88 KB, `sha256` no nome).
- **Origem:** **[LOCAL]**.

---

## U. Reprodutibilidade (bootstrap)

### 81. De um clone a funcionar: o que faltava
- **Kernel:** vem do **npm** (`npm install @deepseek-ai/dsh@<versão>`), não do `app.asar` do
  desktop — é o que permite a alguém sem o DSH instalado no computador construir isto. O
  `extract-kernel.cjs` ganhou o modo `--node-modules <dir>` (mesma travessia + trim do modo
  `--asar`).
- **Runtime:** os binários oficiais de Node para Linux são **glibc**; o Android é **bionic**.
  O `tools/fetch-runtime.sh` monta a árvore a partir dos `.deb` do Termux, procurando os nomes
  dos ficheiros no índice do repositório (não fica preso a versões) e extraindo com um leitor de
  `ar`+`tar` em Python puro. Verificação: o `node` obtido tem o **mesmo sha256** do que está a
  correr no telemóvel.
- **Cache do npm:** usar `--cache .work/npm-cache`. O cache global do utilizador pode ter
  ficheiros de root (de execuções antigas com `sudo`) e o npm aborta — e não é por isso que um
  bootstrap deve falhar na máquina de outra pessoa.
- **`awk`/`pipefail`:** o `awk ... exit` fecha o pipe antes do fim e o `pipefail` mata o script
  (exit 141, sem mensagem). Passar o índice por **ficheiro**, não por pipe.
- **Origem:** **[LOCAL]**.

---

## V. Kernel DSH: schema das ferramentas e plugins que não activam

### 82. Um `{}` sem `type` num schema de ferramenta **desliga o plugin inteiro**
- **Sintoma:** o agente fica **sem nenhuma** ferramenta do plugin (`android_status`,
  `android_tap`, …) e o único sinal é, no arranque do motor:
  `dsh: warning: 1 entry did not activate | tool-cachalote (…): JsonSchemaError: unsupported
  JSON schema: schema.properties.attachmentRef.type must be string/number/integer/boolean/null/
  array/object/json, or use oneOf`. Nada falha ruidosamente: a app, o daemon e o handshake ACP
  ficam todos bons.
- **Causa:** o `defineTool` valida o schema **no registo** e lança. Como o `apply` do plugin
  registra todas as ferramentas no mesmo `ctx`, a excepção na primeira derruba **todas** — não
  é só a ferramenta com o schema errado.
- **Conjunto de schema do kernel:** cada nó tem de declarar `type` (um nó só-anotação é
  `type: "json"`). Atenção ao caso simétrico: `type: "object"` **obriga** a declarar
  `additionalProperties: true|false` explicitamente, senão também é recusado. Para uma
  referência opaca (ex.: o objecto devolvido por `attachments.saveImage`), `type: "json"` é o
  correcto.
- **Prevenção:** não vale confiar nos testes de UI — eles passam sem as ferramentas. Correr
  `tools/plugin-schema-test.sh`, que carrega o plugin contra o `dsh-tools` real e exige as 7
  ferramentas; e olhar para o log de arranque à procura de `did not activate`.
- **Origem:** **[LOCAL]** (regressão de §18, corrigida e verificada em §21 do STATUS).

### 83. `android_see`: a captura apanha a transição de janela (e o visor é alinhado à direita)
- **Sintoma:** a imagem chega ao modelo, mas o modelo "não vê" o número; o raciocínio dele
  descreve os botões e "uma área escura com um traço". A captura foi tirada a meio da animação
  app→outra app: a janela que entra está parcialmente fora do ecrã e o que sai ainda ocupa uma
  faixa. O visor da calculadora é alinhado à **direita**, por isso é a primeira coisa a ser
  cortada.
- **Causa:** dois `adb shell` separados (tocar em "Enviar" + trazer a outra app para a frente)
  somam dois round-trips adb; a transição começa depois de o modelo já estar a decidir.
- **Correção:** o toque e o `am start` da app que se quer capturar vão na **mesma** invocação
  de shell (`input tap X Y; am start -n pkg/.Act`), e a captura apanha o ecrã já assente.
- **Origem:** **[LOCAL]** (STATUS §21).

### 84. O daemon tem **um** lugar de cliente ACP — a app retoma-o sempre
- **Sintoma:** um cliente ACP externo (um script, o `client-smoke`) liga-se, cria sessão e
  envia o prompt, mas deixa de receber `session/update` a meio do turno; e as ferramentas que
  passam pela app falham com `"a app não está ligada ao daemon"`.
- **Causa:** o daemon aceita **um** cliente ACP. Ao ligar-se outro, o daemon desliga a app
  (`"app desligada do daemon"`); a app volta a ligar-se e a retomar o lugar ~1 s depois
  (`"a substituir o cliente ACP anterior"`). Nesse intervalo a app está também sem o canal
  `host.*`, e o cliente externo deixa de ver o turno.
- **Consequência prática:** conduzir o agente por um cliente externo e usar as capacidades
  Android ao mesmo tempo é uma corrida. Para testar, **conduzir pela app** e verificar no
  registo da sessão (`$DSH_HOME/sessions/…/session.v4.jsonl.zstd`), que é autoritativo e não
  depende de quem está ligado.
- **Nota:** acrescentar uma espera antes de enviar o prompt **não** resolve — quando o turno
  começa, a app já retomou o lugar e o cliente externo já não recebe respostas.
- **Origem:** **[LOCAL]** (STATUS §21).

### 85. A app guarda o separador e **retoma a mesma sessão** entre corridas
- **Sintoma (testes de UI):** um passo falha "de vez em quando" a encontrar um botão que existe
  noutro separador; e o registo de uma sessão tem turnos de corridas anteriores misturados com
  os de agora (ferramentas e erros que não são desta corrida).
- **Causa:** o índice do separador é `rememberSaveable` e sobrevive à recriação da actividade;
  e a app faz `session/resume`, reaproveitando o mesmo registo de sessão.
- **Correção (testes):** seleccionar o separador antes de procurar o botão do separador; e, ao
  analisar o registo, olhar **só para o último turno** (do último `turn/start` em diante).
- **Origem:** **[LOCAL]** (STATUS §21).

### 86. `input keyevent 4` (BACK) para esconder o teclado pode **fechar a app**
- **Sintoma:** a app aparece "desligada", a ligação ao daemon entra em ciclo de reconexão, e um
  pedido que devia ser trivial falha com `"a ligação ao motor caiu"`; nas capturas de ecrã vê-se
  o cartão de erro da app, e não um toque perdido.
- **Causa:** o teste manda BACK quando o `dumpsys input_method` diz `mInputShown=true`. Esse
  estado pode estar desactualizado; sem teclado, o BACK **fecha a actividade**.
- **Correção:** mandar BACK só quando há mesmo teclado **e** confirmar a seguir que a app
  continua em primeiro plano (trazendo-a de volta se não estiver). Em passos onde não se escreve
  nada, simplesmente não chamar o `hide_kb`.
- **Origem:** **[LOCAL]** (STATUS §21).

### 87. Emulador x86_64: o motor precisa de um runtime **x86_64** (o do telefone é arm64)
- **Sintoma:** monta-se tudo num AVD x86_64 e o motor não arranca. Se for o `libnode.so` do modo
  embutido, a app instala-se e falha ao carregar a biblioteca; se for o daemon, o `node` morre
  com "cannot execute binary file".
- **Causa:** o runtime do Termux que o telefone usa é **arm64**; o AVD corre x86_64. Instalar um
  binário doutra arquitectura não dá erro na instalação — só na execução, que é tarde.
- **Solução:** `tools/fetch-runtime.sh --arch x86_64 --out .work/node-android-x86_64` monta a
  árvore certa (o Termux publica as mesmas versões: o Node x86_64 é o **24.18.0**, igual ao
  arm64), e `tools/prepare-embed.sh --arch x86_64` põe o ABI certo em `jniLibs` (`x86_64`;
  `aarch64`→`arm64-v8a`). O `tools/bootstrap.sh --arch x86_64` passa as duas.
- **Receita completa para o AVD (sem root):**
  `./tools/bootstrap.sh --device emulator-5554 --arch x86_64 --shizuku --skip-app`
- **Notas do AVD:** a imagem `google_apis_playstore` **não** deixa `adb root` — mas o daemon não
  precisa de root: o caminho `--shizuku` (`--base /data/local/tmp/cachalote --no-root`) instala e
  arranca como `uid=2000(shell)`. E `/dev/kvm` pode estar fora do sandbox de ficheiros: sem ele
  o emulador arranca sem aceleração (ou não arranca).
- **Prova:** no `emulator-5554` (Android 16, x86_64, sem root) o daemon instalou-se a partir do
  kernel do **npm** e o handshake respondeu
  `initialize OK: deepseek-harness-acp 0.0.1 | imagem=true`. O `client-smoke.cjs` não serve aí
  (fala pelo socket AF_UNIX, que o `shell` não pode criar): usar `tools/tcp-acp-probe.cjs`.
- **Origem:** **[LOCAL]** (STATUS §21).

### 88. O motor **embutido** não grava anexos: o `fsync` dos antepassados até `/` é privilégio de root
- **Sintoma:** uma imagem enviada no prompt (ou uma captura do `android_see`) falha com
  `Internal error: unable to persist the prompt image batch` — a **mesma** mensagem de quando
  faltava o `sharp` (armadilha 80), o que manda para o sítio errado. No modo daemon (root)
  tudo funciona; no modo embutido (uid da app) nunca grava nada.
- **Causa:** o `dsh-attachment-local` prova a durabilidade do caminho fazendo `fsync` de
  **todos** os directórios antepassados, de `$DSH_HOME` até à **raiz do sistema**
  (`ensureDurableHome` → `ensureDurableDirectory(home, parse(home).root)`). O `syncDirectory`
  abre cada um com `O_RDONLY` e faz `fsync`. Como root, abrir `/data` é trivial. Como o uid da
  app, `/data` é `771 root:system` — nem se abre: `EACCES` logo no primeiro antepassado que
  não é nosso, e a excepção sobe como "não consegui persistir".
- **Correcção:** **patch 10** ao `dsh-attachment-local` — `syncDirectory` tolera
  `EACCES`/`EPERM`/`ENOENT` e segue. Acima da área da app os directórios não são nossos; não os
  poder abrir não pode impedir a gravação (a durabilidade que interessa é a de dentro da nossa
  árvore, essa continua a ser sincronizada).
- **Como se confirmou:** o turno deixou de falhar na persistência e passou a falhar só no
  modelo (`no API key`), que é o esperado num emulador sem chave; e o objecto apareceu em
  `files/dsh-home/attachments/v1/objects/6c/6c03b438…` com o **mesmo sha256** do ficheiro de
  origem.
- **Origem:** **[LOCAL]** (STATUS §23).

---

## V. Distribuição do kernel

### 89. Kernel por download: a APK leve e como testar isso sem hospedagem
- **Problema:** a árvore do kernel vinda do **npm** dá um zip de ~211–389 MB; embutida na APK
  põe-na em ~440 MB. Não serve para distribuir.
- **Solução:** a app traz só `assets/kernel.remote.json` (`url` + `sha256` + `bytes`) e
  **descarrega** o kernel na primeira utilização do modo embutido, com verificação de
  integridade antes de extrair. Ordem: ficheiro local → asset (desenvolvimento) → download.
  `tools/prepare-kernel-zip.sh --remote-url URL` escreve os metadados;
  `tools/publish-kernel.sh --repo owner/repo` publica o zip como *release asset* no GitHub.
- **Efeito medido:** a APK passou de **440 MB** para **164 MB** (o que resta é o runtime Node
  embutido, que tem de lá estar — ~89 MB — e o resto da app).
- **Testar sem hospedagem:** servir o zip no computador e dar-lho ao telemóvel por USB:
  ```sh
  (cd .work && python3 -m http.server 8080 --bind 127.0.0.1) &
  adb -s <serial> reverse tcp:8080 tcp:8080
  ./tools/prepare-kernel-zip.sh --remote-url http://127.0.0.1:8080/kernel.zip
  ```
  O URL do *build* é um valor de desenvolvimento: `kernel.remote.json` está no `.gitignore`.
  Em produção o URL tem de ser **público** — e atenção que o repositório do Cachalote é
  privado: um asset de release num repo privado não é descarregável pela app sem token.
- **Verificado:** com o asset removido da APK, a app descarregou 221 747 661 bytes do servidor
  local, o `sha256` no telemóvel ficou **igual** ao publicado (`77fa6785…`), o kernel foi
  extraído e o motor embutido arrancou (`embedded: true, engine: true, hostServer: true`).
- **Origem:** **[LOCAL]**.

---

## W. Integração contínua

### 90. O que a CI consegue provar sem telemóvel (e o que se partiu pelo caminho)
- **O que corre no runner:** kernel do npm + patches (com verificação de âncoras), regressão do
  schema do plugin, `bash -n`/`node --check` em tudo, e o **build da APK** (runtime arm64 do
  Termux + `prepare-embed.sh` + Gradle 8.13 + `assembleDebug`, com a APK como artefacto).
- **`android-actions/setup-android@v3` não serve:** pede o pacote legacy `tools` (já removido do
  repositório do SDK) e o passo falha com `Failed to find package 'tools'`. Solução: usar o SDK
  que a imagem do runner já traz e instalar só o que falta com o `sdkmanager` —
  `platform-tools platforms;android-35 build-tools;35.0.0` (a app declara `compileSdk 35`).
- **Âncoras dos patches:** são substituições de texto. Se a versão do kernel mudar, um patch
  pode passar a ser um no-op **silencioso** — por isso a CI falha quando aparece um `AVISO` no
  relatório do `patch-android.cjs`, e exige a linha da guarda de caminhos como prova positiva.
- **Resultado:** num clone limpo, em `ubuntu-latest`, os três jobs passam — incluindo a APK.
- **Origem:** **[LOCAL]**.

---

## X. Sobreviver ao low-memory killer

### 91. `oom_score_adj`, watchdog do daemon — o que faltava no risco do LMK
- **O que existia:** o daemon supervisorava o **motor** (reinício com backoff), mas ninguém
  supervisorava o **daemon**. Se o LMK (ou um crash, ou o kernel) o matasse, ficava morto: a app
  mostrava "desligado" até alguém reparar. E nenhum dos dois processos estava protegido.
- **Solução:**
  1. `oom_score_adj` do daemon em **-800** e do motor em **-500** (o mínimo é -1000; o próprio
     sistema usa estes valores para processos quase-críticos). O daemon define-o no `start`, a
     ponte define-o para o motor assim que o arranca.
  2. `cachalote watchdog [segundos]` — laço que arranca o daemon se estiver parado e volta a
     arrancá-lo se morrer. O `service.d` do Magisk corre-o **em segundo plano** no arranque
     (um `start` único, como estava, não chega).
- **Verificação (S10+):** `kill -9` no daemon (pid 13979) → o watchdog detectou em ~20 s, subiu
  um daemon novo (pid 15018, também com `-800`), a ponte respawnou o motor, o `handshake` voltou
  a `OK` e a app **reconectou sozinha** ("motor DSH no telefone · ligado") sem se mexer nela.
- **Custo honesto:** há uma janela de até `intervalo` segundos (20–30 s) sem daemon. Para turnos
  longos, o motor já não é morto pelo LMK por causa do `-500`.
- **Origem:** **[LOCAL]**.

---

## Y. Reconexão calma

### 92. Três janelas diferentes quando o motor reinicia — e só duas se podem repetir
Gravar a chave reinicia o motor. Um pedido feito nesse intervalo pode cair em três sítios
diferentes, e tratá-los como iguais era o erro:
1. **Sem ligação nenhuma** (modo embutido: o stdio morreu com o processo). Nada foi enviado ⇒
   **seguro repetir**. O cliente chama o `reconnector` (o controlador reconecta/arranca) antes de
   desistir.
2. **A ponte recusa** (modo daemon: `cachalote: o motor não está a correr`). A ponte responde
   *em vez de* encaminhar ⇒ nada foi executado ⇒ **seguro repetir**. `comMotorPronto()` repete
   até 6× com 1,5 s quando a mensagem é exactamente essa.
3. **Entregue e depois cai** (o frame foi escrito e o motor morreu a meio). **Nunca repetir**: um
   turno repetido executa as ferramentas duas vezes. Aqui a resposta certa é a mensagem clara
   (`a ligação ao motor caiu`) e o utilizador repete se quiser.
- **Verificado:** `tools/app-reconnect-test.sh` mata o motor e envia logo o prompt — o pedido é
  aceite e o motor volta (pid novo) com o daemon a responder `OK`. No modo embutido o mesmo
  cenário recupera em ~5 s (o motor volta e a app fica "ligada").
- **Limite honesto do teste:** a janela (2) não se apanha de forma fiável por automação — o
  `pkill` via `su` leva 1–2 s e o motor já voltou quando o toque chega, por isso o teste diz
  quando *não* a apanhou (não conta como sucesso do retry). Para (1) e (3) o comportamento é
  determinístico e está acima.
- **Origem:** **[LOCAL]**.

---

## Z. Sessões e modo do motor

### 93. A sessão pertence a um motor, não à app
- **Sintoma:** ao trocar de modo (embutido ↔ root) a app mostrava
  `sessões: a ligação ao motor caiu`. No log via-se `→ session/resume` seguido de erro.
- **Causa:** a sessão guardada era **global** e o `cwd` de uma sessão difere por modo
  (`/data/adb/cachalote/home/ws` no root, `files/dsh-home/ws` no embutido). Retomar no motor
  errado é recusado pelo ACP — e o erro aparecia como falha de ligação, o que despistava.
- **Solução:** estado **por modo** — `files/sessao-<modo>.json` (transcript + sessão) e
  `lastSessionId:<modo>` nas preferências. Ao trocar de modo, a app guarda o que era daquele
  motor e carrega o do novo (há uma linha de sistema a dizê-lo).
- **Verificado:** existem os dois ficheiros com os `cwd` certos e o do embutido ficou limpo
  (nenhuma sessão), sem tentativas de retomar sessões alheias.
- **`session/close` só fecha a sessão activa.** Verificado contra o motor: criar + fechar
  devolve `{}`; fechar uma sessão do histórico devolve `unknown session`. Por isso o ✕ no
  diálogo das sessões aparece **só** na linha da sessão activa, e a app explica em vez de
  mostrar o erro cru (`o motor só fecha a sessão activa — toca na linha para a retomares`).
- **Limite do teste:** a verificação do ✕ *pela UI* não ficou feita — a automação não acertou
  nas linhas do diálogo (a sessão activa não estava entre as primeiras) e o clique em "Sessões"
  chegou com a app a reconectar. O que está provado é o protocolo e o estado por modo.
- **Origem:** **[LOCAL]**.

---

## AA. Arranque do Shizuku

### 94. O Shizuku no boot depende de como o servidor é iniciado
- **Verificado no S10+:** a opção do Shizuku **"Iniciar na inicialização (root)"** já estava
  ligada (Configurações → primeiro interruptor). Com root, o servidor volta sozinho depois de
  reiniciar — logo o modo Shizuku do Cachalote fica utilizável sem abrir a app do Shizuku.
- **Sem root não há como:** o servidor do Shizuku só sobe por ADB ou depuração sem fio (ou por
  um app de terminal), e isso tem de ser feito depois de cada arranque. Não é limitação nossa.
- **O que a app faz quando ele não está a correr:** diz exactamente isso no cartão do motor
  (*"o Shizuku não está a correr (abre a app Shizuku e inicia-o)"*) e oferece o botão
  **Autorizar no Shizuku**. Não falha em silêncio nem finge que ligou.
- **Origem:** **[LOCAL]**.

---

## AB. Preparar um repositório para ser público

### 95. Nada de identificadores da máquina do autor no repositório
- **O que estava mal:** nove scripts usavam `DEV="${CACHALOTE_DEV:-<serial-do-telemóvel>}"` — o
  identificador do aparelho do autor ficava gravado (e não servia a mais ninguém), e havia
  caminhos absolutos da máquina em dois documentos.
- **Solução:** [`tools/lib-device.sh`](../tools/lib-device.sh) resolve o dispositivo por
  `$CACHALOTE_DEV` → único ligado → erro explícito se houver vários. Caminhos pessoais
  trocados por `<repo>`.
- **Auditoria completa** (segredos na árvore e no histórico, licenças, referências internas):
  ver [`PUBLICAR.md`](../PUBLICAR.md). Resumo: nada de segredos, nada de LGPL/GPL, nenhuma
  referência interna.
- **Detalhe que quase passa:** o email do autor fica nos **metadados dos commits** (não é
  conteúdo de ficheiro, logo `git grep` não o apanha). Ver a decisão em `PUBLICAR.md` §2.1.
- **Origem:** **[LOCAL]**.

---

## AC. Diagnóstico

### 96. `cachalote doctor`: um comando em vez de dez perguntas ao adb
- **Porque existe:** diagnosticar isto à mão era sempre a mesma sequência — o daemon está vivo? o
  motor? a chave está lá e com modo 600? o `oom_score_adj`? o watchdog? o plugin foi inserido? a
  app responde às capacidades? Cada uma destas perguntas já me custou uma ronda de investigação.
- **O que faz:** percorre ficheiros (runtime, kernel + versão, ponte, CLI, camada de patch,
  **plugin referenciado pelo perfil**), chave (presença e modo), daemon (vivo, `oom_score_adj`,
  watchdog), canal (TCP, socket unix, motor a responder ao `status`), app **ponta a ponta**
  (`cachalote.host.call status` → acessibilidade e notificações), espaço em disco e registos
  (`did not activate` só no fim do registo, não no histórico todo). Sai com código 1 se houver
  problemas.
- **Como fala com a ponte:** `cachalote ctl <método> [params]` — um cliente mínimo pelo socket
  unix (autentica como cliente `tool`). Foi preciso para o doctor poder perguntar
  `cachalote.status` e `cachalote.host.call`.
- **Dois bugs meus que o primeiro `doctor` revelou:** o `pgrep` do motor usava um caminho que não
  corresponde ao real (o motor vive em `.../dsh/node_modules/@deepseek-ai/dsh/lib/bin.js`), e a
  contagem de `did not activate` somava o histórico todo. E os padrões `"campo":valor` não casavam
  porque o JSON vem indentado — tira-se espaços e mudanças de linha antes de comparar.
- **Verificação:** com tudo bom → `resultado: tudo bem`; com o daemon parado e o plugin apontado
  para um caminho inexistente → `2 problema(s) e 3 aviso(s)`, `exit=1`.
- **Origem:** **[LOCAL]**.

---

## AD. Títulos de sessão

### 97. O motor gera títulos… e não os mostra no `session/list`
- **O que se tentou:** o perfil ACP desliga o `session-title-llm` (o plugin por trás é o
  `dsh-session-title-first-prompt-llm`, uma chamada no primeiro prompt). Ligámo-lo na nossa
  camada (`- id: session-title-llm / disabled: false`) e o motor arranca sem avisos de activação.
- **O que se mediu:** `session/list` devolve as sessões **sem `title`** (todas), e a projecção
  (`home/storages/session_projcache/sessions/<id>.json`) guarda `record.identity` (sem título) +
  `record.rows` (forma que não expõe um evento de título nos campos testados). Ou seja: o título
  pode existir internamente, mas **não chega ao cliente ACP** — que é o que a app usa.
- **O que ficou (e é o que o utilizador vê):** a app deriva o título do **primeiro prompt** da
  sessão (48 caracteres, sem gastar chamadas) e guarda-o por modo no instantâneo
  (`files/sessao-<modo>.json` → `titles`). Se um dia o motor passar a devolver `title`, o código
  **prefere-o** e o derivado só serve de recurso.
- **Verificado:** o instantâneo da app ficou com
  `{"e21e9a7e…": "explica-me o ceu azul em duas frases"}`.
- **Limite honesto:** a lista mostra as primeiras ~50 sessões antigas (sem título, criadas antes
  disto), por isso não consegui ver a linha nova no ecrã; o que está provado é o que fica gravado
  e a ordem de preferência no código.
- **Origem:** **[LOCAL]**.

---

## AE. Pressão de memória a sério

### 98. Encher a RAM para testar o LMK (e o que se aprende com isso)
- **Como se faz sem ferramentas extra:** um processo Node que aloca 64 MB de cada vez até um
  tecto, com `oom_score_adj = 1000` (o mais matável possível). O kernel mata-o **a ele** — que é
  precisamente o que se quer verificar: com o daemon em **-800** e o motor em **-500**, a vítima
  escolhida é o alocador.
- **Primeira versão do teste estava errada:** o alocador saía mal acabava de alocar, e a "pressão"
  durava milissegundos (a observação via tudo vivo sem stress nenhum). Agora ele **segura** a
  memória 40 s.
- **Resultado medido (S10+, 7.4 GB):** 7.2 GB usados (97%) durante 30 s, e
  **daemon, motor e app sobreviveram todos, sem mudar de pid**; depois da pressão, o
  `cachalote doctor` diz `1 aviso(s), nenhum problema` (o aviso é a acessibilidade, que a
  reinstalação da app desliga — não é do teste).
- **Nota:** o registo do kernel não mostrou linhas de *lowmemorykiller* neste telefone
  (LineageOS 23 / kernel moderno usa outras vias); a evidência é a memória medida e os pids.
- **Origem:** **[LOCAL]**.

---

## AF. Servidores MCP

### 99. Ligar um servidor MCP ao agente — e a armadilha do `LD_LIBRARY_PATH`
- **Forma aceite** (validada pelo ACP, `dsh-acp` → `resolveMcpConfigs`):
  - stdio: `{name, command, args?, env?}` — **`command` tem de ser um caminho absoluto**;
  - http: `{name, type:"http", url, headers?}`.
  Vai no `session/new` e no `session/resume`. Os nomes das ferramentas ficam
  **`mcp__<servidor>__<ferramenta>`**.
- **A armadilha:** o `env` do servidor MCP é **próprio** (não herda o do motor). Se o comando for o
  Node que vem com o daemon, ele precisa de `LD_LIBRARY_PATH=<base>/runtime/lib` — senão morre no
  linker (`library libz.so.1 not found`) e o servidor **nunca regista ferramentas**. O sintoma é
  cruel: o `session/new` é aceite (o processo chegou a arrancar), o agente diz
  *"não tenho nenhuma ferramenta chamada mcp__…"* e não há erro nenhum no registo do motor.
- **`failOnStartupError: true`:** só falha a sessão se o arranque falhar de forma detectável; um
  servidor que arranca e morre não impede a sessão — daí a falha silenciosa.
- **A testar:** o daemon tem **um só lugar de cliente ACP** (a app retoma-o sempre). Um cliente de
  teste pelo socket unix é expulso quando a app está a correr: para testar MCP/`session/list` por
  script, parar a app primeiro.
- **Verificação (duas vezes):** servidor MCP mínimo (`tools/mcp-ping-server.mjs`, ferramentas
  `ping` e `echo`) montado numa sessão → o agente chamou `mcp__ping__ping` e respondeu **pong**.
  E **pela app**: com o servidor gravado no cartão *Servidores MCP*, sessão nova → transcript com
  `[tool] mcp__ping__ping [completed]` e `[assistant] pong`.
- **Origem:** **[LOCAL]**.

---

## AG. Recomposição em diálogos (bug aberto)

### 100. O diálogo das sessões não reflecte o estado da sessão activa
- **Sintoma:** o cabeçalho mostra `sessão: 17c839db`, o instantâneo tem esse id, e a lista do
  diálogo mostra a linha `17c839db` — mas **sem o ✓** (e, sem ✓, também não aparece o ✕ de
  fechar, que só se mostra na sessão activa).
- **O que está descartado:** não é o estado (cabeçalho e ficheiro concordam), não é o motor
  (`session/list` devolve esse id), não é o `close` (medido: devolve `{}` para a sessão activa).
  É a **composição do diálogo** que não acompanha `vm.sessionId`.
- **Hipótese:** o conteúdo do `AlertDialog` fica com a primeira composição (o `if (current)`
  avaliado quando `sessionId` ainda era outro/nulo) — o mesmo tipo de problema do item 66.
- **Como verificar (próximo passo):** ler `vm.sessionId` numa `val` no topo do `SessionsDialog`,
  forçar `key(vm.sessionId, row.id)` nos itens, ou testar se o diálogo recompõe ao fechar/abrir
  duas vezes. Depois, o teste de UI do ✕ fica possível.
- **Impacto:** hoje o fecho de sessões **não é alcançável pela UI** (o protocolo funciona).
- **Origem:** **[LOCAL]**.

### 101. Fechar a sessão pela UI — e o id que voltava
- **O que estava errado no diálogo:** o `✓`/`✕` da sessão activa nunca apareciam, apesar de o
  estado estar certo (cabeçalho e instantâneo com `17c839db`). A leitura de `vm.sessionId`
  dentro do lambda dos itens do `LazyColumn` ficava presa na primeira composição.
- **Correcção:** ler a sessão activa **uma vez, no topo do diálogo**, e mostrá-la no título
  (`activa: 17c839db`). Acrescentou-se um botão **Fechar a sessão activa** no rodapé: a lista tem
  dezenas de sessões antigas e andar à procura da linha certa não é razoável.
- **Verificado:** tocar em "Fechar a sessão activa" → `session/close` enviado, o diálogo passa a
  `nenhuma sessão activa` e o `session/list` é refrescado.
- **Bug apanhado no próprio teste:** depois de fechar, a mesma sessão voltava a aparecer activa —
  o id ficava em `lastSessionId` e o arranque (ou a troca de modo) retomava-o. Fechar passa a
  **esquecer** a última sessão.
- **Origem:** **[LOCAL]**.

### 102. Visão em vídeo sem `ffmpeg`: fotogramas anexados
- **O que se fez:** o `android_see` ganhou `frames` (1–12) e `intervalMs` (até 5 s). Repete a captura
  e anexa **uma imagem por fotograma** ao mesmo resultado da ferramenta; o `render` devolve-as por
  ordem. Sem `screenrecord`/`ffmpeg` (que o Android não traz) e sem custo extra de API.
- **Limites do harness:** o ACP aceita até 20 imagens por mensagem e a política de normalização
  aguenta 4 MP por imagem; 3–8 fotogramas a 720 px ficam confortáveis.
- **Verificado no S10+:** com `frames=3, intervalMs=2000` o agente respondeu que *o relógio
  avançou de 10:03:15 para 10:03:20, o indicador de carregamento rodou e o cursor de texto
  desapareceu* — ou seja, comparou imagens no tempo, que é o que se pede a "visão em vídeo".
- **Cuidado com o schema:** `attachmentRefs` tem de ser `{type:"array", items:{type:"json"}}` —
  um `{}` sem `type` desliga o plugin inteiro (item 82).
- **Origem:** **[LOCAL]**.

---

## AH. Ecrã virtual (vscreen) reimplementado com APIs AOSP

### 103. `createVirtualDisplay` + `ImageReader` numa app normal: funciona, e sem permissões
- **Decisão do utilizador:** opção (a) — reimplementar do zero, **zero LGPL**. Nada aqui vem do
  Operit; usa-se API pública do AOSP (Apache-2.0).
- **O que basta:**
  1. `DisplayManager.createVirtualDisplay("cachalote-vscreen", w, h, dpi, surface, flags)` com o
     `Surface` de um `ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)`; flags
     `VIRTUAL_DISPLAY_FLAG_PUBLIC or OWN_CONTENT_ONLY`. **Não precisou de permissão nenhuma** numa
     app normal (ao contrário do que eu temia: não é preciso `CAPTURE_VIDEO_OUTPUT`).
  2. Cada fotograma sai no `ImageReader` como `Image` RGBA; converter em `Bitmap` exige tratar o
     **`rowStride`** (cria-se o bitmap largo e corta-se a margem).
  3. O PNG resultante é o **mesmo formato** da capacidade `screenshot` (`data` base64 + `mediaType`)
     — entra no caminho de anexos do `android_see` sem código novo.
- **Lançar apps no ecrã:** `am start --display <id> -n <pkg>/<activity>` (ou
  `ActivityOptions.setLaunchDisplayId`). Se a app já estiver a correr, o Android avisa
  *"intent has been delivered to currently running top-most instance"* e traz a instância para ali
  — que é o que se quer.
- **Injectar toques/teclas:** `input -d <displayId> tap x y` **como root** (é o daemon que o faz).
  Consequência importante: no ecrã virtual **não é preciso o serviço de acessibilidade** — a
  captura vem do `ImageReader` e o toque vem do `input`.
- **Verificação no S10+ (com prova visual):** `displayId=2`, 720×1280 @320; calculadora lançada
  com `--display 2`; fotograma em
  [`docs/screenshots/vscreen-calculadora.png`](../docs/screenshots/vscreen-calculadora.png)
  (mostra **512**); depois de `input -d 2 tap 105 838` (a tecla «7»), novo fotograma em
  [`docs/screenshots/vscreen-toque-registado.png`](../docs/screenshots/vscreen-toque-registado.png)
  (mostra **5 127**) — o toque registou-se no ecrã virtual.
- **Origem:** **[LOCAL]**.

### 104. Ferramentas do agente para o ecrã virtual
- **Desenho:** a ferramenta `android_vscreen` faz o que a app sabe fazer (`start`, `status`,
  `frame`, `stop` — capacidades da app, sem shell). O que precisa de root (`am start --display`,
  `input -d`) o agente corre com o `bash` que já tem — e a descrição da ferramenta diz-lhe isso
  com os comandos exactos. Vantagem: nenhuma capacidade de shell nova, tudo o que é privilegiado
  continua a passar pela guarda/auditoria do `bash`.
- **O fotograma entra no contexto** como anexo (mesmo caminho do `android_see`), por isso o
  agente vê o ecrã virtual sem nada de novo no lado das imagens.
- **Verificação (agente, ponta a ponta):** pedido *"inicia um ecrã virtual, lança lá a calculadora
  com bash, tira um fotograma e diz o número"* → `android_vscreen start` (display 3) →
  `bash am start --display 3 …` → `android_vscreen frame` → **"O número no visor é 5127
  (apresentado como `5 127`)"**.
- **Cuidado:** um ecrã virtual a correr produz fotogramas continuamente; parar (`vscreen_stop`)
  quando acaba, ou fica a consumir memória/bateria.
- **Origem:** **[LOCAL]**.

### 105. Pré-visualização do ecrã virtual na própria app
- **O que se fez:** cartão *Ecrã virtual* no separador Poderes com `Iniciar`, `Ver fotograma`,
  `Parar`, o estado (`display 2 · 540×960 · 51 KB`) e a **imagem do último fotograma** desenhada
  em Compose a partir do PNG que a capacidade devolve. A app chama a capacidade pelo mesmo
  caminho que o agente usa (`controller.capability(...)`), o que serve de teste ao próprio canal.
- **Verificação:** com o ecrã virtual criado pelo botão (display 2) e a calculadora lançada lá,
  o fotograma aparece no cartão — [`docs/screenshots/app-ecra-virtual.png`](../docs/screenshots/app-ecra-virtual.png).
- **Nota de arquitectura:** o ecrã virtual vive **dentro do processo da app**; se a app for
  fechada, ele desaparece (verificado: `dumpsys display` fica sem `cachalote-vscreen`). Não é uma
  fuga — é o dono a morrer com a coisa. Para o agente, a app tem de estar a correr (o serviço em
  primeiro plano é o que a mantém viva em segundo plano).
- **Origem:** **[LOCAL]**.

### 106. Um requisito de dispositivo a mais parte a CI
- **Sintoma:** o job `apk` da CI passou a falhar em `fetch-runtime.sh` com
  *"nenhum dispositivo ligado (adb devices vazio)"* — num runner que, por definição, não tem
  telemóvel.
- **Causa:** ao introduzir o `tools/lib-device.sh` (para tirar o serial do repo), pus a resolução
  do dispositivo no **topo** de todos os scripts. O `fetch-runtime.sh` só precisa dele com
  `--from-device`, e o `prepare-kernel-zip.sh` só com `--push`.
- **Correcção:** resolver o dispositivo **só no ramo que usa adb**. Verificado com
  `env PATH=/usr/bin:/bin bash tools/fetch-runtime.sh` (sem adb nenhum).
- **Bónus apanhado na mesma simulação:** `tar.extract(..., filter="data")` só existe no Python
  3.12+; passou a ter alternativa, senão o mesmo script falharia em máquinas com Python antigo.
- **Lição:** um requisito novo (mesmo "higiénico", como tirar o serial) tem de ser testado no
  caminho mais restrito onde o script corre — a CI é esse sítio.
- **Origem:** **[LOCAL]**.

### 107. Licenças de terceiros dentro da app (textos integrais)
- **Porque:** o Apache-2.0 pede uma **cópia da licença** para o que se redistribui, e as outras
  pedem atribuição. Ter os textos no sítio onde o utilizador os pode ler resolve isso e serve de
  fonte única para o repositório.
- **Como:** `app/src/main/assets/licenses.json` (13 entradas, 56 KB) com `nome`, `licenca`, `url` e
  o `texto` integral; `Licencas.kt` lê-o e o separador *Poderes* mostra a lista e, ao tocar, o
  texto em monoespaçado.
- **Textos de onde:** MIT canónico (com a linha de copyright de cada projecto), Apache-2.0 de
  `apache.org`, Unicode License V3 de `unicode.org`, zlib de `madler/zlib`, mais a excepção LLVM
  para o libc++. SQLite é domínio público (nota, sem texto) e o Termux entra só como proveniência.
- **Cuidado:** o `LICENSE` do Node (154 KB) **não** é o texto MIT — traz as licenças de todas as
  dependências embutidas. Para o MIT usa-se o texto canónico.
- **Verificação:** diálogo com a lista e o texto do Apache-2.0 em
  [`docs/screenshots/app-licencas.png`](../docs/screenshots/app-licencas.png).
- **Origem:** **[LOCAL]**.

### 108. Notificação no fim do turno (e porque é que a app tem de saber se está à frente)
- **O que faz:** quando um turno acaba e a app **não** está em primeiro plano, aparece uma
  notificação com o início da resposta (ou o erro). Com a app à frente não notifica nada — seria
  ruído.
- **Como se sabe se está à frente:** um sinal em `Notificacoes.appEmPrimeiroPlano`, marcado no
  `onResume`/`onPause` do `MainActivity`. Não se usa `getRunningAppProcesses` (mentia desde o
  Android 5) nem se olha para o ciclo de vida do serviço (o serviço vive sempre).
- **Canal próprio** (`cachalote-turno`) para o utilizador poder silenciar só isto.
- **Verificação:** testes instrumentados — a notificação aparece com o sinal a falso e **não**
  aparece com o sinal a verdadeiro (`RecursosTest`).

### 109. Testes instrumentados a substituir os toques cegos
- **O problema:** os testes de UI por `uiautomator` falhavam por tudo e por nada (campo sem foco,
  teclado a tapar o botão, `BACK` a fechar a app, texto escrito no campo errado). Verificavam mal e
  custavam tempo.
- **O que se fez:** `app/src/androidTest` com JUnit + `androidx.test`, a correr **no aparelho** com
  `gradle connectedDebugAndroidTest`. Nove testes, sem um único toque:
  - i18n: inglês por omissão, pt-PT correcto, **pt-BR nunca apanha formas brasileiras**, nenhuma
    string vazia, e a maioria traduzida (se tudo fosse igual, o pt-PT estaria por traduzir);
  - licenças: carregam, têm texto, e os textos são os verdadeiros (`Apache License`, cláusula de
    garantia do MIT, `as-is` do zlib);
  - notificação: aparece com a app atrás, não aparece com a app à frente.
- **Bónus:** os testes **apanharam um pt-BR-ismo real** que me tinha escapado — o texto do ecrã
  virtual em pt-PT dizia "segunda tela". Passou a "um segundo ecrã". Sem os testes, isso ia para o
  telefone.
- **CI:** `tools/i18n-check.sh` compara as chaves dos dois ficheiros (uma tradução em falta cai
  silenciosamente no inglês, por isso só uma comparação de ficheiros a apanha), confirma que todas
  as `R.string.*` usadas existem e recusa formas brasileiras no pt-PT.
- **Origem:** **[LOCAL]**.

### 110. Ecrã virtual: tocar sem dar um shell ao plugin
- **O problema:** o agente tinha de fazer `bash` → `input -d <id> tap …` para cada toque. Funciona,
  mas obriga a uma ida ao modelo por cada gesto e espalha a lógica por dois sítios.
- **O que se fez:** o plugin ganhou as acções `launch`, `tap`, `swipe`, `key` e `text`, que passam
  pelo daemon. O daemon **não** dá um shell: expõe dois métodos estreitos —
  `cachalote.vscreen.input` (só `tap`/`swipe`/`text`/`keyevent`) e `cachalote.vscreen.launch`
  (só `pacote/Actividade`) — e ambos **confirmam primeiro com a app** qual é o ecrã virtual activo.
  Só se injecta no display que a app disser que é dela.
- **Sem shell, sem injecção de comandos:** a execução é por `execFile` (não há shell pelo meio),
  e os argumentos são validados (números, ou `KEYCODE_*`, ou uma string para `text`).
- **Bug apanhado pelo próprio teste:** o `keyevent` só aceitava números e recusava
  `KEYCODE_BACK` — corrigido para aceitar nome ou código.
- **Verificação:** [`tools/vscreen-tool-test.sh`](../tools/vscreen-tool-test.sh) chama a ferramenta
  **a sério** (o mesmo código que o agente) contra o daemon: recusa display alheio, recusa
  componente inválido, lança a calculadora, tira fotograma, toca e manda `KEYCODE_BACK` → 6/6.
- **Origem:** **[LOCAL]**.

### 111. `ctl @ficheiro`: escapar JSON dentro de `su -c` é uma armadilha
- **Sintoma:** o mesmo comando funcionava à mão e devolvia vazio dentro de um script.
- **Causa:** `adb shell "su -c '$1'"` — as aspas simples do JSON **fechavam** o `su -c`, e o
  comando partia-se sem erro visível.
- **Correcção:** `cachalote ctl <método> @/caminho/params.json` lê os parâmetros de um ficheiro.
  Os scripts empurram o JSON e deixam de ter aspas para escapar.
- **Origem:** **[LOCAL]**.

### 112. Duas armadilhas de teste que me custaram uma hora
- **`connectedDebugAndroidTest` desinstala a app no fim** (e com ela vão as preferências: modo,
  capacidades autorizadas, URL do kernel, MCP). Sintoma: o daemon fica sozinho, `appConnected:
  false` para sempre, e `am start` responde *"Activity class does not exist"*. Depois de correr
  testes instrumentados, reinstalar (`tools/install-app.sh`) e reautorizar o que for preciso.
- **Numa instalação nova, sem `POST_NOTIFICATIONS` concedida, a app morre no arranque** — o
  diálogo de permissões aparece, o arranque é cancelado (`result code=-92`) e não há crash no
  `logcat` (nem no buffer de crashes) que explique nada. Sintoma: `pidof` vazio segundos depois de
  `am start`, com o sistema bem disposto para outras apps. Correcção:
  `adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS`.
- **Como distinguir:** se *outras* apps arrancam e a nossa não, e não há excepção em lado nenhum,
  procurar permissões por conceder antes de suspeitar do código.
- **Origem:** **[LOCAL]** (custou uma hora; o registo acima vale por ela).

### 113. Pré-visualização ao vivo (e como se prova que é vivo)
- **O que se fez:** interruptor **Ao vivo** no cartão do ecrã virtual: enquanto está ligado, a app
  pede um fotograma a cada 1,5 s e desenha-o. O pedido vai por um caminho "silencioso" (não mexe
  no estado `busy`, senão os botões piscavam a cada pedido, nem na faixa de erro).
- **Como se prova que é mesmo vivo:** não basta que a imagem apareça — um fotograma estático
  também aparece. O teste é comparar **dois fotogramas separados por uma acção**: com o conteúdo
  parado dão o mesmo `md5` (correcto), e **depois de um toque injectado** dão `md5` diferente
  (`06f87e8d2864` → `481004c6ca30`, 59 179 → 59 562 bytes). É isso que a pré-visualização mostra.
- **Origem:** **[LOCAL]**.

### 114. Testes de interface na JVM: o Espresso não corre nesta ROM
- **O que se tentou:** testes de interface instrumentados com Compose (`createComposeRule`) no
  aparelho. Todos os 8 falharam com
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance` dentro de `Espresso.onIdle`.
  Não era o teste: o Espresso injecta eventos por uma API que as ROMs recentes escondem. Fixar
  `espresso-core:3.6.1` e relaxar as três `hidden_api_policy` não resolveu.
- **O que se fez:** os mesmos testes a correr na **JVM com Robolectric** (`test/`, não `androidTest/`).
  `@Config(sdk = [34], qualifiers = "pt-rPT")` para os textos em português europeu e uma segunda
  classe com `qualifiers = "en"` para o inglês. Correm **na CI**, sem aparelho — 11 testes.
- **Duas armadilhas do Robolectric:** quer escrever no `$HOME` (recusado pelo sandbox) e, com
  `robolectric.dependency.dir` definido, **deixa de descarregar** os jars e falha logo. Solução:
  redireccionar só o `user.home` do processo de teste para dentro do `build/`.
- **Ganho inesperado:** o Robolectric com `qualifiers` testa os **dois idiomas** de forma
  determinística — o que o aparelho não deixou fazer (`cmd locale set-app-locales` não pega nesta
  ROM).
- **O que fica instrumentado:** o que precisa do telefone a sério (notificação, i18n de recursos).
- **Origem:** **[LOCAL]**.

### 115. Backup das definições — e o que ele não leva
- **O que leva:** modo, capacidades autorizadas, áreas do bash, URL do kernel e servidores MCP.
- **O que não leva, de propósito:** a **chave do provedor** (vive no daemon; a app nunca a guarda) e
  o **token do canal** (segredo entre a app e o daemon). Um backup que anda por aí com segredos
  dentro é uma fuga à espera de acontecer — e há um teste que falha se algum deles aparecer no
  ficheiro.
- **Importação defensiva:** um ficheiro que não seja do Cachalote é recusado; um MCP inválido é
  recusado **antes** de estragar as sessões novas; um modo desconhecido é ignorado.
- **Verificação:** `BackupTest` (4 testes, na JVM) — exporta/importa e confirma que os segredos não
  saem.
- **Origem:** **[LOCAL]**.

### 116. O agente ficava parado à espera de uma decisão que ninguém sabia que era precisa
- **O sintoma:** o diálogo de aprovação só existe **dentro da app**. Com a app em segundo plano,
  o agente ficava pendurado num `session/request_permission` sem nada a avisar o utilizador.
- **A correcção:** `Notificacoes.pedidoDeAutorizacao(...)` no momento em que o pedido nasce e
  `cancelarPedidoDeAutorizacao(...)` quando é respondido (id próprio, `1002`). Tocar na notificação
  abre a app — onde está o diálogo.
- **Cuidado:** cada aviso tem o **seu id** (`1001` fim de turno, `1002` autorização, `1003`
  acessibilidade). Com um id partilhado, uma notificação apagava a outra — há um teste para isso.
- **Verificação:** `AvisosTest` (8 testes, JVM/Robolectric): aparece com a app atrás, **não**
  aparece com a app à frente, desaparece ao responder, e os avisos são independentes.
- **Origem:** **[LOCAL]**.

### 117. O consumo de contexto era recebido e deitado fora
- **O que se descobriu:** o motor envia `usage_update` (`used`/`size`) em cada turno — estava nos
  registos desde o início — e a app **ignorava-o**. Era a única noção de custo disponível, de graça.
- **O que se fez:** `Uso.kt` (parsing + formatação com separador de milhares fino, como manda o
  português) e `ConsumoDeContexto` no cabeçalho: `contexto: 6 290 / 1 000 000 · 1%`. Não desenha
  nada enquanto não houver números.
- **Detalhe que os testes fixaram:** `0,629%` arredonda para `1` (e não para `0`) — a expectativa
  errada era minha, não do código.
- **Verificação:** `UsoTest` (5) + `ConsumoTest` (2, Compose).
- **Origem:** **[LOCAL]**.

### 118. A acessibilidade caía e o agente só descobria ao falhar
- **O sintoma:** o serviço desliga-se em reinstalações e depois de o telefone dormir. O agente
  tentava usar o ecrã, falhava, e o utilizador via um erro estranho em vez de um aviso.
- **A correcção:** `VigiaAcessibilidade` observa a cada 5 s e avisa **na conversa** (e por
  notificação, se a app não estiver à frente). Só avisa na transição ligada → desligada: leituras
  repetidas do mesmo estado não geram avisos, e voltar a ligar não é alarme.
- **Bug apanhado na verificação:** o aviso não aparecia na leitura do instantâneo porque **não era
  persistido** (`transcript.add` sem `saveState()`). Corrigido — o aviso sobrevive a fechar a app.
- **Verificação:** no aparelho, com a app a correr: desligar a acessibilidade →
  *"a acessibilidade desligou-se: o agente perdeu olhos e dedos no ecrã"* no transcript (gravado).
  `AvisosTest` cobre a lógica de transição (só cai, não repete, conta quedas).
- **Origem:** **[LOCAL]**.

### 119. Bootstrap completo: do clone ao daemon a correr + app instalada
- **O que faltava:** o `bootstrap.sh` só tinha sido corrido com `--skip-daemon --skip-app` — ou seja,
  os passos 4 e 5 (instalar o daemon e a app) **nunca** tinham sido exercitados a sério. Uma coisa é
  ter o script, outra é saber que ele funciona do princípio ao fim.
- **Corrido a sério (2026-10-14):** os 6 passos, saída 0 — kernel do **npm** (`@deepseek-ai/dsh`
  0.2.0-rc.2) + patches, runtime do Termux, daemon com autostart, APK (376 MB, com o kernel
  embutido, que é como este caminho funciona) instalada, app aberta.
- **A chave do provedor sobreviveu** — o instalador do daemon só cria `home/.env` se ele **não
  existir** (53 bytes, modo 600, data original intacta). Antes de correr, fez-se cópia do `home/`
  inteiro para `.work/backup-device/` (2,6 MB), porque um erro aqui custaria a chave do utilizador.
- **Origem:** **[LOCAL]**.

### 120. "De onde veio esta árvore?" — marca de origem do kernel
- **O problema:** ao olhar para o telefone não havia **maneira nenhuma** de saber se o kernel veio do
  `app.asar` do desktop ou do `npm`. Havia dois caminhos possíveis e zero registo.
- **A correcção:** `tools/extract-kernel.cjs` escreve `.cachalote-origem` na raiz da árvore com
  `origem`, `fonte`, `versao`, `cortado`, `sha256` da árvore, `quando`; o `cachalote doctor` mostra
  `[ok] origem do kernel: npm 0.2.0-rc.2` (e avisa quando não há marca).
- **Dois erros meus pelo caminho, os dois por caminhos de ficheiro errados:** o kernel vive em
  `dsh/node_modules/@deepseek-ai/dsh/`, e eu procurei `dsh/package.json`.
- **Lição (a terceira vez nesta sessão):** uma edição feita por script **tem de ser verificada** a
  seguir (`grep`) — o script imprimia "feito" mesmo quando a âncora não existia. Foi assim que os
  passos do i18n e do scan de segredos ficaram **fora da CI** sem eu notar.
- **Origem:** **[LOCAL]**.

### 121. `secret-scan.sh`: segredos fora do repositório e do histórico
- **Porque:** o repositório vai ser público e tirar um segredo depois obriga a reescrever o
  histórico (como já foi preciso fazer com a identidade dos commits). Prevenir é mais barato.
- **O que verifica:** (1) ficheiros que o git segue — `.jks`, `.keystore`, `.p12`, `.pem`, `.env`,
  `app.token`; (2) conteúdo com cara de segredo — chaves privadas, `sk-…`, tokens do GitHub/AWS/Slack;
  (3) **nomes no histórico**; (4) **conteúdo no histórico** (todos os commits).
- **Corre na CI** a cada push, ao lado do `i18n-check.sh`.
- **Cuidado:** a password por omissão da chave de assinatura está escrita no `build-release.sh`
  (é pública). Serve para desenvolvimento; quem tiver o `.jks` assina como "Cachalote" com ela. A
  secção 4 do `PUBLICAR.md` explica o que fazer (e o `sha256` para conferir a cópia).
- **Origem:** **[LOCAL]**.

### 122. Re-verificar o Shizuku valeu a pena: cinco bugs reais
O flavor Shizuku tinha sido verificado numa sessão antiga e o código mudou muito desde então
(i18n, capacidades, vscreen). Ao voltar a correr o caminho inteiro apareceram **cinco** problemas,
todos invisíveis no dia-a-dia do modo root:

| Bug | Sintoma | Correcção |
|---|---|---|
| **`doctor` com porto fixo** | dizia `TCP 39731 à escuta` num daemon que ouve no **39733** — mentia sobre o canal | o porto vem de `$BASE/home/port` (com o valor antigo como recurso) |
| **Marca de origem não copiada** | toda a instalação nova avisava "kernel sem marca de origem" | o `tar` da árvore encenada passa a incluir `.cachalote-origem` |
| **Token velho depois de reinstalar o daemon** | `shizuku: token inválido` para sempre (a app guardava o token antigo e nunca relia) | a ponte relê o ficheiro quando o `mtime` muda **e** a app, ao ver "token inválido", relê o token e tenta outra vez |
| **Dois daemons no mesmo porto** | reinstalar não parava o anterior: dois bridges ao mesmo tempo | `start` procura os nossos processos pelo caminho do bridge e pára-os (e **diz** quando não consegue, em vez de fingir) |
| **CLI com a base errada** | correr `/data/local/tmp/cachalote/bin/cachalote` gerava o daemon **root** (o valor por omissão era `/data/adb/cachalote`) — chegou a matar o daemon errado | a base por omissão passa a ser **onde o script vive** (`…/bin/cachalote` → a pasta de cima) |

- **Verificado no fim:** o daemon instala-se **sem root**, corre como **uid shell**, protege-se do
  LMK, tem watchdog, ouve no 39733, o motor corre como shell, o `doctor` funciona contra essa base e
  o **diálogo de permissão do Shizuku aparece sozinho** (a app passou a pedi-lo quando o arranque
  falha por falta dele).
- **Por verificar:** a ligação app↔daemon em modo Shizuku de ponta a ponta e um turno a sério — a
  autorização do Shizuku é **por app** e perde-se a cada reinstalação (e um turno exigiria pôr a
  chave nessa base, que é uma decisão do dono).
- **Origem:** **[LOCAL]**.

### 123. Títulos do motor: investigados, e **não existem** do lado do cliente
- **O que se fez:** procurou-se o título em todas as portas possíveis.
  1. **ACP `session/list`** — devolve as sessões **sem `title`** (medido: todas).
  2. **Registos de sessão no disco** (`home/sessions/<cwd>/<id>/session.v4.jsonl.zstd`) — 76
     sessões, e **todas** têm **apenas o evento `session`** (o cabeçalho). O corpo não é escrito em
     disco (fica em memória e, aparentemente, só sai ao fechar a sessão).
  3. **Projecção** (`home/storages/session_projcache/`) — `record.identity` não tem título e
     `record.rows` não expôs nenhum evento de título nos campos testados.
- **Conclusão:** o plugin `session-title-llm` está ligado e gera títulos **internamente**, mas
  nenhum caminho do cliente os vê. Não é um bug nosso — é uma lacuna da API.
- **O que fica:** a app continua a derivar o título do **primeiro prompt** (48 caracteres) e
  **prefere** o do motor se algum dia vier. Vale a pena pedir isso a montante (`title` no
  `session/list`), não inventar mais voltas cá.
- **Origem:** **[LOCAL]**.

### 124. Ecrã virtual: o que sobrevive e o que não
- **Medido:** com o ecrã virtual a correr (display 7), fechar a **interface** (HOME) não o mata —
  `running: true`, 10,7 s de uptime. É o serviço em primeiro plano que mantém o processo vivo.
- **E não pode ser de outra maneira:** um *virtual display* pertence a um **processo Android**. O
  daemon é um processo **Node** — não tem `DisplayManager`. Passar o ecrã virtual "para o daemon"
  não é uma migração possível; a alternativa seria um serviço separado, e ainda seria um processo
  Android.
- **O que acontece:** `am force-stop` (ou o sistema a matar a app) leva o ecrã virtual com ele
  (0 displays). Documentado na app — *"vive dentro desta app: se ela for fechada, o ecrã virtual
  desaparece"*.
- **Origem:** **[LOCAL]**.

### 125. Fila de tarefas: "faz isto e avisa-me"
- **O que é:** o dono junta tarefas (texto) e a app corre-as **uma a seguir à outra**, esperando
  pelo fim de cada turno. Cada turno gera a sua notificação (é o `enviarPrompt` que a manda) e um
  pedido de autorização **pára-a naturalmente** — o turno não acaba até o dono responder, e a
  notificação de autorização (§116) já avisa.
- **Desenho:** a lógica vive em `Fila.kt` (ordem, estados, persistência em `files/fila.json`),
  separada do ViewModel e da UI para se poder testar sem aparelho. O cartão é um composable de
  dados+lambdas (`FilaCard`).
- **Decisão:** uma tarefa que estava a correr quando a app morreu volta a **pendente** — não se sabe
  se acabou, e repetir é melhor do que perder o trabalho. Uma tarefa **falhada** não se repete
  sozinha (senão a fila entrava em ciclo).
- **Bug apanhado só na verificação no aparelho:** a fila era lida do ficheiro, mas o estado da UI
  (`tarefas`) **não era inicializado** — a fila parecia perdida depois de fechar a app, que é
  exactamente o que ela existe para evitar. Os testes unitários passavam (testavam `Fila`, não o
  arranque do ViewModel). Corrigido no `init` e verificado no telefone: `2 na fila` com as duas
  pendentes, a concluída fora.
- **Verificação:** `FilaTest` (7) + `FilaCardTest` (5) na JVM; no aparelho, o cartão aparece e a
  fila sobrevive ao arranque.
- **Limite honesto:** a fila corre enquanto a app estiver viva (o serviço em primeiro plano ajuda);
  se o processo morrer, o turno em curso perde-se e a tarefa volta a pendente.
- **Origem:** **[LOCAL]**.

### 126. Correr plugins do DSH: sim, e é assim
- **A resposta curta:** já corremos. Toda a camada de capacidades Android **é** um plugin do DSH
  (`@deepseek-ai/dsh-tool-cachalote`) montado pela nossa camada de patch — as ferramentas
  `android_*` que o agente usa são disso.
- **Para instalar outro plugin (de terceiros):** põe o pacote em
  `kernel/dsh/node_modules/<pacote>` e acrescenta um `insert` em `config/cachalote.patch.yml` com o
  **caminho absoluto** do `lib/index.js` (um caminho relativo **não** é resolvido: o
  `bareModuleBaseUrl` do perfil não inclui a nossa pasta).
- **Um plugin é isto:** exporta `name`, `inject` e `apply(ctx)`; o `apply` faz
  `ctx.tools.register(defineTool({...}))`. O exemplo completo está em
  [`kernel-plugins/exemplo/`](../kernel-plugins/exemplo/) e vem montado de fábrica.
- **Provado no telefone:** montado o exemplo, o registo do motor mostra
  `[plugin-exemplo] activo: registei a ferramenta exemplo_ola` e o `doctor` diz `tudo bem`.
- **⚠ Armadilha que me deitou o motor abaixo:** o `cordis.patch.yml` é YAML com **níveis de
  indentação que contam**. Anexei o `insert` novo depois de um bloco de 2 espaços, com 4 espaços, e
  o motor morreu no arranque com *"bad indentation of a mapping entry"* — e o daemon reiniciou-o em
  ciclo. O `doctor` apanhou-o à primeira ("motor em baixo"). **Regra:** os `insert` vão **dentro** do
  `insert:` existente, com a mesma indentação dos vizinhos.
- **Origem:** **[LOCAL]**.

### 127. Skills no Cachalote: o sistema já estava montado, faltavam as skills
- **Descoberta:** o perfil base já monta `dsh-skill`, `dsh-skill-filesystem`, `dsh-skill-badge` e
  `dsh-tool-skill` — ou seja, o agente **tem** o sistema de skills. O que faltava era haver skills:
  o `dsh-skill-filesystem` procura em `$DSH_HOME/skills` (e em `<projecto>/.dsh/skills`) e essa pasta
  não existia.
- **Instalar uma skill** é copiar uma pasta com `SKILL.md` (formato aberto *Agent Skills*) para
  `home/skills/`. Nada de código.
- **Feito:** [`tools/install-skills.sh`](../tools/install-skills.sh) instala as skills de **design**
  do Stitch (Google, Apache-2.0) — 11: `generate-design`, `code-to-design`, `extract-design-md`,
  `extract-static-html`, `manage-design-system`, `upload-to-stitch`, `design-md`, `enhance-prompt`,
  `site-md`, `stitch-loop`, `taste-design`. Os de *build* (`react-*`, `shadcn`, `remotion`) ficaram
  de fora de propósito: geram web/React e o Cachalote é Compose.
- **Limite honesto:** as skills descobrem-se em runtime e o agente lista-as pela ferramenta de
  skills — mas **não consegui provar a listagem**: os turnos estão a falhar por transporte da API
  (*"DeepSeek Messages transport failed"*) e a automação de UI voltou a falhar no envio. O motor
  está saudável (`doctor: tudo bem`) e as 11 pastas estão no sítio.
- **As skills do Stitch precisam do MCP do Stitch** para gerar de facto (é um MCP **remoto**:
  URL + credenciais, obtidos em <https://stitch.withgoogle.com/docs/mcp/setup/>).
- **Origem:** **[LOCAL]**.

### 128. Quando tudo o que é rede falha, testa a rede do telefone primeiro
- **O sintoma:** os turnos falhavam com *"DeepSeek Messages transport failed"* e o MCP do Stitch com
  *"mcp-client(stitch): initial connection or tool synchronization failed"*. Passei horas a culpar a
  API e a configuração do MCP.
- **A causa real:** o **telefone estava sem rede**. `ping 8.8.8.8` → *"Network is unreachable"*,
  todos os DNS → `ENOTFOUND`, e `dumpsys connectivity` dizia **"Active default network: none"** —
  com o Wi-Fi *ligado* e a interface móvel (`rmnet0`, 10.3.208.136) com IP, mas sem rota por omissão.
- **Como se diagnostica em 20 segundos** (e devia ter sido o primeiro passo):

  ```sh
  adb shell 'ping -c 2 -W 2 8.8.8.8'          # sem DNS à mistura: é IP puro
  adb shell 'dumpsys connectivity | grep -m1 "Active default network"'
  ```
  E, para ver da perspectiva do motor (root, com o nosso Node):
  `node -e 'fetch("https://api.deepseek.com/").then(r=>console.log(r.status)).catch(e=>console.log(e.message))'`.
- **Lição:** um erro de *transporte* não é um erro de chave nem de configuração. Antes de mexer no
  que quer que seja, confirmar que o telefone tem internet.
- **Correcção do lado do projecto:** o `tools/secret-scan.sh` passou a conhecer também os formatos
  de chave da Google (`AQ.…`, `AIza…`).
- **Origem:** **[LOCAL]**.

### 129. Stitch MCP: configurado e verificado (falta só rede)
- **O que é:** MCP **remoto** da Google — `https://stitch.googleapis.com/mcp`, autenticado por
  cabeçalho `X-Goog-Api-Key`. Tem **15 ferramentas** e é exactamente o que faltava para desenhar:
  `create_project`, `generate_screen_from_text`, `edit_screens`, `generate_variants`, `list_screens`,
  `get_screen`, `upload_design_md`, `create_design_system_from_design_md`, `apply_design_system`…
- **Verificação directa (da máquina, não do telefone):** `initialize` respondeu
  (`serverInfo: StatelessServer`, protocolo 2024-11-05) e `tools/list` devolveu as 15. A chave é boa.
- **Como se configura no Cachalote:** no cartão *Servidores MCP*, em JSON. ⚠️ O ACP quer os
  cabeçalhos como **lista de entradas**, não como objecto:

  ```json
  [{"name":"stitch","type":"http","url":"https://stitch.googleapis.com/mcp",
    "headers":[{"name":"X-Goog-Api-Key","value":"AQ.…"}]}]
  ```
- **Estado:** gravado na app; o motor **tentou montar** e falhou apenas por falta de rede do
  telefone (ver item 128). A chave fica **só nas preferências da app** — não vai para o repositório e
  o `secret-scan` já a sabe reconhecer.
- **Origem:** **[LOCAL]**.

### 130. Assistente de configuração inicial
- **Porque existe:** a primeira decisão de quem instala é **como o motor corre** (root, Shizuku ou
  nada) — e essa decisão muda o que o agente consegue fazer e, sobretudo, **quanto o sistema
  operativo o trava**. Sem guia, o utilizador adivinha; e uma app que não arranca por falta de uma
  permissão (a de notificações mata o arranque, ver item 112) não explica nada.
- **Seis passos:** boas-vindas (o que é, que trabalha por turnos, que nada corre sem autorização e o
  que sai do telefone) · **escolha do modo** (os três, com o limite de cada um e detecção do que
  existe no aparelho + recomendação) · ligar o motor (daemon, token, motor a responder) ·
  acessibilidade · chave do provedor · capacidades · pronto.
- **Decisão de desenho:** durante o assistente **não há barra de navegação** — há um caminho a
  seguir, não abas. E pode ser repetido em *Ajustes → Repetir a configuração inicial*.
- **Bug apanhado ao ver no telefone:** no cartão escolhido (fundo `primaryContainer`) o texto usava
  as cores de superfície normais e ficava **ilegível** (escuro sobre teal). Passou a usar as cores
  `onPrimaryContainer` desse fundo.
- **Verificação:** no S10+, com a preferência nova a nascer a falso, o assistente apareceu; o passo
  da escolha do modo detectou o root e recomendou-o.
- **Origem:** **[LOCAL]**.

### 131. Aba *Resumo*: o `doctor` e o consumo, sem terminal
- **Porque:** o diagnóstico existia só no terminal (`cachalote doctor`) e o consumo de contexto
  vivia discreto no cabeçalho. Uma app destas precisa de um sítio onde se veja, de relance, se está
  tudo de pé.
- **Como:** a ponte ganhou `cachalote.doctor`, que **corre o próprio CLI** e devolve o resultado
  **estruturado** (`resultado`, `problemas`, `avisos`, `secoes[]`). Duplicar as verificações em
  JavaScript seria pedir que as duas listas divergissem; assim há uma só, a do CLI.
- **Na app:** a terceira aba mostra o consumo (tokens, sessão, modelo, esforço, fila, imagens) e o
  diagnóstico. Na primeira versão mostrava **só os itens que não estão `ok`**, com o resto resumido
  em «tudo o resto está verde (N secções verificadas)»; **desde o STATUS 62 mostra a tabela toda**
  (✓/⚠/✕ e o valor técnico à direita, em monospace) porque é assim que o ecrã «Resumo» do Stitch a
  desenha — e porque, com valores à vista, a lista verde também informa (versões, pids, modos).
- **Detalhe:** no modo embutido não há daemon, portanto não há doctor — a aba di-lo em vez de
  mostrar um erro.
- **Verificação:** no S10+, a aba mostrou `1 aviso(s), nenhum problema` com o aviso da
  acessibilidade desligada (o que era verdade — a app tinha sido reinstalada).
- **Origem:** **[LOCAL]**.

### 132. Instalar a APK desliga o serviço de acessibilidade (e não é só na desinstalação)

- **Sintoma:** depois de cada `tools/install-app.sh`, o `doctor` passa a dizer
  `[!!] acessibilidade desligada — liga em Definições → Acessibilidade → Cachalote` e o agente fica
  sem olhos nem dedos. Aconteceu **duas vezes** na mesma noite (S10+, 2026-10-14), uma na
  desinstalação e outra numa actualização normal (`adb install -r`).
- **Causa:** é o **Android**, não a app — quando um pacote é instalado/actualizado, o sistema
  desliga os seus serviços de acessibilidade (é uma defesa contra apps que se auto-activam). O
  `settings get secure enabled_accessibility_services` volta a `null`.
- **Como repor** (com root; a app não pode fazer isto por si):
  ```sh
  adb shell su -c 'settings put secure enabled_accessibility_services \
      dev.ricardopimentel.cachalote/dev.ricardopimentel.cachalote.CachaloteAccessibilityService'
  adb shell su -c 'settings put secure accessibility_enabled 1'
  ```
  Confirmação: `dumpsys accessibility | grep label=Cachalote` (tem de aparecer *bound*) e
  `cachalote doctor` a terminar em `resultado: tudo bem`.
- **Na app:** o `VigiaAcessibilidade` já avisa quando o serviço cai («o agente perdeu olhos e dedos
  no ecrã») e o assistente/doctor explicam como ligar — é o comportamento certo, mas **quem
  instala por script tem de contar com isto**: depois de instalar, ou se liga à mão, ou se aceita
  que o primeiro aviso do doctor é este.
- **Origem:** **[LOCAL]**.


### 133. Libertar o `VirtualDisplay` atira as apps para o ecrã físico

- **Sintoma:** o agente trabalha numa app dentro do ecrã virtual (isolado, o dono vê outra coisa no
  telefone) e, ao tocar em **Parar** na app Cachalote, a app do agente **aparece de repente no ecrã
  físico** — a calculadora ficou à frente, a ecrã inteiro. Medido no S10+ em 2026-10-15.
- **Causa:** é o Android, não a app. Quando um display é **removido**, o `ActivityTaskManager` move
  as *tasks* que lá estavam para o display por omissão em vez de as matar. O
  `DisplayManager.createVirtualDisplay` que o `VirtualScreen` usa é um display normal: ao
  `release()`, as activities vão atrás.
- **Consequência prática:** o isolamento é verdadeiro **enquanto o ecrã virtual existe** (verificado
  com `dumpsys window windows`: a janela da calculadora em `mDisplayId=8` e a app Cachalote em
  `mDisplayId=0`); o que não sobrevive é o *fim* — quem estava lá dentro sai para o ecrã do dono.
- **Como confirmar:** `dumpsys window windows | grep -E "Window #|mDisplayId="` antes e depois de
  parar o ecrã virtual.
- **Correcção (feita em 2026-10-21):** antes de libertar o display, fecha-se o que lá está — pelo
  daemon (root) ou pelo Shizuku. **`am task remove` não existe no Android 16**: o comando é
  `am stack remove <rootTaskId>`, e o id sai do `am stack list` (as linhas `RootTask id=N …
  displayId=D`). No modo embutido não há nada a fazer: sem root/shell não se lançam apps lá.
  Quando não há como fechar (sem `su`/Shizuku), a app **avisa** no transcript em vez de deixar a app
  aparecer de surpresa. Ver `HostCapabilities.fecharTasksDoDisplay`.
- **Origem:** **[LOCAL]** (S10+, 2026-10-15, ao aplicar o ecrã do design).

### 134. Um MCP configurado e inalcançável responde «Internal error» a tudo — e esconde o erro verdadeiro

- **Sintoma:** com o MCP do Stitch configurado (ver 129) e o telefone **sem rede**, um
  `session/prompt` numa sessão que já não existe devolve `-32603 Internal error`
  (`mcp-client(stitch): initial connection or tool synchronization failed`) em vez do
  `-32602 Invalid params: unknown session: …` que se esperava. A app mostra
  «prompt: Internal error» na faixa e a recuperação de sessão — que procura o texto
  `unknown session` — **não chega a correr**, porque quem falha primeiro é o `session/new` que ela
  tentaria a seguir.
- **Prova, no mesmo telefone, com e sem o MCP:**
  ```
  (MCP configurado)   prompt → error -32603 "Internal error" (mcp-client(stitch))
  (MCP limpo)         prompt → error -32602 "Invalid params: unknown session: 11111111-…"
                      → a app abriu sessão nova (33c12f8c-…) e escreveu no transcript
                        «a sessão anterior já não existe no motor — a abrir uma nova»
  ```
  O erro `unknown session` também fica no log do motor, mas **depois** do erro do MCP — e é o do MCP
  que a app vê como resposta ao `prompt`.
- **Causa:** o motor monta os servidores MCP ao criar/retomar sessão. Sem rede, cada montagem
  rebenta e o ACP devolve esse `Internal error` genérico, que passa a ser a resposta de qualquer
  pedido que precise de sessão.
- **Como diagnosticar:** `su -c 'tail -30 /data/adb/cachalote/logs/engine.log'` — o `-32602 unknown
  session` está lá, seguido do `-32603` do MCP. Para testar a recuperação de sessão, **limpar
  primeiro o MCP** (guardar `mcpServersJson` das prefs e repor depois).
- **Robustez (feita em 2026-10-21):** a app passou a **reconhecer** esta falha
  (`FalhasDeLigacao.suspeitaDeMcp`: «internal error» **e** haver MCP configurado), fecha e retoma a
  sessão **sem MCP** e repete o prompt uma vez — o turno não chegou a correr, por isso repetir é
  seguro. Diz-se no transcript: «os servidores MCP não responderam — esta mensagem correu sem eles».
  Ver `DaemonController.prompt`.
- **Origem:** **[LOCAL]** (S10+, 2026-10-15).

### 135. A lista de conversas: o `session/list` não traz título nem data, e as sessões estão em frames zstd encadeados

- **Sintoma:** a lista de conversas (o desenho novo, à WhatsApp) mostrava **76 linhas de
  hexadecimal** por ordem arbitrária — sem título, sem hora. E a lista vinha sempre igual,
  independentemente do que se tinha conversado.
- **Causa 1 — o ACP não dá:** `session/list` devolve **só** `sessionId` e `cwd`. Confirmado com um
  cliente directo contra o motor no S10+ (2026-10-15):
  ```json
  {"sessions":[{"sessionId":"269676d6-…","cwd":"/data/adb/cachalote/home/ws"}, …]}
  ```
  Nem `title` nem `updatedAt` — embora o motor **grave** os dois (ver abaixo). É a mesma lacuna do
  knowledge 97, agora com consequência visível.
- **Causa 2 — os títulos que a app guardava perdiam-se:** o `store.load()` repunha `sessionId`,
  modelo e esforço, mas **não** `sessionTitles`; o primeiro `saveState()` gravava `{}` por cima do
  ficheiro. Ou seja: os títulos derivados do primeiro prompt duravam até ao arranque seguinte.
- **O que está em disco:** cada sessão é
  `$DSH_HOME/sessions/<slug-do-cwd>/<id>/session.v4.jsonl.zstd`, e o JSONL traz tudo o que falta:
  ```json
  {"type":"session","createdAt":1791977431778,"cwd":"/data/adb/cachalote/home/ws"}
  {"type":"session/title","time":1791977605055,"data":{"title":"usa o bash para escrever"}}
  {"type":"user/message","time":1791977605015,"data":{"content":[{"type":"text","text":"usa o bash…"}]}}
  ```
  Dois detalhes que custaram: o **título do motor vem depois** do primeiro prompt no ficheiro (quem
  ficar com o primeiro que aparece fica com o prompt), e o `time` do último registo é a data boa
  (o `mtime` do ficheiro serve de fallback).
- **A armadilha do zstd:** o ficheiro é uma **concatenação de frames zstd** (um por lote durável).
  `zstdDecompressSync(ficheiroInteiro)` devolve só o **primeiro** frame — 174 bytes de um ficheiro
  de 17 KB — porque cada frame é independente. O `createZstdDecompress()` em stream também pára no
  primeiro. É preciso andar pelos frames (o kernel faz isso em
  `dsh-session-persistence-jsonl`: magic `0xFD2FB528`, descritor, blocos) e descomprimir cada um.
  Um frame a meio no fim (motor morto a escrever) fica de fora — e é o comportamento que se quer.
- **Solução:** `daemon/session-meta.cjs` lê os metadados (título, `createdAt`, `updatedAt`, `cwd`),
  expostos pelo daemon como `cachalote.sessions.meta` (na mesma ligação TCP autenticada que o ACP
  usa). A app cruza isso com o `session/list` e ordena pela conversa mais recente. Teste sem
  telefone: `tools/session-meta-test.cjs` (constrói uma sessão com a forma real, frames
  encadeados e um frame truncado no fim) — está na CI.
- **Limite honesto:** no **modo embutido** não há daemon, portanto não há metadados: a lista fica
  com o que a app já sabia (título derivado do prompt, ou o id curto) e **sem datas inventadas**.
- **Origem:** **[LOCAL]** (S10+, 2026-10-15, ao aplicar o desenho da lista de conversas).

### 136. A lista de conversas: o `session/list` do motor **não é a verdade**

- **Sintoma:** a lista de conversas mostrava 74 conversas quando havia **78** em disco — e as que
  faltavam eram precisamente as que tinham sido usadas há pouco (a `teste-recuperacao`, a
  `usa o bash para escrever`). Noutro momento, com o motor recém-reiniciado, listou **24 de 78**.
- **Causa:** o `session/list` do ACP devolve o que o motor tem *em memória*, não o que existe em
  disco: reiniciar o motor encolhe a lista, e ela volta a crescer à medida que as sessões são
  retomadas. (E, como se viu em §21.2, com a app ligada nem responde a um cliente externo: o motor
  tem um lugar de cliente ACP de cada vez.)
- **Consequência:** usar o motor como fonte da lista **esconde conversas** — inaceitável numa lista
  de conversas.
- **Correção:** a base da lista passou a ser o **disco** (o daemon lê os ficheiros das sessões —
  knowledge 135); o `session/list` ficou só como recurso para o modo embutido, onde não há daemon.
  Retomar uma sessão que o motor não lista funciona à mesma (o `session/resume` lê a persistência).
- **Origem:** **[LOCAL]** (S10+, 2026-10-15, ao pôr prévias reais na lista).

### 137. «session is already active» ao abrir a conversa que já está aberta

- **Sintoma:** tocar na linha da conversa **em uso** falhava com
  `retomar: Invalid params: session is already active: 269676d6-…` e não abria nada — a conversa que
  o dono mais provavelmente quer abrir era a única que não abria.
- **Causa:** o motor tem **um** cliente ACP de cada vez e a app retoma a sessão dela ao ligar-se;
  quando o utilizador toca nessa mesma linha, o `session/resume` responde `already active` — que não
  é falha nenhuma. O §23.1 já tratava isto **no laço de reconexão**, mas o caminho do toque na lista
  não: a excepção subia e virava faixa de erro.
- **Correção:** no `resumeSession` do modelo, `already active` é sucesso (e, sendo a mesma sessão, o
  transcript em memória **não** se apaga — é o dela).
- **Lição:** a mesma condição de erro tratada num caminho e não noutro é o padrão de bug mais fácil
  de repetir neste código (aconteceu com o MCP em baixo, §68.4, e com o `unknown session`, §66).
- **Origem:** **[LOCAL]** (S10+, 2026-10-15).

### 138. O build da app morre com «What went wrong: 25.0.3» — é o JDK demasiado novo

- **Sintoma:** `gradle` falha na configuração com uma linha só: `* What went wrong: 25.0.3`, sem mais
  nada. Nada mudou no código.
- **Causa:** o Kotlin que vem no AGP usa uma cópia da `JavaVersion` do IntelliJ que não sabe ler a
  versão de um JDK >= 24. Com o JBR do Android Studio já no 25, `JavaVersion.parse("25.0.3")` atira
  `IllegalArgumentException` — e o Gradle mostra só a mensagem da excepção.
- **Diagnóstico:** `./gradlew help --stacktrace` e procurar `JavaVersion.parse`.
- **Correcção:** usar um JDK **17–21**. Neste repositório, um JDK 21 posto em `.work/jdk21` é usado
  primeiro por `tools/install-app.sh` e `tools/bootstrap.sh`, e os dois **recusam** um JDK >= 24 com
  uma mensagem que diz o que fazer. (2026-10-21: o JBR passou ao 25 e o build só voltou com o
  Temurin 21 em `.work/jdk21`.)
- **Origem:** **[LOCAL]** (macOS, 2026-10-21).

### 139. O Cachalote corre fora da app — e «fechar a app» não pára nada

- **Sintoma:** o autor fechou todas as apps, o telefone continuou **super quente**, e não havia sítio
  nenhum para desligar o que estava a correr.
- **Causa:** por desenho, o motor vive **fora** da app — daemon root (`/data/adb`, arranca no boot
  pelo `service.d` do Magisk) e daemon do Shizuku (`/data/local/tmp`). Fechar a app não os toca.
- **O que faltava:** visibilidade e um botão. Agora há três: a notificação do serviço em primeiro
  plano tem a acção **«Parar o motor»** (funciona sem abrir a app), o ecrã **Motor** tem **«Parar
  tudo»** (daemons root **e** Shizuku, motor embutido e serviço) e um interruptor **«Arrancar com o
  telefone»** que liga/desliga o `service.d` — sem ele, «parar» era mentira, porque o reboot trazia
  tudo de volta.
- **E a app não desobedece:** com «Parar tudo» dado, o laço de reconexão **não** ressuscita o daemon
  (a auto-reparação do G1 fazia isso em segundos, e parecia que a app ignorava o dono).
- **Origem:** **[LOCAL]** (S10+, 2026-10-21).

### 140. O `service.d` do Magisk corre **todos** os ficheiros — renomear para `.off` não desliga nada

- **Sintoma:** o interruptor «Arrancar com o telefone» punha o `service.d/cachalote.sh` em
  `cachalote.sh.off` e o `service.d` parecia vazio; no arranque seguinte o daemon **voltou a subir**
  (o `boot.log` mostrava o `.off` a correr).
- **Causa:** o `service.d` do Magisk não filtra por extensão — executa tudo o que lá está, com
  qualquer nome. A ideia do `.off` vem do `init.d` de outras ROMs, não daqui.
- **Correcção:** para desligar, o script tem de **sair da pasta**: com o interruptor em baixo vai para
  `/data/adb/cachalote/arranque/cachalote.sh` (e volta ao `service.d` quando se liga). A app lê o
  estado do **disco**, não de uma preferência, para o interruptor não mentir.
- **Prova:** reboot com o interruptor em baixo → `pgrep -af cachalote` **vazio**, `service.d` vazio
  (2026-10-22).
- **Origem:** **[LOCAL]** (S10+, 2026-10-22).

### 141. Um teste Compose na JVM passa em debug e falha em release (`Unable to resolve activity … ComponentActivity`)

- **Sintoma:** `gradle test` (as duas variantes) falha só na **release**, com
  `RuntimeException: Unable to resolve activity for Intent { … cmp=<pkg>/androidx.activity.ComponentActivity }`,
  nas classes que usam `createComposeRule()`. Em debug passam.
- **Causa:** o `createComposeRule` lança a `androidx.activity.ComponentActivity`, e essa actividade só
  está no **manifesto fundido** por causa do artefacto `androidx.compose.ui:ui-test-manifest` — que
  se declara como `debugImplementation` **de propósito**, para não ir no APK publicado. Na release o
  manifesto não a tem e o Robolectric não a resolve.
- **Correcção:** não vale a pena pôr o `ui-test-manifest` na release (metia uma actividade de teste
  no APK); desligam-se os testes de unidade dessa variante e corre-se a debug, que é o que a CI já
  faz: `androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }`.
- **Bónus:** se apagares classes de teste do `src/test`, o build incremental pode deixar **.class
  velhos** na pasta da variante e eles correm na mesma (falhas fantasma de testes que já não existem)
  — apagar `build/tmp/kotlin-classes/<variante>` resolve.
- **Origem:** **[LOCAL]** (macOS, 2026-10-22).

### 142. Apagar uma conversa deixava os metadados dela em disco (a cache do projecto)

- **Sintoma:** a lista de conversas mostrava «fantasmas» — conversas já apagadas — e o disco tinha
  mais entradas na cache do que sessões reais (6 para 1, medido a 2026-10-22).
- **Causa:** o motor guarda dois sítios por sessão: a pasta `home/sessions/<projecto>/<id>/` (o log) e
  `home/storages/session_projcache/sessions/<id>.json` (título, datas, contagens). Apagar só tirava a
  pasta; o `.json` ficava, com o título da conversa «apagada».
- **Correcção:** `apagarSessao` (daemon/session-meta.cjs) remove os dois; se só existir a cache
  (sessão apagada antes da correcção) limpa-a e devolve `soCache: true`, em vez de falhar.
- **Para os testes:** quem cria sessões num ensaio tem de as apagar pelo daemon (não com `rm`), senão
  a cache fica a apontar para o nada. Ver `cachalote_apagar_sessao` em tools/lib-device.sh.
- **Origem:** **[LOCAL]** (S10+, 2026-10-22).

### 143. O `service.d` do Magisk corre um **watchdog**, não um `start` — e ele desfaz um «parar»

- **Sintoma:** «Parar tudo» parava, e meio minuto depois o daemon estava outra vez no ar.
- **Causa:** o script do `service.d` é `cachalote watchdog 30` (um laço que levanta o daemon quando o
  vê em baixo, para ele voltar depois de o LMK o matar). Matar o daemon não chega: o vigia levanta-o.
- **Correcção:** o `stop` mata primeiro o watchdog **daquela base** (`pgrep -f "$BASE/bin/cachalote
  watchdog"`, TERM, espera 1 s, KILL) e só depois o daemon. O `restart` **não** o mata (senão perdia-se
  a vigilância até ao próximo arranque); o `start` também não.
- **Como testar sem reiniciar o telefone:** `setsid` a partir do `adb shell` não sobrevive à sessão —
  o `adbd` mata o grupo. Para ver o watchdog a agir, arrancá-lo **dentro da mesma** sessão (`… watchdog
  5 &`), matar o bridge e esperar; para provar o `stop`, correr o script num só `adb shell`.
- **Origem:** **[LOCAL]** (S10+, 2026-10-22).

### 144. A telemetria do turno (passos, duração) está no registo da sessão, não no ACP

- **Pedido:** a linha do desenho «Em directo · 2/6 · 1.102/8.000 · 3s» — contagem de passos e tempo.
- **O que o protocolo dá:** nada disso. O `dsh-acp` manda mensagens e `usage_update`, não passos nem
  duração de turno (é a razão pela qual a linha ficou por fazer em §64).
- **O que o motor grava:** o registo da sessão (`session.v4.jsonl.zstd`) tem `turn/start`,
  `step/start`, `tool/call` e `turn/end`, todos com `seq` e `time`. Passos = nº de `step/start`;
  duração = `turn/end.time - turn/start.time`; estado = `turn/end.data.reason.kind`.
- **Como se lê:** `resumoTurnos()` em `daemon/session-meta.cjs` (frames zstd concatenados, leitor que
  já existia) e o método `cachalote.sessions.turns`; no modo embutido, o `meta.cjs` da APK com o
  argumento `turns <id>`.
- **Cuidado:** o motor escreve em lotes — a primeira leitura a seguir ao fim do turno pode não o ver.
  Uma segunda leitura ~800 ms depois resolve (foi o que a app passou a fazer).
- **Origem:** **[LOCAL]** (S10+, 2026-10-22).
