# DSH kernel 0.2.0-rc.2 — programmatic / headless surfaces

Scope: the shipped macOS Electron payload
`/Applications/DeepSeek Harness.app/Contents/Resources/app.asar` (version **0.2.0-rc.2**, confirmed by
`runtime/primary-runtime/runtime.json` and `runtime/cli/bin/dsh --version`).
All `app.asar:<path>` references below are paths *inside* the asar; extracted copies live in
`.work/asar/out/<path>`. Every claim is either quoted from a shipped file, or from a live probe run on this Mac
(commands and raw output are in §10).

---

## Bottom line

**Yes — a native Android client can drive DSH agents over newline-delimited JSON-RPC 2.0 today, but not over a
socket out of the box: the shipped SDK/ACP servers only speak stdio.** The wire protocol, the server, the profile
composition and the launch CLI all exist and work (verified end-to-end). Two things are missing for Android:

1. **No network transport is shipped.** `dsh-sdk-jsonrpc-server` builds its transport as
   `new JsonRpcLineTransport(input, output)` with `input = config.input ?? process.stdin`,
   `output = config.output ?? process.stdout`
   (`app.asar:dsh/node_modules/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js:257-269`).
   ACP is the same shape with `config.stream ?? ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin))`
   (`app.asar:dsh/node_modules/@deepseek-ai/dsh-acp/lib/index.js:1319`). No TCP/WebSocket listener, no port flag.
2. **No Android/ARM-Linux runtime is shipped.** This payload is darwin/arm64 Electron with darwin-arm64 native
   binaries (`node-pty/prebuilds/darwin-arm64/pty.node`, `@img/sharp-darwin-arm64`, `@koromix/koffi-darwin-arm64`,
   `@deepseek-ai/node-addon-system-darwin-arm64`, `@vscode/ripgrep-darwin-arm64`). Android cannot spawn this kernel
   in-process; the kernel has to run on a host and the Android app has to be a *client*.

**The good news:** both gaps are closable *without touching the kernel*, because the transport override is an
ordinary plugin `config` field and `dsh` accepts ordered `--patch <file>` overlays. I verified this live: a single
`--patch` file repointed `dsh --profile sdk` to a TCP socket and the full handshake
(`initialize` → `shutdown`) round-tripped over `127.0.0.1:45999` (§10.4). So the concrete Android architecture is:

> Android app ⇄ (TCP/loopback, or an SSH/ADB-forwarded port) ⇄ `dsh --profile sdk --patch tcp-bridge.yml` running on
> a host, speaking the NDJSON JSON-RPC protocol documented in §4.

Also missing / thin, in descending order of importance for a client implementer:

- **No published schema artifacts.** The asar contains **zero `.d.ts` files** (`grep -c '\.d\.ts$'` over the
  12 973-entry file list = 0) even though every `package.json` advertises `"types": "lib/types/index.d.ts"`.
  The only machine-readable description of the protocol is the compiled `lib/index.js` plus the package README.
- **No reference client.** Neither the TypeScript client (`dsh-sdk-client`) nor the Python SDK referenced by the
  READMEs is shipped in this asar. You write the client from the protocol description.
- **No error taxonomy.** Every handler failure is `-32603` with a raw JS message; `-32602` invalid-params is never
  used, and malformed input surfaces raw Node `TypeError` text (§4.6).
- **No cancel, no per-session close, no per-prompt result** on the SDK wire; the SDK server is one-process,
  one-transport, initialize-once (`session/prompt` before `initialize` → `-32603 "SDK server is not initialized"`).
- **`initialize` does not validate the model id** against any catalog (observed: `model: "no-such-model"` was
  accepted). The package README claims the opposite; the shipped behavior is looser than documented (§8).
- **`sdk` and `sdk-minimal` share `$DSH_HOME/sessions` with different on-disk encodings** (zstd vs none); the
  second profile to open a shared root fails on the first profile's artifacts (§8, §10.5).
- **ACP** is a *better* protocol for an Android UI than the raw SDK wire (sessions, cancel, per-turn result,
  config options, permissions), but it is a standard-only surface: no DSH presentation data, no plans, todos,
  terminals, elicitation, fork, delete or transcript replay.

---

## 1. What is shipped, and where

| Layer | Path | Notes |
|---|---|---|
| Electron payload | `.../Resources/app.asar` (116 MB) | `dsh/node_modules/…` — the whole DSH kernel; installation manifest is `@deepseek-ai/dsh-desktop-runtime@0.2.0-rc.2` with **287 dependencies** (`app.asar:dsh/package.json`) |
| Unpacked natives | `.../Resources/app.asar.unpacked/dsh/node_modules/` | 1 493 unpacked entries; all `.node` binaries + `rg` |
| Runtime carrier | `.../Resources/runtime/` | `cli/bin/dsh`, `cli/command-manager.js`, `primary-runtime/dependencies/{node,pnpm,python}`, `versions.json` (`node 24.18.1`, `pnpm 11.7.0`) |
| Electron binary | `.../MacOS/DeepSeek Harness` | run as Node via `ELECTRON_RUN_AS_NODE=1`; asar reading comes from Electron |
| Runtime manifest | `app.asar:dsh/desktop-runtime.json` (2.8 MB) | `release.version 0.2.0-rc.2`, `hostProtocolVersion 4`, full `sharedPackages[]` list with paths |

The relevant kernel packages are all present in the asar under `dsh/node_modules/@deepseek-ai/`:
`dsh-base`, `dsh-sdk-app`, `dsh-sdk-minimal`, `dsh-sdk-jsonrpc-server`, `dsh-sdk-protocol`, `dsh-acp`,
`dsh-acp-app`, `dsh-headless`, `dsh-app-boot`, `dsh-web-app`, `dsh`, `dsh-desktop-host`, plus
`@agentclientprotocol/sdk@1.4.0`.

---

## 2. The CLI surface

### 2.1 Launch chain

