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