```
runtime/cli/bin/dsh                      (POSIX sh shim, 583 bytes)
  └─ ELECTRON_RUN_AS_NODE=1 "$resources/../MacOS/DeepSeek Harness" --expose-internals \
       "$resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js" "$@"
          └─ runDesktopCli()  →  runCli() from @deepseek-ai/dsh/lib/bin.js
               └─ parseDshArgs() → runProfile() from @deepseek-ai/dsh/lib/profile-boot.js
```

- Shim: `runtime/cli/bin/dsh` (verbatim: `ELECTRON_RUN_AS_NODE=1 exec "$resources/../MacOS/DeepSeek Harness" --expose-internals "$resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js" "$@"`).
- `runDesktopCli` is `app.asar:dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js:93` (it exists only to
  install the Office-engine ASAR→unpacked resolver hook and, on Windows, a koffi console handler at `:68`).
- **`dsh-desktop-host` is not required for the SDK path.** Verified: invoking
  `@deepseek-ai/dsh/lib/bin.js` directly through the Electron binary with `--expose-internals` answers
  `initialize` over stdio (§10.6). `bin.js` self-executes via `if (import.meta.main) await runCli();`
  (`app.asar:dsh/node_modules/@deepseek-ai/dsh/lib/bin.js:252`).

### 2.2 Launcher flags (owned by `bin.js`, must come *before* app args)

`app.asar:dsh/node_modules/@deepseek-ai/dsh/lib/bin.js:105`:

```js
.option("--profile <name>", "the profile under $DSH_HOME/profiles to boot", selectProfile)
.option("--from-default-profile <name>", "initialize a new custom profile from a shipped profile template")
.option("--patch <path>", "extra patch-list overlay applied after the profile layer (repeatable)", collect)
.option("--dump-config", "print the composed profile tree and exit")
.option("--dump-config-schema", "print JSON Schema for profile entries and patches without mounting")
.option("--dump-default-config", "print the profile tree without its user layer or --patch overlays and exit")
```

- `dsh <name> …` abbreviates `dsh --profile <name> …` (`bin.js:129`).
- `dsh plugin --profile <name> <pnpm-args…>` manages profile dependencies (`bin.js:115-127`).
- `profile === "desktop"` is rejected: `error: profile "desktop" is managed exclusively by the Electron application`
  (`bin.js:35-37`).
- **Everything after the launcher's own flags is forwarded verbatim** to the booted app as `ctx.cmdlineArgs`
  (`bin.js:19-20`, `dsh/lib/profile-boot-BZ2ZjNWi.js:276-280`). So app flags are *last*.
- `--patch` is repeatable and never variadic (`collect`, `bin.js:30`) so it cannot swallow app args.

### 2.3 Verified app-level CLI per profile

| Profile | Command | App flags | Evidence |
|---|---|---|---|
| `sdk` | `dsh --profile sdk` | `-h, --help` only | `dsh-sdk-app/lib/index.js:24-27`; live `--help` output §10.3 |
| `sdk-minimal` | `dsh --profile sdk-minimal` | `-h, --help` only | same bundle, `profile: sdk-minimal` (`dsh-sdk-minimal/cordis.patch.yml:6-8`) |
| `acp` | `dsh --profile acp` | `-h, --help` only | `dsh-acp-app/lib/index.js:20-25` |
| `headless` | `dsh --profile headless "<task>"` | `--json`, `--session-id <id>`, `-h` | `dsh-headless/lib/startup.js:35`; live `--help` §10.3 |
| `web` | `dsh --profile web` | web-app flags (`--port`, `--trusted-host`, …) | `dsh-web-app/cordis.patch.yml:169-174` (`port: !!js ctx.webStartup.port ?? 3080`) |

**There is no port/socket/host flag on `sdk`, `sdk-minimal` or `acp`.** All transport configuration is composition.

### 2.4 Profile templates (the `dsh-app-boot` table)

`app.asar:dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:529-556`, verbatim:

```js
/** The shipped profile templates auto-initialized on first use, by name. */
const PROFILE_TEMPLATES = {
	acp: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-acp-app"] },
	web: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"] },
	headless: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-headless"] },
	sdk: { bundles: ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-sdk-app"] },
	"sdk-minimal": { bundles: ["@deepseek-ai/dsh-sdk-minimal"] }
};
/** Installation-owned bundle tuples normalized to the shipped template. */
const INSTALLATION_OWNED_PROFILE_TUPLES = { headless: [
	"@deepseek-ai/dsh-base",
	"@deepseek-ai/dsh-web-app",
	"@deepseek-ai/dsh-headless"
] };
/** The bundle list a `dsh plugin` init uses for a name with no shipped template. */
const DEFAULT_PROFILE_BUNDLES = ["@deepseek-ai/dsh-base"];
const OPTIONAL_BUNDLES = [
	"@deepseek-ai/dsh-experimental-agent-team-profile",
	"@deepseek-ai/dsh-experimental-voice-input-bundle",
	"@deepseek-ai/dsh-experimental-auto-review",
	"@deepseek-ai/dsh-experimental-schedule-bundle"
];
```

Every template bundle **is** present in the installation (they are direct dependencies of
`@deepseek-ai/dsh-desktop-runtime`, `app.asar:dsh/package.json`), so `resolveBundleDir` resolves them from the
installation anchor first (`dsh-app-boot/lib/index.js:901-907`). Nothing has to be hand-materialized.

---

## 3. Profiles, `DSH_HOME`, config files, credentials, models

### 3.1 `DSH_HOME`

`app.asar:dsh/node_modules/@deepseek-ai/dsh-home-paths/lib/index.js:73-76`:

```js
function resolveDshHome(configured, env = process.env) {
	const fromEnv = env[DSH_HOME_ENV];
	return resolve(expandHomePath(configured ?? (fromEnv !== void 0 && fromEnv.trim().length > 0 ? fromEnv : defaultDshHome())));
}
```

Precedence: explicit configured path → `$DSH_HOME` (blank/whitespace treated as unset) → `~/.dsh`.
`dshHomePath(...)` joins onto the resolved home and is what profile patches use for `sessions`, `storages`, etc.

### 3.2 Profile directory — auto-created, not hand-made

A profile is `$DSH_HOME/profiles/<name>/` (`PROFILES_DIR = "profiles"`, `dsh-app-boot/lib/index.js:485`;
`resolveProfileDir` at `:524`). On first use `loadProfile` calls `initProfile(dir, template.bundles)`
(`dsh-app-boot/lib/index.js:857` and `:973`), which writes
(`dsh-app-boot/lib/index.js:576-592`):

| File | Content |
|---|---|
| `package.json` | `{name: "dsh-profile-<name>", private: true, dependencies: {}, dsh: {profile: {bundles: [...]}}}` |
| `cordis.patch.yml` | the user patch layer, initialized to `[]` |
| `pnpm-workspace.yaml` | `packages: [.]`, `nodeLinker: hoisted`, `autoInstallPeers: false` |
| `cordis.yml` | rewritten on every boot to the empty root entry list (`profile-boot-BZ2ZjNWi.js:121-125`, `:189`, `:207`) |

Observed on this machine for `--profile sdk` (`.work/dshhome/profiles/sdk/`): exactly those four files.
Custom profiles: `dsh <new> --from-default-profile <template>` copies only the template's bundle list and refuses
shipped names or existing directories (`profile-boot-BZ2ZjNWi.js:139-169`).

**Patch-layer order** (last wins per row; a patch replaces the whole `config`, it does not deep-merge):

```
bundle layers in dsh.profile.bundles order
  → $DSH_HOME/profiles/<name>/cordis.patch.yml   (per-profile user layer)
  → $DSH_HOME/cordis.patch.yml                   (home-level layer, outranks the per-profile one)
  → --patch <file> … (argv order)
  → launcher telemetry switch
```
(`dsh/lib/profile-boot-BZ2ZjNWi.js:205-212`, `:224-290`; `homePatchPath()` at `:115-117`.)

### 3.3 Environment / `.env`

`loadLayeredEnv("dsh")` (`dsh-app-boot/lib/index.js:3433-3458`) reads `<invocation cwd>/.env` then
`$DSH_HOME/.env` (skipped when cwd *is* the home) and fills only variables not already set, i.e. the inherited
environment wins. `PATH`, `DSH_*`, `XDG_*`-style bootstrap variables are **rejected from `.env` files**
(`readEnvLayer`, `dsh-app-boot/lib/index.js:3399-3417`); the four proxy names are accepted only from the
home-level file.

### 3.4 Credentials

`app.asar:dsh/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js:14-20`, verbatim:

```text
inherited process environment      (read-only, wins)
> $DSH_HOME/.credentials.yaml      (provider-managed, writable)
> <invocation cwd>/.env            (read-only fallback)
> $DSH_HOME/.env                   (read-only fallback)
```

- The file is `$DSH_HOME/.credentials.yaml` (`CREDENTIALS_FILENAME`, same file `:49-58`).
- `dsh-base` mounts `authorization`, `credentials` (`dsh-credentials-local`) and the DeepSeek adapters
  (`dsh-base/cordis.patch.yml:109-128`, `:525-529`). The base patch comment at `:521-524` says no key or endpoint
  is inlined; both resolve per request.
- `sdk-minimal` has no managed credentials; it pins `apiKeyEnv: DEEPSEEK_API_KEY`
  (`dsh-sdk-minimal/cordis.patch.yml:20-26`) and reads the env var only.

### 3.5 Model / provider selection

| Surface | Where selection comes from |
|---|---|
| SDK (`sdk`, `sdk-minimal`) | `initialize` params `provider`, `model`, optional non-empty `reasoningEffort`, optional positive safe-integer `maxTokens`; validated by `llm.resolveCallConfig` before being stored (`dsh-sdk-jsonrpc-server/lib/index.js:110-137`). Omitting `reasoningEffort` leaves the model default. |
| ACP | Plugin `config.provider` + `config.model` (defaults for new sessions), then per-session `session/set_config_option` with `model` / `reasoning_effort` (`dsh-acp/lib/index.js:1063-1067`, `:1141-1165`). |
| Base default (any profile) | `agent-default-model` row: `provider: deepseek-official`, `model: deepseek-flash` (`dsh-base/cordis.patch.yml:82-86`). |
| Adapter fallback | SDK `initialize` mounts `@deepseek-ai/dsh-llm-deepseek-api-key` only when no adapter owns the provider, and only for `deepseek-official`; any other unowned provider fails with `no adapter registered for provider "X"` (`dsh-sdk-jsonrpc-server/lib/index.js:117-120`). |

Catalog observed live from ACP `session/new` `configOptions`: `deepseek-official` ×
`deepseek-v4-flash`, `deepseek-flash` ("DeepSeek-V41-Flash"), `deepseek-v4-pro`; `reasoning_effort` ∈
`off | low | high | max` (current `high`). The SDK adapter's own default catalog is only
`deepseek-flash` and `deepseek-v4-pro` (`app.asar:dsh/node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js:41-53`).

### 3.6 Workspace / cwd and sessions

- SDK: `initialize.params.cwd` (required in practice) → `resolve(params.cwd)` → stored and passed as
  `meta: { cwd: this.cwd }` to `ctx.agents.create` (`dsh-sdk-jsonrpc-server/lib/index.js:113`, `:218-231`).
  Base `fs-sandbox` defaults its cwd to `process.cwd()` (`dsh-base/cordis.patch.yml:516-519`).
- ACP: `session/new` requires an **absolute** `cwd`; `additionalDirectories` is rejected
  (`dsh-acp/lib/index.js:1410-1413`).
- Sessions persist under `$DSH_HOME/sessions` (`dsh-base/cordis.patch.yml:130-133`; `sdk-minimal` same root at
  `dsh-sdk-minimal/cordis.patch.yml:154-158`). Observed layout:
  `$DSH_HOME/sessions/--<cwd with / → ->--/<sessionId>/session.v4.jsonl[.zstd]` + `session.lock`.
- Base uses the default (zstd) encoding; `sdk-minimal` pins `compression: none`. See §8 for the resulting
  incompatibility.

---

## 4. SDK JSON-RPC protocol (the raw server)

Source of truth: `@deepseek-ai/dsh-sdk-protocol` (transport + types) and
`@deepseek-ai/dsh-sdk-jsonrpc-server` (methods), both compiled JS only.

### 4.1 Transport — newline-delimited JSON, no headers

`app.asar:dsh/node_modules/@deepseek-ai/dsh-sdk-protocol/lib/index.js:234-236`:

```js
write(message) {
	this.output.write(`${JSON.stringify(message)}\n`);
}
```

- **One JSON-RPC 2.0 object per `\n`-terminated line.** No `Content-Length` framing (that is LSP, not this).
- UTF-8 via `StringDecoder`; a partial trailing line is buffered (`drainLines`, `:157-166`).
- Empty lines are skipped; unparseable lines are **silently ignored** (`handleLine`, `:175-182`) — a client
  gets no parse-error frame, unlike ACP.
- Frame classification (`:186-194`): `id` + `method` → request; `id` alone → response; `method` alone →
  notification.
- `params` is normalized: arrays and scalars collapse to `{}` (`objectParams`, `:243-246`).
- Request ids: the client picks; the server echoes verbatim. (The client-side transport generates
  `req_<uuid-without-dashes>`.)
- `close()` detaches listeners and rejects pending requests without destroying the streams (`:57-62`);
  `flush()` writes an empty string and waits for the callback (`:145-152`).
- **stdout is the protocol**; diagnostics must go to stderr. There is no stdout logger in the shipped SDK
  composition.

### 4.2 Methods

`app.asar:dsh/node_modules/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js:195-202`:

```js
async handleRequest(method, params) {
	switch (method) {
		case "initialize": return this.initialize(params);
		case "session/prompt": return this.prompt(params);
		case "shutdown": return this.shutdown();
		default: throw new Error(`unknown DeepSeek Harness SDK runtime method: ${method}`);
	}
}
```

| Direction | Method | Params → Result |
|---|---|---|
| client→server | `initialize` | `{cwd, provider, model, reasoningEffort?, maxTokens?}` → `{serverInfo: {name: "deepseek-harness-sdk-runtime", version: "0.0.1"}}` |
| client→server | `session/prompt` | `{sessionId, contentBlocks: [...]}` → `{messageId}` (durable **enqueue** receipt only) |
| client→server | `shutdown` | (none) → `{}` |
| server→client | `session.event` | `{sessionId, event}` — **every** session in the runtime, unfiltered |
| server→client | `session.status` | `{sessionId, status: "running" \| "idle"}` |
| server→client | `subagent.started` | `{parentSessionId, childSessionId}` |
| server→client | `subagent.finished` | `{provider, agentId, parentSessionId, childSessionId, status: "ok"\|"error", stopReason, lastAssistantMessage?}` (in-process runs only, gated on `info.local`) |

Notification emission sites: `dsh-sdk-jsonrpc-server/lib/index.js:68-103`. `serverInfo.name` is the only
wire-stable identity; `version` is `0.0.1` and unvalidated (`dsh-sdk-protocol/README.md:113`).

### 4.3 `initialize` semantics

- `cwd` is `resolve(params.cwd)` — omitting it throws a raw Node error (§4.6). Send an absolute path.
- `reasoningEffort`, if present, must be a non-empty string (`:111`); `maxTokens`, if present, must be a positive
  safe integer (`:112`).
- The server waits for `ctx.get("loader")?.await()` **before** handling `initialize`
  (`dsh-sdk-jsonrpc-server/lib/index.js:280`), so async siblings (MCP discovery) are visible to the first prompt.
- Re-initialization is unsupported; `this.initialized = true` is sticky.
- `maxTokens` becomes the per-agent output cap for SDK-created agents and their in-process descendants.

### 4.4 `session/prompt` semantics

- One agent per `sessionId`, created lazily on first use via `ctx.agents.create({sessionId, meta: {cwd}, agentOptions: {provider, model, reasoningEffort?, maxTokens?}})`
  (`:203-231`). Concurrent creations for the same id are de-duplicated by `sessionCreations`.
- Content: ordinary durable content blocks plus inline images
  `{type: "image", data: <base64>, mimeType: "image/png"}`; images are admitted through the composition's
  attachment store first and replaced by content-addressed references (`:16-33`). A missing attachment store →
  `SDK image prompt requires an attachment store`.
- The result `{messageId}` is a **UUID from `createUserMessage`** — it identifies inbox admission only. There is
  no assistant-message id, no `turn/end`, no per-prompt completion signal. Clients must observe `session.event`
  themselves.
- The returned `messageId` equals the `id` of the `user/message` event's message (verified in §10.1:
  `f9f4a954-0a1e-459f-94b9-7e17ecf5d4c8` appeared in both the result and the `agent/inbox/spliced` /
  `user/message` events).
- `session/prompt` rejects before a successful `initialize`; requests may dispatch concurrently.

### 4.5 `session.event` payload — the real streaming channel

Live observed shape (verbatim from the probe, §10.1):

```json
{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"session-probe-0001",
 "event":{"type":"turn/end","seq":16,"time":1791886028307,
          "data":{"turn":1,"reason":{"kind":"error","error":{...}}}}}}
```

Every event carries `type`, a monotonic `seq`, `time` (ms epoch) and `data`. Types observed in one turn:

```
permission/preset, sandbox/mode, approval/policy, agent/inbox/spliced,
turn/start, step/start, system/message, user/message, request/header,
request/context, session/title, assistant/attempt, step/end, turn/end
```

`system/message` carries the full assembled system prompt; `request/header` carries the tool roster and the
resolved `config` (`provider`, `model`, `maxTokens`, `reasoningEffort`); `request/context` carries
`contextWindow` and `systemPromptUpdate`. Other event types exist across the tree (`assistant/chunk`,
`assistant/live-chunk`, `tool/result`, `tool/ptc-dispatch`, `compaction/*`, `goal/change`, `image/offload`,
`subagent/catalog`, `feedback/*`, `session/end-seed`, …) — the authoritative union is `SessionEvent` in
`@deepseek-ai/dsh-session`; **the wire ships no schema for it**, only the compiled types.

### 4.6 Errors

`dsh-sdk-protocol/lib/index.js:196-212`:

- `-32601 method not found` — **only** when no request handler is installed at all.
- `-32603` — every handler failure, with `error.message` = `String(err)`.

Because the SDK server always installs a handler, unknown methods return `-32603`
`"unknown DeepSeek Harness SDK runtime method: does/not/exist"`, not `-32601`. Observed error messages (§10.7):

| Input | Wire error |
|---|---|
| `session/prompt` before `initialize` | `-32603 "SDK server is not initialized"` |
| unknown method | `-32603 "unknown DeepSeek Harness SDK runtime method: does/not/exist"` |
| `initialize` without `cwd` | `-32603 "The \"paths[0]\" argument must be of type string. Received undefined"` |
| `initialize` with unknown provider | `-32603 "no adapter registered for provider \"nope\""` |
| `initialize` with `reasoningEffort: ""` | `-32603 "initialize reasoningEffort must be a non-empty string"` |
| `session/prompt` without `contentBlocks` | `-32603 "Cannot read properties of undefined (reading 'filter')"` |

### 4.7 Lifecycle

- `shutdown` → flushes the response, disposes the **root** context (agents, subscriptions, persistence to
  quiescence), then `exit(0)` (`dsh-sdk-jsonrpc-server/lib/index.js:271-286`).
- stdin EOF and SIGINT/SIGTERM are owned by the app bin / launcher: `dsh-sdk-app` calls
  `exitOnStdinEnd(ctx, "sdk-app.stdin")` (`dsh-sdk-app/lib/index.js:36-42`), and the launcher installs bounded
  5 s shutdown with escalation (`dsh/lib/profile-boot-BZ2ZjNWi.js:17`, `:250-268`).
- Unloading only the plugin stops serving without exiting the process.
- `dsh --profile sdk --help` prints help and exits **without claiming stdio** (the JSON-RPC row injects the
  `sdkAppStartup` service, which is only published by a successful parse).

---

## 5. ACP surface

Package: `@deepseek-ai/dsh-acp@0.2.0-rc.2`, built on `@agentclientprotocol/sdk@1.4.0`
(`app.asar:dsh/node_modules/@deepseek-ai/dsh-acp/package.json`), profile `acp` =
`dsh-base` + `dsh-acp-app` (`dsh-app-boot/lib/index.js:530`).

### 5.1 Transport

`app.asar:dsh/node_modules/@deepseek-ai/dsh-acp/lib/index.js:1319-1320`:

```js
const stream = config.stream ?? ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin));
const connection = agent({ name: "deepseek-harness-acp" })
```

`ndJsonStream` is the SDK's line framing (`@agentclientprotocol/sdk/dist/stream.js`: `JSON.stringify(message) + "\n"`,
`LineBuffer` split on `0x0a`) — same framing as the SDK wire, but the readable/writable carry **parsed objects**
(`AnyMessage`), not bytes. `PROTOCOL_VERSION = 1`
(`@agentclientprotocol/sdk/dist/schema/index.js:51`). Unlike the raw SDK transport, a parse failure produces a
JSON-RPC parse-error response.

### 5.2 Methods

Registered at `dsh-acp/lib/index.js:1320-1323`:

| Client call | Handler |
|---|---|
| `initialize` | `:1141-1165` |
| `authenticate` | immediate success, `{}` (no auth required) |
| `session/new` | `:1168` |
| `session/list` | paginated, newest-first, opaque base64url keyset cursor |
| `session/resume` | restores a persisted inactive session, no replay |
| `session/close` | quiescent cancel + drain + flush + scoped disposal |
| `session/set_config_option` | `model` / `reasoning_effort` |
| `session/prompt` | one in-flight prompt per session |
| `session/cancel` (notification) + `$/cancel_request` | prompt-owned cancellation |
| server→client `session/update` | committed messages/thoughts, tool lifecycle, config changes, context usage |
| server→client `session/request_permission` | one-shot `allow-once` / `reject-once` |

Advertised capabilities (live, §10.2):
`{protocolVersion: 1, agentInfo: {name: "deepseek-harness-acp", version: "0.0.1"},
agentCapabilities: {mcpCapabilities: {http: true}, promptCapabilities: {image: <route-dependent>, audio: false,
embeddedContext: false}, sessionCapabilities: {close: {}, list: {}, resume: {}}}, authMethods: []}`.

### 5.3 How it differs from the raw JSON-RPC server

| | `dsh-sdk-jsonrpc-server` | `dsh-acp` |
|---|---|---|
| Protocol | DSH-private, 3 methods + 4 notifications | standard ACP v1 (`@agentclientprotocol/sdk`), ~10 client calls |
| Framing | NDJSON | NDJSON (same) |
| Multiple sessions per connection | yes (`sessionId` in params) | yes, independent, with lifecycle |
| Session create/close | implicit on first `session/prompt`; **no close** | explicit `session/new`, `session/close`, `session/list`, `session/resume` |
| Cancellation | **none** | `session/cancel`, `$/cancel_request` |
| Prompt result | enqueue receipt `{messageId}` | settles on Agent idle + ordered update delivery; typed `stopReason` |
| Streaming | raw `session.event` (every durable fact, unfiltered, all sessions) | semantic `session/update` only (no raw provider deltas, no DSH presentation data) |
| Model selection | `initialize` only | `initialize` defaults + `session/set_config_option`, options enumerated from the live catalog |
| Permissions | none on the wire | `session/request_permission` with one-shot choices |
| MCP | via composition only | per-session `mcpServers` (stdio + Streamable HTTP) |
| Images | inline base64 `{type:"image",data,mimeType}` | ACP image content, route- and store-gated |
| Workspace | `initialize.cwd` | `session/new.cwd` (absolute only; `additionalDirectories` rejected) |
| Not supported | cancel, close, per-prompt result, session list | `session/load`, delete, fork, modes, commands, plans, terminals, client fs ops, elicitation, additional directories |
| Config surface | `maxTokensAsSuccess` (+ test-only `input`/`output`/`exit`) | `provider`, `model`, `sessionListPageSize` (+ test-only `stream`) |
| Auth | none | none |

For an Android UI, **ACP is the better target**: it has the session lifecycle, cancellation and per-turn result
the raw SDK wire lacks, and it is a documented public protocol.

---

## 6. Headless (not JSON-RPC, but the third programmatic surface)

`dsh --profile headless "<task>"` runs one task and exits; `--json` projects the run as NDJSON on stdout
(`dsh-headless/lib/startup.js:35`, `dsh-headless/README.md:56-58`). Event vocabulary observed live (§10.8):

```
{"type":"session","sessionId":…,"cwd":…}
{"type":"status","phase":"turn_start"|"step_start"|"step_end"|"turn_end",…}
{"type":"final","text":""}
```
(plus `text`, `thinking`, `tool_call`, `tool_result`, `error` per the README). Exit 0 = completed `turn/end`;
anything else = 1. It **must** run through the `dsh` launcher because only the launcher owns process exit
(`dsh-headless/README.md:141`). It is a one-shot CLI, not a session protocol — not a candidate transport for an
Android app.

The Web surface (`dsh --profile web`) is a *fourth* transport: `dsh-host-webserver` binds
`127.0.0.1:3080` by default and `dsh-client-connection` mounts a gateway under `/api` with a fetch/SSE browser
client (`dsh-web-app/cordis.patch.yml:169-174`, `:207-215`). It is the shipped frontend's private protocol
(`dsh-api-*` controllers) with no published schema; reusing it from Android means reverse-engineering the
frontend bundle. Not recommended.

---

## 7. Native-module dependencies touched by these profiles

All binaries below are **darwin-arm64 only** in this payload.

| Module | Used by | Import style | In `sdk` | In `sdk-minimal` |
|---|---|---|---|---|
| `@deepseek-ai/node-addon-system` (Node-API: `flock`, Linux Landlock) | `dsh-session-persistence-jsonl` — **static** `import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock"` (`…/dsh-session-persistence-jsonl/lib/index.js:11`); also `dsh-sandbox-local`, `cordis-plugin-loader`, `dsh-app-boot` | static | **yes** (session locking, `session.lock`) | **yes** |
| `sharp` `^0.35.3` | `dsh-attachment-local` (image decode/resize) | lazy: `createLazyRequire("sharp", import.meta.url)` (`…/dsh-attachment-local/lib/index.js:118`) | loaded on first image | not mounted |
| `node-pty` `1.2.0-beta.15` | `dsh-subprocess-local` PTY mode (lazy require, `…/dsh-subprocess-local/lib/index.js` "Local node-pty terminal-process implementation"); `dsh-terminal-bash` | lazy | mounted but **not loaded** unless a PTY subprocess is requested | **loaded** — `dsh-terminal` + `dsh-terminal-bash` are mounted (`dsh-sdk-minimal/cordis.patch.yml:50-62`) |
| `@xterm/headless` | `dsh-terminal-bash` | lazy (`…/dsh-terminal-bash/lib/index.js:243`) | no | on terminal use |
| `@vscode/ripgrep` `^1.18.0` | `dsh-tool-fs-search` | dynamic `await import("@vscode/ripgrep")` (`…/dsh-tool-fs-search/lib/index.js:125`) | **loaded on first search** | not mounted |
| `koffi` `3.1.1` | `dsh-fs-local` (Windows ACL), `dsh-session-persistence-jsonl` (Windows `kernel32`), `dsh-subprocess-local` Windows Job runner, `dsh-win32-process`, `dsh-host-directory-picker-native`, `dsh-desktop-host` Windows console handler | dynamic, **Windows-gated** (`await import("koffi")).default.load("kernel32.dll")`) | not loaded on macOS/Linux | not loaded |
| `sherpa-onnx` | voice-input bundle | — | no | no |

Binary presence (unpacked): `…/node-pty/prebuilds/darwin-arm64/pty.node`,
`…/@img/sharp-darwin-arm64/lib/sharp-darwin-arm64-0.35.5.node`,
`…/@koromix/koffi-darwin-arm64/darwin_arm64/koffi.node`,
`…/@deepseek-ai/node-addon-system-darwin-arm64/bin/system.node`,
`…/@vscode/ripgrep-darwin-arm64/bin/rg`.

**Answer to the question asked:** the `sdk` profile **cannot fully avoid native modules** — it always loads the
`node-addon-system` Node-API addon for session locking (base mounts `session-persistence-jsonl`), and it loads
`sharp` on the first image prompt and `ripgrep` on the first filesystem search. It *does* avoid `node-pty` and
`koffi` in normal operation. `sdk-minimal` additionally loads `node-pty` at terminal use and has no `sharp`/
ripgrep rows. Neither profile is native-free, which matters if you were hoping to run this on a platform without
prebuilt binaries.

---

## 8. Missing / undocumented / partial surface — honest list

1. **No socket transport.** Only stdio. Closing the gap requires a `--patch` overlay (verified working, §10.4) or
   a separate process that owns the pty and forwards bytes.
2. **No Android runtime.** darwin-arm64 payload; Electron + Node 24. The kernel must run on a host.
3. **Zero shipped `.d.ts` / JSON Schema for the SDK or ACP wire.** `package.json` `types` fields point at files
   that do not exist in the asar. The `--dump-config-schema` command exists but only describes *profile entry
   configs*, not the JSON-RPC payloads.
4. **No reference client in the payload.** `dsh-sdk-client` and the Python SDK named in the READMEs
   (`dsh-sdk-protocol/README.md:93`, `:96`) are not shipped.
5. **No cancel / close / per-prompt result on the SDK wire.** `dsh-sdk-jsonrpc-server/README.md:125-126` says so
   explicitly: "The wire has no per-session close or prompt-cancel method — SDK-created agents remain live until
   process shutdown."
6. **No error taxonomy.** All failures are `-32603`; malformed input leaks raw Node messages (§4.6).
7. **`initialize` model validation is looser than documented.** `model: "no-such-model"` was accepted
   (`initialize` returned `serverInfo`), while `dsh-sdk-jsonrpc-server/README.md:48` claims the server "validates
   the provider/model route … before it stores them".
8. **No protocol version negotiation.** `serverInfo.version` is the fixed string `0.0.1` and clients are not
   expected to validate it (`dsh-sdk-protocol/README.md:113`).
9. **`session.event` is unfiltered across all sessions** in the runtime — an SDK client must filter by
   `params.sessionId` itself; there is no subscription call.
10. **Cross-profile session-store incompatibility.** `sdk` writes zstd JSONL (`dsh-base/cordis.patch.yml:130-133`,
    default encoding) while `sdk-minimal` pins `compression: none`
    (`dsh-sdk-minimal/cordis.patch.yml:154-158`). Sharing one `$DSH_HOME/sessions` root across the two profiles
    fails (§10.5). Use a separate `DSH_HOME` per profile, or patch both to the same encoding.
11. **stdout purity is deployment-enforced.** A profile or `--patch` that loads a stdout logger corrupts the
    channel; nothing in the kernel vetoes it (`dsh-sdk-jsonrpc-server/README.md:127`).
12. **`acp-app` pins `model: deepseek-v4-flash`** (`dsh-acp-app/cordis.patch.yml:20-21`) even though the SDK
    adapter's own default catalog lists only `deepseek-flash` / `deepseek-v4-pro`. It works because the ACP
    session enumerates the live catalog (which includes `deepseek-v4-flash`), but the two catalogs disagree — a
    trap if you resolve models yourself.
13. **`--dump-config-schema` imports every composed module** to read its schema
    (`dsh-app-boot/README.md:54`) — do not run it against an untrusted profile.
14. **ACP `config.stream` override is unverified.** It is the same override shape as the SDK's `input`/`output`
    (which I verified), but the ACP stream is object-level (`Stream<AnyMessage>`), so a socket bridge must parse
    lines itself. I did not run that variant.
15. **`desktop` profile is reserved** and rejected by the CLI (`bin.js:35-37`).

---

## 9. Recommended shape for the Android decision

Two viable designs, both using only the shipped kernel plus a patch file:

**A. ACP over a bridged socket (recommended).**
Host runs `dsh --profile acp --patch <bridge>.yml`; the bridge exposes ACP NDJSON over TCP/loopback (or a Unix
socket forwarded by `adb forward`). Android implements an ACP v1 client: `initialize` → `session/new` (absolute
cwd) → `session/set_config_option` → `session/prompt` (settles with `stopReason`) → `session/update` stream →
`session/cancel` → `session/close`. You get cancellation, session list/resume and permissions for free, and the
protocol is public, so client libraries/idioms exist outside DSH.

**B. Raw SDK wire over a bridged socket (smaller surface, fewer guarantees).**
Host runs `dsh --profile sdk --patch <bridge>.yml` (or `sdk-minimal` for a standalone tree with one shell tool).
Android sends `initialize` → `session/prompt`, and reconstructs turns from `session.event` (`turn/start` …
`turn/end`, `assistant/attempt`, `tool/result`) filtered by `sessionId`. No cancel, no close, no per-prompt
result — abandon a turn by killing the process.

Reference bridge (verified working, `--profile sdk`): `.work/tcp.patch.yml` — one `--patch` overlay that replaces
the transport with a `PassThrough`/`Writable` pair backed by `net.createServer` on `127.0.0.1:45999`. The same
technique, with object-level streams, applies to ACP's `config.stream`.

---

## 10. Evidence: exact commands and observed results

Environment: macOS, `<repo>`. `DSH=/Applications/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh`,
`export DSH_HOME=<repo>/.work/dshhome`.

### 10.1 SDK handshake + streaming (`.work/rpc-probe.mjs`, `--profile sdk`)

```
>> {"jsonrpc":"2.0","id":"probe_1","method":"initialize","params":{"cwd":"…/probe-workspace","provider":"deepseek-official","model":"deepseek-flash"}}
<< {"jsonrpc":"2.0","id":"probe_1","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime","version":"0.0.1"}}}
>> {"jsonrpc":"2.0","id":"probe_2","method":"session/prompt","params":{"sessionId":"session-probe-0001","contentBlocks":[{"type":"text","text":"…"}]}}
<< {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"session-probe-0001","event":{"type":"permission/preset","seq":0,…}}}
<< {"jsonrpc":"2.0","method":"session.event",…"event":{"type":"turn/start","seq":4,…}}
<< {"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"session-probe-0001","status":"running"}}
<< {"jsonrpc":"2.0","id":"probe_2","result":{"messageId":"f9f4a954-0a1e-459f-94b9-7e17ecf5d4c8"}}
<< {"jsonrpc":"2.0","method":"session.event",…"event":{"type":"request/header","seq":11,"data":{"header":{"config":{"provider":"deepseek-official","model":"deepseek-flash","maxTokens":256000,"reasoningEffort":"high"},…}}}}
<< {"jsonrpc":"2.0","method":"session.event",…"event":{"type":"assistant/attempt","seq":14,…"finish":{"reason":{"kind":"error","failure":{"message":"Authentication Fails, …401","code":"AUTH"}}}}}
<< {"jsonrpc":"2.0","method":"session.event",…"event":{"type":"turn/end","seq":16,…}}
<< {"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"session-probe-0001","status":"idle"}}
>> {"jsonrpc":"2.0","id":"probe_3","method":"shutdown"}
<< {"jsonrpc":"2.0","id":"probe_3","result":{}}
== child exit code=0
```

The only failure was the deliberately fake `DEEPSEEK_API_KEY` — the protocol path is fully exercised.

### 10.2 ACP handshake (`.work/acp-probe.mjs`, `--profile acp`)

```
>> initialize {"protocolVersion":1,"clientCapabilities":{}}
<< {"result":{"protocolVersion":1,"agentInfo":{"name":"deepseek-harness-acp","version":"0.0.1"},
     "agentCapabilities":{"mcpCapabilities":{"http":true},"promptCapabilities":{"image":false,…},
     "sessionCapabilities":{"close":{},"list":{},"resume":{}}},"authMethods":[]}}
>> session/new {"cwd":"…/probe-workspace","mcpServers":[]}
<< {"result":{"sessionId":"f4c64214-…","configOptions":[{"id":"model","category":"model","type":"select",
     "currentValue":"[\"deepseek-official\",\"deepseek-v4-flash\"]","options":[…deepseek-flash, deepseek-v4-pro…]},
     {"id":"reasoning_effort","category":"thought_level","type":"select","currentValue":"high","options":[…off, low, high, max…]}]}}
>> session/prompt …
<< {"error":{"code":-32603,"message":"Internal error: turn failed: Authentication Fails, …"}}
>> session/close {"sessionId":"f4c64214-…"}
<< {"result":{}}
```

### 10.3 Help surfaces

```
$ "$DSH" --version
0.2.0-rc.2
$ "$DSH" --profile headless --help
Usage: dsh --profile headless [options] [task...]
Options: --json, --session-id <id>, -h, --help
$ "$DSH" --profile sdk --help
Usage: dsh --profile sdk [options]
Serve DeepSeek Harness SDK clients over stdio JSON-RPC.
Options: -h, --help
```

### 10.4 TCP transport injected with one `--patch` overlay (`.work/tcp.patch.yml`)

```yaml
- id: sdk-jsonrpc-server
  config:
    maxTokensAsSuccess: true
    input: !!js (globalThis.__dshTcpBridge ??= (() => { … net.createServer … })(), globalThis.__dshTcpBridge.input)
    output: !!js (globalThis.__dshTcpBridge ??= (() => { … net.createServer … })(), globalThis.__dshTcpBridge.output)
```

```
$ "$DSH" --profile sdk --patch /Users/…/.work/tcp.patch.yml   # stderr: "bridge-listening"
$ node tcp-client.mjs
== connected over TCP
<< {"jsonrpc":"2.0","id":"tcp_1","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime","version":"0.0.1"}}}
<< {"jsonrpc":"2.0","id":"tcp_2","result":{}}
```
Server stdout stayed empty (frames went to the socket). Note the YAML constraint discovered here: a plain `!!js`
scalar cannot contain `": "` or a leading quote, so the expression must be a single line with no colon-space.

### 10.5 `sdk-minimal` on a clean home vs a shared home

```
$ PROBE_DSH_HOME=…/.work/dshhome-min node rpc-probe.mjs sdk-minimal
PROMPT RESULT: {"jsonrpc":"2.0","id":"probe_2","result":{"messageId":"2fe66ab4-…"}}     # OK

$ PROBE_DSH_HOME=…/.work/dshhome node rpc-probe.mjs sdk-minimal
PROMPT RESULT: {"jsonrpc":"2.0","id":"probe_2","error":{"code":-32603,
 "message":"session artifact \"…/f4c64214-…/session.v4.jsonl.zstd\" uses .jsonl.zstd, but this backend is
 configured for compression \"none\"; use a separate root or select the matching compression mode"}}
```

### 10.6 Direct `bin.js` launch (no `dsh-desktop-host`)

```
$ echo '{"jsonrpc":"2.0","id":"direct_1","method":"initialize","params":{"cwd":"…","provider":"deepseek-official","model":"deepseek-flash"}}' \
  | ELECTRON_RUN_AS_NODE=1 "/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness" --expose-internals \
    "/Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" --profile sdk
{"jsonrpc":"2.0","id":"direct_1","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime","version":"0.0.1"}}}
```

### 10.7 Negative paths (`.work/neg-probe.mjs`)

See the table in §4.6 — all six cases observed verbatim.

### 10.8 Headless `--json`

```
$ "$DSH" --profile headless --json "say pong"
{"type":"session","sessionId":"session-843fd1cc-…","cwd":"…/.work"}
{"type":"status","phase":"turn_start","turn":1}
{"type":"status","phase":"step_start","turn":1,"step":1}
{"type":"status","phase":"step_end","turn":1,"step":1}
{"type":"status","phase":"turn_end","turn":1,"reason":{"kind":"error","error":{"message":"llm-deepseek: no API key for provider route \"deepseek-official\"…","code":"MISSING_CREDENTIAL"}}}
{"type":"final","text":""}
```

### 10.9 On-disk artifacts after the probes

```
.work/dshhome/.anonymous-user-id
.work/dshhome/profiles/sdk/{package.json,cordis.patch.yml,cordis.yml,pnpm-workspace.yaml}
.work/dshhome/sessions/--Users-…-probe-workspace--/session-probe-0001/session.v4.jsonl.zstd + session.lock
.work/dshhome/sessions/--Users-…-probe-workspace--/f4c64214-…/session.v4.jsonl.zstd + session.lock
.work/dshhome/storages/session_projcache/sessions/session-probe-0001.json
```

---

## 11. Reproduction kit left on disk

| Path | Purpose |
|---|---|
| `.work/asar/asar.py` | selective asar reader (`list`, `grep`, `get`, `cat`) |
| `.work/asar/filelist.txt` | all 12 973 asar entries with size + packed/unpacked flag |
| `.work/asar/out/dsh/node_modules/@deepseek-ai/…` | extracted copies of the packages cited above |
| `.work/rpc-probe.mjs` | SDK wire probe (`initialize` → `session/prompt` → `shutdown`) |
| `.work/acp-probe.mjs` | ACP probe (`initialize` → `session/new` → `session/prompt` → `session/close`) |
| `.work/neg-probe.mjs` | negative-path / error-code probe |
| `.work/tcp.patch.yml`, `.work/tcp-client.mjs` | verified stdio→TCP transport bridge |
| `.work/sdk-default.yml` | `dsh --profile sdk --dump-default-config` output (405 lines, shows the composed tree with per-layer `# ==` provenance comments) |
