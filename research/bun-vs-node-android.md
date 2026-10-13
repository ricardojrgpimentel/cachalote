# Bun vs Node for embedding the DSH kernel on Android — feasibility report

**Question:** should the Android app that embeds a JavaScript runtime to run the DeepSeek Harness (DSH) kernel switch from Node to Bun?

**Subject under test:** DSH `@deepseek-ai/dsh@0.2.0-rc.2`, launched as `node --expose-internals <dsh>/lib/bin.js web --host 127.0.0.1 --port 3080`; desktop runtime Node **24.18.1** + pnpm 11.7.0; target Samsung Galaxy S10+ (arm64-v8a, Android 16 / LineageOS 23.2, Magisk).
**Bun under test:** `bun-v1.4.3` (released 2026-10-10 — the current latest release at the time of writing).
**Date of research:** late 2026.

**Method / provenance note.** The session's `web_search` backend was unavailable (no API key), so every claim below comes from a *directly fetched authoritative source* (official docs, GitHub API, upstream source) plus **first-hand experiments I ran in this session**:

* upstream source of Bun v1.4.3 (full tarball) and Node v24.x docs (machine-readable JSON),
* the real Bun 1.4.3 binaries (`bun-darwin-aarch64` executed locally; `bun-linux-aarch64-android` downloaded and inspected with `file`/`strings`),
* Node v26.1.0 locally (proxy for the shipped 24.18.1; the snapshot/compile-cache flags used are all present in 24.x — annotated where it matters),
* and — most usefully — **the Android port's own artifacts already in this workspace**: `repo/` (patches, `android-app/`, docs, CHANGES) and `.work/asar/out/dsh` (the extracted DSH payload).

Test-host caveat, stated once and repeated where it matters: the experiments ran on **macOS arm64**, not on the S10+. Absolute milliseconds are not device numbers; the *mechanisms* and the *relative* gains are what transfer. Android storage/CPU is slower, which generally **amplifies** the per-file costs measured here.

---

## Bottom line

### Verdict: **No. Do not move the DSH kernel to Bun.** It is not a port; it is a rewrite of DSH's boot layer against an API that does not exist in Bun.

Three independent, individually-sufficient blockers, in order of severity:

1. **DSH's boot hard-requires Node's *real* internal module loader, and Bun only ships a decorative `--expose-internals`.** `@deepseek-ai/dsh-app-boot` reaches `internal/modules/esm/loader`, `internal/modules/cjs/loader`, `internal/modules/helpers`, `internal/modules/esm/utils`, `internal/modules/esm/resolve` through the native addon `node-addon-require-builtin`, in a code path **with no `try/catch`** — failure is fatal at "host preparation", so even the web profile never starts. Bun's `--expose-internals` is accepted (silently) but resolves `internal/…` only against **Bun's own** ~45 internal files; all five of DSH's are `MODULE_NOT_FOUND` — verified by execution, Bun vs Node (see §2). This is architectural, not a missing feature: Node's internals are JS modules living in Node's own builtin registry; Bun is JavaScriptCore-based with a different registry and no Node module loader to expose.
2. **Bun is not V8, so the entire Node startup/distribution toolbox DSH depends on does not exist.** `node:sea` is documented **🔴 Not implemented** in Bun; `--build-snapshot`/`--snapshot-blob` are silently swallowed; `v8.serialize/deserialize` use JavaScriptCore's wire format; `process.binding` is partial and throws for `fs`/`buffer`/`constants`/`http_parser`/`natives`/`zlib`/`spawn_sync`. Whatever Bun gains in raw startup, it gives back by making the *only* strong cold-start levers (bundle + V8 snapshot + SEA) unavailable.
3. **Bun's Android support is real but unofficial, undocumented and currently broken on stock devices.** Official `bun-linux-aarch64-android` release assets *do* exist (Bionic, `/system/bin/linker64`, 94.7 MB unstripped, vs **45.3 MB** for the port's Termux Node 24.18.0), but the README supports only Linux/macOS/Windows, the docs never mention Android, `bun.sh/install` has **no** Android target (so it hands Termux a glibc binary — open bug #44918, 2026-10-10), and the officially built Android binary is **still killed by `SIGSYS` on `close_range` on every stock Android device** (open #30766, 2026-05-15; fix PR #39775 open and unmerged as of 2026-09-04; the v1.4.3 source tree contains **zero** `SYS_SECCOMP` handling). Termux only ships Bun because its maintainers carry **11 Android patches** (close_range, openat2, fchmodat2, pidfd_open, statx, …).

Switching to Bun would also *not* fix the problem that motivated this question. The three native dependencies are Android-limited for reasons that are platform-specific and Bun-neutral: `sharp` has **no Android prebuild at all** (25 platform packages, none for Android), `node-pty` ships prebuilds only for darwin/win32, and `@vscode/ripgrep` is not even an addon but a binary downloader. `koffi` is the one dep that already has an official Android arm64 prebuild. Bun changes none of that; the port's pure-JS `sharp` shim and `child_process`-based `node-pty` replacement remain the correct calls.

### Top 3 Node-side wins (measured in this session, macOS arm64, 5,000-module / 20 MB synthetic tree, Node v26.1.0)

| # | Optimization | Measured cold start | Payoff | Why |
|---|---|---|---|---|
| 1 | **Bundle the fixed core with esbuild/`bun build --target=node` into as few files as possible** (plugins stay external) | 187.6 ms → **60.0 ms** (−68 %) | **Largest, cheapest, lowest-risk** | Kills most of the per-module `stat`/`open` + resolution work. On Android's slower storage the per-file term is *bigger*, so expect a larger absolute win |
| 2 | **V8 startup snapshot — or better, SEA with `useSnapshot` + `execArgv`** | bundle → **32.0 ms** (snapshot) / **31.3 ms** (SEA); 5.9× vs unbundled | **Largest fixed win, real integration cost** | Pre-runs the bootstrap at build time. Verified compatible with `--expose-internals`, and verified that `require('internal/modules/cjs/loader')` works from a deserialized snapshot/SEA main function. Building the snapshot is no longer experimental as of **Node v24.13.1** — 24.18.1 qualifies |
| 3 | **Keep the engine process warm** (foreground service + persistent daemon, never `re-exec` on UI restart) | not measured here — but the port's own logs show the prize: node "ready" took **17–43 s** of user-visible waiting while the engine was actually up in **~5 s**, and one post-cold-boot run took **160 s / >5 min** | **Best perceived-latency win** | The 28,402-file tree is walked at most once per device boot instead of at every app launch |

**Explicitly *not* worth doing for cold start** (all measured, see §5): `NODE_COMPILE_CACHE` *before* bundling (net loss: 187.6 ms → 247 ms steady-state, 1.12 s cold, 4,712 cache files / 5.3 MiB), and `--max-semi-space-size` / `--max-old-space-size` tuning (no measurable effect: 187.7 / 205.4 / 221.3 ms).

---

## 1. Does Bun officially support Android / Bionic libc?

**Official release builds: yes, they exist.** The `bun-v1.4.3` release assets include:

| Asset | Size | Downloads |
|---|---|---|
| `bun-linux-aarch64-android.zip` | 39,197,597 B | 163 |
| `bun-linux-aarch64-android-profile.zip` | 378,261,973 B | 26 |

(Source: [`GET /repos/oven-sh/bun/releases/latest`](https://api.github.com/repos/oven-sh/bun/releases/latest), asset list, fetched 2026-10.)

I downloaded the Android artifact and inspected it:

```
bun-linux-aarch64-android/bun: ELF 64-bit LSB pie executable, ARM aarch64,
  dynamically linked, interpreter /system/bin/linker64, BuildID[sha1]=537d3d6c…, not stripped
size: 94,675,200 bytes      (auto-release / crash-handler PR #39801 refers to "glibc, musl and android builds")
```

`/system/bin/linker64` is Bionic, so this is a genuine Android/Bionic build, not a glibc binary. Its size is **94.7 MB unstripped vs 45.3 MB** for the Termux Node 24.18.0 binary the port already ships (`.work/node-android/root/data/data/com.termux/files/usr/bin/node`) — i.e. Bun roughly doubles the engine payload, which matters for an APK/bundle.

**Official *support*: no.** The evidence is unambiguous:

* README: *"Bun supports Linux (x64 & arm64), macOS (x64 & Apple Silicon), and Windows (x64 & arm64)."* — Android is not listed ([README.md](https://raw.githubusercontent.com/oven-sh/bun/main/README.md)).
* The installation docs page mentions only macOS/Linux/Windows and never names Android ([bun.sh/docs/installation](https://bun.sh/docs/installation)).
* The docs corpus in the v1.4.3 source mentions Android exactly three times, all incidental: `bun install --os android` is an accepted value, the DNS `"system"` backend is the Android default, and a CSS font-stack table ([docs/](https://github.com/oven-sh/bun/tree/main/docs)).
* **`bun.sh/install` has no Android target.** I fetched the installer script itself: it maps `'Linux aarch64' | 'Linux arm64') target=linux-aarch64`, lexically before the catch-all `'Linux x86_64' | *) target=linux-x64`, and only appends `-musl` for Alpine. On Termux `uname -ms` prints `Linux aarch64`, so the installer fetches the **glibc** build ([bun.sh/install](https://bun.sh/install)).

That last point is an open bug: **[#44918 — "Bug: Bun install.sh incorrectly identifies Android (Termux) as glibc, failing to run"](https://github.com/oven-sh/bun/issues/44918)**, opened 2026-10-10, still open, 0 comments. The symptom is the classic missing glibc loader: `bash: …/.bun/bin/bun: cannot execute: required file not found`. The reporter's workaround is glibc-runner ("a huge hassle"). So: **the Android build exists but the documented install path cannot deliver it.**

### Android-specific defects still open (this is the state of the art)

| Issue | State | Substance |
|---|---|---|
| [#30766](https://github.com/oven-sh/bun/issues/30766) — "Android aarch64 binary killed by SIGSYS (seccomp) on close_range syscall" | **open**, filed 2026-05-15 | Against the *official* `bun-linux-aarch64-android` prebuilt (NDK r27c, API 28), on Android 12/aarch64: `./bun-linux-aarch64-android --version` → `Bad system call`, exit **159**. Root cause per the report: "the zygote's seccomp-bpf filter" traps `close_range`. "Reproduces on every stock Android device." |
| [PR #39775](https://github.com/oven-sh/bun/pull/39775) — "Turn seccomp SIGSYS traps into ENOSYS so the syscall fallbacks run on Android" | **open, unmerged** (updated 2026-09-04) | Installs a `SA_SIGINFO` handler for `si_code == SYS_SECCOMP` and rewrites the return register. I grepped the **v1.4.3 source tree: `SYS_SECCOMP` appears 0 times**, so the released Android binary does not contain this general fix. |
| [#39060](https://github.com/oven-sh/bun/issues/39060) / [PR #39084](https://github.com/oven-sh/bun/pull/39084) / [#44565](https://github.com/oven-sh/bun/issues/44565) / [#44570](https://github.com/oven-sh/bun/issues/44570) | open | Per-syscall Android seccomp work (`openat2`, `fchmodat2`), `CouldntReadCurrentDirectory` on `/sdcard` shared storage, and `/system/bin/am` browser-opener failures. #44570 was validated — but note the reporter's own caveat: *"The patched binary has not been run on an Android device or emulator"*, and it was tested against a **custom Android ARM64 build** with three hand-applied patches plus Clang 23.1.2 / NDK r27c / **API 28**. |
| [#32489](https://github.com/oven-sh/bun/issues/32489) (referenced in source) | — | Android's seccomp policy does not whitelist `epoll_pwait2`; Bun carries a feature-flag escape hatch `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2` (`src/bun_core/env_var.rs`, comment: *"Android app sandbox, some container runtimes"*). |

So Bun's Android story in late 2026 is **a series of individually-patched seccomp accommodations**, not a supported platform. Some are in the source (`openat2`/`fchmodat2` runtime detection, `epoll_pwait2` flag); the general trap-to-errno handler and the installer fix are not.

### Community effort: Termux

Termux *does* package Bun — [`termux-packages/packages/bun`](https://github.com/termux/termux-packages/tree/master/packages/bun) — currently **1.4.3**, built from source with the NDK toolchain and `--abi=android` (`build.sh`: `bun run build:release --abi=android --arch=$_bun_arch --android-ndk="$NDK" --lto=on`), with these patches that tell their own story:

`0001-default-to-symlink-install-backend`, `0002-remove-Werror-and-unsupported-flags`, **`0003-disable-close_range`**, **`0004-disable-openat2`**, **`0005-disable-fchmodat2`**, `0006-set-termux-tmpdir`, `0007-fix-bash-completion-empty-regex`, **`0008-fix-symlink-bin-chmod`**, **`0009-disable-pidfd_open`**, **`0010-disable-statx`**, `0011-android-link-rust-as-machine-code`.

That is the honest measure of "community support": 11 patches, five of which exist purely because Android's seccomp policy traps syscalls Bun issues.

### Same constraint the port already fights

The port's own notes record that **`targetSdk` must stay 28**, because at ≥29 Android mounts the app's private directory `noexec` and `node` fails with `EACCES` (`repo/docs/开发指南.md`, "注意事项" §1; with a workaround: keep the payload outside / rebuild links). Any Android runtime — Bun included — inherits that constraint; it is not a differentiator, but it does mean "just drop in the Bun binary" is not a packaging shortcut either.

---

## 2. Does Bun support `--expose-internals`? (the decisive blocker)

**Short answer: Bun recognises the flag, and implements something that looks like it, but it cannot give DSH what it needs.**

### What DSH actually requires

I read the shipped payload (`.work/asar/out/dsh`). `@deepseek-ai/dsh-app-boot` has one function that reaches into Node's loader, and it is not optional:

```js
// node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:1575-1580
function internalModules() {
	const addon = createRequire(import.meta.url)("node-addon-require-builtin");
	const esmModule  = addon.requireBuiltin("internal/modules/esm/loader");
	const cjsModule  = addon.requireBuiltin("internal/modules/cjs/loader");
	const cjsHelpers = addon.requireBuiltin("internal/modules/helpers");
	const esmUtils   = addon.requireBuiltin("internal/modules/esm/utils");
	const esmResolve = addon.requireBuiltin("internal/modules/esm/resolve");
	const esm = esmModule.getOrInitializeCascadedLoader();
	…
	if (typeof esm.resolveSync !== "function" || typeof Reflect.get(esm, modern ? "getOrCreateModuleJob" : "getModuleJobForImport") !== "function" || … )
		throw new Error("profile resolution: unsupported Node module loader");
```

`@deepseek-ai/dsh@0.2.0-rc.2` depends on `node-addon-require-builtin@^0.1.6`, and `@deepseek-ai/dsh-app-boot@0.2.0-rc.2` depends on it again. The purpose is **profile resolution**: DSH installs an interception on Node's real ESM and CJS resolvers so that a profile's package table routes imports. This is the mechanism HMR and plugin routing are built on, and it happens during **host preparation**, before the web profile exists.

### Why this is fatal rather than degraded — the port's own words

The Android port had to drop the native addon (there is no Android build of `node-addon-require-builtin`, and the payload ships zero `.node`/`.so`). Its replacement shim documents exactly what happens if you don't:

> "…the upstream module is a *Node-API native addon + runtime detection*, loading `.node` via platform-specific optional dependencies (`node-addon-require-builtin-<platform>-<arch>-<abi>`). **This chain necessarily breaks on Android**: there is no Android artifact… And `dsh-app-boot`'s `internalModules()` (host preparation stage) is a **hard dependency with no `try/catch`** — a load failure is immediately fatal:
> `dsh: host preparation failed: No usable native binding found for node-addon-require-builtin-…`
> **As a result even the web profile cannot start.**"
>
> — `repo/dsh-patches/overlay-020/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js` (translated from the Chinese comment)

> "DSH startup **must** have `--expose-internals` (otherwise HMR errors)"
> — `repo/dsh-patches/README.md` (translated)

So the port's fallback is precisely `--expose-internals` + `require(moduleId)` inside the shim, relying on Node's real internals. That fallback is proven working on Node; §2.2 shows it cannot work on Bun.

### 2.1 How Bun's `--expose-internals` is implemented

It is real code, not a stub — but the namespace it exposes is **Bun's own**:

* `src/runtime/cli/Arguments.rs:316 parse_param!("--expose-internals")` and `:1278 if args.flag(b"--expose-internals") { … set_expose_internals_enabled(true) }`
* `src/jsc/ModuleLoader.rs:191-200` — `exposed_internal_tag()` strips the `internal/` prefix and rewrites it to **`internal:<rest>`**, then requires `ResolvedSourceTag::try_from_name()` to succeed:

```rust
/// Node's `--expose-internals`.
pub fn exposed_internal_tag(spec: &[u8]) -> Option<(Vec<u8>, crate::ResolvedSourceTag)> {
    let rest = spec.strip_prefix(b"internal/")?;
    if !bun_resolve_builtins::expose_internals_enabled() { return None; }
    let mut name = …; name.extend_from_slice(b"internal:"); name.extend_from_slice(rest);
    let tag = crate::ResolvedSourceTag::try_from_name(&name)?;
    Some((name, tag))
}
```

`try_from_name` resolves against Bun's builtin registry — i.e. **Bun's own internal modules** (`src/js/internal/*.ts`, ~45 files, scanned by `src/codegen/internal-module-registry-scanner.ts:20-21`, which is also why the `internal/` prefix collides), plus a small hand-written set: `internal:repl`, `internal:repl/await`, `internal:repl/history`, `internal:util/inspect`, `internal:cluster/RoundRobinHandle`, `internal/test/binding` (`src/resolve_builtins/HardcodedModule.rs:170-196`). **Node's loader internals are not in that set and do not exist in Bun.**

Two aggravating details, both verified:

1. **Bun silently ignores unknown flags.** `src/runtime/cli/mod.rs:823`: *"The `is_node` branch of `which()` must clear `bun_clap::streaming::WARN_ON_UNRECOGNIZED_FLAG` so node-mode argv parsing stays silent on unknown flags."* I confirmed empirically: `bun --build-snapshot -e 'console.log("RAN")'`, `bun --snapshot-blob=… `, `bun --experimental-sea-config`, `bun --allow-natives-syntax` — **all print `RAN`**. So `--expose-internals` is *accepted*, and the failure surfaces later, at host preparation, rather than at argv parse. You get a fatal "host preparation failed", not a usage error.
2. **Name collisions return the wrong object instead of throwing.** `internal/errors`, `internal/timers`, `internal/util/inspect` resolve in Bun to Bun's own implementations. A Node-internals-dependent package can therefore get a *different* module with the same name — silently — which is worse than `MODULE_NOT_FOUND` for anything that trusts it.

### 2.2 Empirical result — same script, same flag, Bun vs Node

I ran this against `bun 1.4.3` (darwin-aarch64) and `node v26.1.0`, both with `--expose-internals`:

| `require(id)` | Bun 1.4.3 `--expose-internals` | Node 26.1.0 `--expose-internals` |
|---|---|---|
| `internal/modules/esm/loader` | ❌ `MODULE_NOT_FOUND` | ✅ `createModuleLoader, getOrInitializeCascadedLoader, …` |
| `internal/modules/cjs/loader` | ❌ `MODULE_NOT_FOUND` | ✅ `Module`, `initializeCJS`, `kModuleSource`, … |
| `internal/modules/helpers` | ❌ `MODULE_NOT_FOUND` | ✅ `enableCompileCache`, `getCjsConditions`, … |
| `internal/modules/esm/utils` | ❌ `MODULE_NOT_FOUND` | ✅ `getDefaultConditions`, `getConditionsSet`, … |
| `internal/modules/esm/resolve` | ❌ `MODULE_NOT_FOUND` | ✅ `defaultResolve`, `packageExportsResolve`, … |
| `internal/modules/run_main` | ❌ `MODULE_NOT_FOUND` | ✅ `executeUserEntryPoint`, … |
| `internal/errors` | ✅ (Bun's own) | ✅ (Node's) |
| `internal/util/inspect` | ✅ (Bun's own) | ✅ (Node's) |
| `internal/test/binding` | ✅ | ✅ |
| `internal/repl` | ✅ | ✅ |
| `internal/util/types`, `internal/fs/utils`, `internal/webidl`, `internal/net`, `internal/options`, `internal/http2/util` | ❌ not found | ✅ |

**All five module IDs DSH's `internalModules()` needs are unavailable in Bun.** Consequently `getOrInitializeCascadedLoader()` cannot even be reached, and the shim's fallback (`requireInternal(moduleId)`) throws the same way.

### 2.3 `process.binding` and V8 internals

Bun's own compatibility page says `process.binding` is *partially* implemented and the rest throw ([bun.sh/docs/runtime/nodejs-compat](https://bun.sh/docs/runtime/nodejs-compat) → `process` global). Empirically (Bun 1.4.3, darwin-arm64):

| Binding | Bun | Note |
|---|---|---|
| `uv`, `config`, `tty_wrap`, `util`, `crypto/x509` | ✅ object | |
| `natives`, `fs`, `buffer`, `constants`, `http_parser` | ❌ `TypeError: No default value` | docs claim availability for several of these; the probe disagrees |
| `zlib`, `spawn_sync` | ❌ `Error: process.binding("zlib") is not implemented in Bun…` | explicit "not implemented" |

`process.versions` in Bun reports `{"node":"26.3.0","bun":"1.4.3","boringssl":"…"}` — i.e. Bun *claims* a Node version it does not implement, which is a footgun for capability detection generally.

**Conclusion for §2:** there is nothing to port *to*. Node's `internal/*` is not an API surface Bun forgot; it is Node's own private bootstrap, and Bun's implementation is a same-named approximation over JSC. DSH's boot layer is written against the private surface by design (to intercept the resolver), so DSH-on-Bun is not a port configuration — it is a rewrite of profile resolution and HMR.

---

## 3. Node-API and native addon compatibility

**What Bun claims.** *"Node-API is an interface for building native add-ons to Node.js. Bun implements this interface from scratch, so most existing Node-API extensions work with Bun out of the box."* ([bun.sh/docs/runtime/node-api](https://bun.sh/docs/runtime/node-api)). `require('./x.node')` and `process.dlopen` are supported. Bun reports Node-API version 10 and re-syncs headers from Node 26 (issues [#34146](https://github.com/oven-sh/bun/issues/34146), [#36804](https://github.com/oven-sh/bun/issues/36804)); NAPI is under active development (74 issues with "node-api" in the title; e.g. [#40260](https://github.com/oven-sh/bun/issues/40260) "take the Node-API lifecycle code to safe Rust", [#40225](https://github.com/oven-sh/bun/issues/40225) `ibm_db` async APIs hang on Windows).

**What that means for DSH's closure — the honest per-package picture.** Note that most of these constraints are *Android* constraints, and Bun does not relieve any of them:

| Package | Addon ABI | Android prebuild? | Bun's effect |
|---|---|---|---|
| `node-addon-require-builtin` | Node-API, **but calls Node's internals by design** | none (port ships zero `.node`) | **Blocked.** Even if Bun loaded the .node, `requireBuiltin("internal/modules/…")` returns nothing (§2) |
| `node-pty` **1.1.0** | **Node-API** (`node-addon-api ^7.1.0`); ships `prebuilds/{darwin-arm64,darwin-x64,win32-arm64,win32-x64}` | **no** — install script is `node scripts/prebuild.js \|\| node-gyp rebuild`, so Android needs an NDK build in *any* runtime | Neutral. (Worth correcting a common assumption: modern `node-pty` is no longer NAN/V8-based, so it is *not* structurally impossible under Bun — it is just absent for Android.) The port's `child_process` replacement avoids the NDK build entirely and is the right call |
| `koffi` **3.3.3** | Node-API, prebuilt optional deps | **YES** — `@koromix/koffi-android-arm64` and `@koromix/koffi-android-x64` exist on npm (registry returns HTTP 200) and are listed in koffi's `optionalDependencies`; koffi source has `__BIONIC__ && __ANDROID_API__ < 28` handling | **The one native dep that already works on Android.** Under Bun it would be an NAPI-load experiment; under Node it is solved. Bun's own answer would be `bun:ffi`, which would require rewriting koffi call sites |
| `sharp` **0.35.5** | Node-API + prebuilt libvips | **none** — 25 platform `@img/sharp-*` optional deps, **zero** Android (macOS/Windows/linux-gnu/linux-musl only) | Neutral. Building libvips with the NDK is a large project; the port's pure-JS header parser is the pragmatic answer. Bun has its own image pipeline (`Bun.Image`) but DSH's call sites would still need a shim |
| `@vscode/ripgrep` | not an addon — a downloader for a prebuilt `rg` | no Android package | Neutral; hence the port bundles its own `rg` at `runtime/bin/rg` |

Small-scale sanity check under Bun on the host: `require('koffi')` → OK; `require('node-pty')` → OK (its darwin prebuild). So Bun's NAPI does load real-world NAPI addons — the failure mode for DSH is not "NAPI is broken", it is "the specific addon DSH needs is not an ordinary NAPI addon, and Android has no prebuilds anyway."

---

## 4. Semantic differences that matter for a long-running server

All quotes below are from Bun's own compatibility matrix, which states it tracks Node.js v26: [bun.sh/docs/runtime/nodejs-compat](https://bun.sh/docs/runtime/nodejs-compat) (fetched 2026-10; note Bun 1.4.3 reports `process.versions.node = "26.3.0"`).

| Area | Bun status | Why it matters to DSH |
|---|---|---|
| **`internal/*`, `--expose-internals`** | approximate shim over Bun's own registry | **Fatal** — §2 |
| **`node:sea`** | **"🔴 Not implemented. Use `bun build --compile` instead."** | Removes the single-executable packaging path; also removes the cleanest way to bake `execArgv` (`--expose-internals`, memory flags) into the artifact |
| **`--build-snapshot` / `--snapshot-blob`** | not implemented; flags are silently accepted and ignored (verified: process runs normally) | Removes V8 startup snapshots, the strongest cold-start lever (§5) |
| **`node:v8`** | 🟡 "`setFlagsFromString` ignores the flags it is given. `serialize` and `deserialize` use JavaScriptCore's wire format instead of V8's. Missing `queryObjects`, `Serializer`/`Deserializer`, `takeCoverage`/`stopCoverage`, `promiseHooks`." | Any code that inspects or tunes V8 (heap limits, GC, snapshots, IPC via `v8.serialize`) behaves differently or not at all. `startupSnapshot` is listed as implemented, but there is no V8 to snapshot |
| **`node:vm`** | 🟢 "Fully implemented", incl. `vm.Module`/`SourceTextModule` (no `--experimental-vm-modules` needed), `cachedData`, `microtaskMode` | Superficially good — but it is JSC contexts, so `cachedData` buffers and V8-specific semantics are not interchangeable, and `vm.measureMemory()` "reports whole-heap figures for every context" |
| **`node:worker_threads`** | 🟡 "`Worker` ignores the `resourceLimits` and `trackUnmanagedFds` options… `worker.performance.eventLoopUtilization()` is a stub. Missing `moveMessagePortToContext` and `locks`." Some `execArgv` flags only set `process.execArgv` | `resourceLimits` silently ignored is a real hazard for a memory-constrained device: worker memory caps you set would not apply |
| **`AsyncLocalStorage`** | 🟡 implemented, **but**: "Bun does not propagate `AsyncLocalStorage` context into `MessagePort`, `BroadcastChannel` or `Worker` events"; `createHook`/`executionAsyncId` are stubs, async ids always `0` | Context loss across worker/port boundaries silently breaks request-scoped state — the classic long-running-server bug that is very hard to attribute |
| **`node:http`** | 🟢 "`http.Server` does not extend `net.Server`. Bun ignores `listen(handle)` and the `fd`, `ipv6Only` and `signal` options of `listen()`. `keepAlive`/`keepAliveInitialDelay` on the server are no-ops." | DSH binds `127.0.0.1:3080` and can change ports when 3080 is taken (the port's `ScheduleExecutor`/engine logic already handles port switching). `listen(fd)`/`signal` no-ops and dead `keepAlive` knobs remove the escape hatches you would want on Android |
| **`node:https` / `node:tls`** | 🟡 `https.Server` is not a `tls.Server`; `req.socket` is not a `TLSSocket`; `setSecureContext`/`addContext`/`SNICallback` unsupported; BoringSSL lacks OCSP stapling and cross-process session resumption | Localhost HTTP is unaffected, but TLS-terminating features are |
| **`node:cluster`** | 🟡 HTTP servers in workers each bind their own socket; only Linux `SO_REUSEPORT` load-balances | Android/Bionic `SO_REUSEPORT` behaviour is not guaranteed; multi-process HTTP balancing is effectively out |
| **`node:module`** | 🟡 "Missing `Module#load()`, `registerHooks`, `findPackageJSON`, `stripTypeScriptTypes`… `syncBuiltinESMExports`, `module._load`, `module._pathCache` and `module.register` are **no-ops**" | This is the *public* version of the same gap as §2: DSH's resolver interception has no supported Bun equivalent |
| **`node:sqlite`** | 🟢 "Fully implemented" (caveat: `backup()` blocks the event loop synchronously; `loadExtension()` needs a full SQLite build) | Fine |
| **`node:timers`** | 🟢 "Fully implemented", incl. `node:timers/promises` | Fine |
| **`node:diagnostics_channel`** | 🟡 missing `http.server.*`, `net`, `module`, `console`, `child_process`, **`worker_threads`** channels; subscribers do not keep a `Channel` alive | Observability hooks silently never fire |
| **`node:perf_hooks`** | 🟡 `eventLoopUtilization()` always returns zeros; `nodeTiming` placeholder; no `gc`/`dns`/`resource` entries | No usable event-loop lag metrics for a long-running server |
| **`WebSocket` global** | 🟢 but **`binaryType` defaults to `"nodebuffer"`, whereas Node defaults to `"blob"`** | DSH's live stream is a WebSocket (`ws://127.0.0.1:3080/api/events.mux`). Different default binary type changes payload shapes at the edges |
| **`process.getActiveResourcesInfo()` / `_getActiveHandles()` / `_getActiveRequests()`** | "always return an empty array"; `setSourceMapsEnabled()` no-op; missing `addUncaughtExceptionCaptureCallback` | The canonical "why won't my server exit / what's holding the loop" tools are gone; `addUncaughtExceptionCaptureCallback` is a real robustness loss for a daemon |
| **ESM/CJS interop** | Bun supports `require(esm)` and `require.cache`/`require.extensions`/`module._resolveFilename` overrides; `module.register` is a no-op | The *supported* interop is fine; DSH's boot depends on the *unsupported* internals layer |

Bottom line for §4: several of these are individually survivable, but the set that is missing is exactly the set a *long-running, internals-interception-based server* leans on — loader internals, snapshot/SEA, real `eventLoopUtilization`, `AsyncLocalStorage` propagation across workers, non-no-op `listen` knobs, and uncaught-exception capture.

---

## 5. Real runtime-level wins available on Node for this Android embedding

### 5.1 Method

Synthetic tree designed to mimic the shape of DSH's kernel: **5,000 CommonJS modules / 20 MB**, layered 100-wide with fan-out 2–4 (so every module is loaded, depth ~50, no pathological recursion like a 5,000-deep chain), entry requiring the first layer. Host: **macOS arm64**, Node **v26.1.0**, `bun 1.4.3` used only as the bundler (`bun build --target=node --format=cjs --minify` → 4,712 modules → 636,652 B). Best-of-N wall-clock for the full process (spawn → exit). **These are not S10+ numbers**; they establish mechanism and relative magnitude.

### 5.2 Measurements

| # | Configuration | Best | Median | vs baseline |
|---|---|---|---|---|
| 0 | `node -e ''` (spawn floor) | 23.0 ms | 24.3 ms | — |
| 1 | **unbundled tree, cold** | 187.6 ms | 189.2 ms | 1.00× |
| 2 | unbundled + `NODE_COMPILE_CACHE`, **1st run** | 1121.3 ms | — | **5.98× slower** |
| 3 | unbundled + `NODE_COMPILE_CACHE`, warm | 247.0–249.0 ms | 250.9 ms | **1.32× slower** |
| 4 | **single-file bundle** | **60.0 ms** | 79.2 ms | **3.13× faster** |
| 5 | bundle + `NODE_COMPILE_CACHE`, 1st | 65.8 ms | — | |
| 6 | bundle + `NODE_COMPILE_CACHE`, warm | **51.0 ms** | 52.0 ms | **3.68× faster** |
| 7 | `--max-semi-space-size=16` (unbundled) | 187.7 ms | 233.8 ms | no gain |
| 8 | `--max-semi-space-size=64` (unbundled) | 205.4 ms | 243.4 ms | no gain |
| 9 | `--max-old-space-size=2048` (unbundled) | 221.3 ms | 244.8 ms | no gain |
| 10 | **V8 startup snapshot of the inlined bundle, `--expose-internals`** | **32.0 ms** | 33.2 ms | **5.86× faster** |
| 11 | same snapshot, *without* `--expose-internals` | 31.4 ms | 32.4 ms | (flag-independent) |
| 12 | **SEA (`useSnapshot` + `execArgv`), run with no flags** | **31.3 ms** | 33.1 ms | **6.0× faster** |

Supporting facts: compile-cache run #1 wrote **4,712 cache files / 5,303 KiB**; the snapshot blob was **10,448 KiB** (for this toy heap) and took **0.31 s** to build on this host; the SEA prep blob was 11,427,380 B; the SEA binary printed `SEA-INTERNAL-OK resolveFilename=function`, i.e. **`internal/modules/cjs/loader` was reachable inside the SEA with `--expose-internals` supplied only via `execArgv`**.

### 5.3 Evaluation of each lever, with caveats

**(a) `node --build-snapshot` / `--snapshot-blob` (V8 startup snapshot)** — *largest fixed win, and the best-measured one.* 187.6 → 32.0 ms; note that the snapshot alone (32.0 ms) beats the bundle alone (60.0 ms) and sits only ~9 ms above the bare-Node floor (23.0 ms). Node docs: **"The snapshot building process is no longer experimental" as of v24.13.1** — the shipped 24.18.1 qualifies ([Node CLI docs, `--build-snapshot`](https://nodejs.org/api/cli.html#--build-snapshot)).
Caveats, all verified or documented:
* **The builder refuses to load additional user-land modules.** My first attempt (`require('./bundle.cjs')` from the snapshot script) failed: `Error: Cannot find module './bundle.cjs' … at requireForUserSnapshot (node:internal/main/mksnapshot:107:17)`. Docs agree: *"The snapshot currently only supports loading a single entrypoint during the snapshot building process, which can load built-in modules, but not additional user-land modules."* **⇒ the bundler must inline everything into the one entry file** — snapshot and bundling are a package deal.
* *"only a subset of the built-in modules are well tested to be serializable… When the snapshot builder encounters a built-in module that cannot be serialized, it may crash the snapshot building process."* The documented workaround is to defer those modules with `v8.startupSnapshot.addDeserializeCallback()` / `setDeserializeMainFunction()` — which is exactly what DSH's internals work should do.
* Snapshots are **not portable across Node versions, architectures or OSes** (SEA docs: *"code cache and snapshots can only be loaded on the same platform where they are compiled"*). ⇒ you cannot build the snapshot on a macOS/CI host; it must be produced by the **same aarch64-Android Node binary** that will consume it (on-device, in an emulator, or in an NDK-built container). Budget that as a first-run step; on this host it was 0.31 s, on an S10+ expect seconds to tens of seconds.
* Snapshot blob size scales with the heap you pre-initialise — 10 MiB for my toy tree. For DSH, weigh blob size against the APK payload you already ship.
* **Verified compatible with `--expose-internals`** (rc=0, and internals reachable from the deserialized main function) — this is the detail that matters for DSH, and it works.

**(b) Shipping a single executable (SEA)** — *the cleanest packaging, and it composes with (a).* I built a real SEA end-to-end in this session: `{"main":"main.cjs","output":"sea-prep.blob","disableExperimentalSEAWarning":true,"useSnapshot":true,"useCodeCache":true,"execArgv":["--expose-internals","--max-semi-space-size=16"]}` → `node --experimental-sea-config` → `postject` → `codesign --sign -`; the result **ran in 31.3 ms and successfully required `internal/modules/cjs/loader` with no flags on the command line**. That is the concrete answer to "how do we bake `--expose-internals` into the Android artifact": **`execArgv` in the SEA config.**
Caveats: SEA is still *1 - Active development*; **the embedded main script must be CommonJS** (*"currently only supports running a single embedded script using the CommonJS module system"*), so DSH's ESM `lib/bin.js` needs a CJS wrapper/bundle; `useCodeCache` is redundant when `useSnapshot` is true (Node said so explicitly in my build); cross-platform SEA generation requires `useSnapshot`/`useCodeCache` = `false`; on macOS/Windows the injected binary needs re-signing (irrelevant on Android, but relevant if you build/test on a Mac); `postject` is required as a build-time tool ([SEA docs](https://nodejs.org/api/single-executable-applications.html)).

**(c) Keeping the engine warm / daemonized** — *best perceived-latency win, and the port is already halfway there.* The engine only boots on the user's tap (`引擎为手动启动`), and the port's own history shows the cost of cold work being conflated with engine readiness: *"on a real device node readiness takes **17–43 s**, [the old check] treated 'process alive' as 'ready'"* (`repo/CHANGES.md`, v1.5.x entry) — while a later entry records the engine was actually ready in **~5 s** and the 90 s timeout was a bug in the readiness probe. Worse, a run started immediately after a device cold boot took **160 s, once >5 minutes**, versus **2–3 s** for the same operation once the device settled. Recommendations: keep `node` alive behind a foreground service (the port already has `EngineService`/前台保活), restart only the *engine* on failure, never re-`exec` the binary on a UI reload, and gate "start" on a real port probe rather than process liveness (already learned). The 28,402-file tree should be walked once per device boot, not per app launch. This is worth more than any single-digit-percent runtime tweak.

**(d) Lazy / stripped module loading** — *cheap and already the architecture; just protect it.* DSH's cordis loader resolves plugins lazily and the port already curates what ships. Two concrete asks: (i) make sure nothing in the boot path eagerly requires the whole tree (a single `import * as` over a plugin index would undo every other optimisation here); (ii) exclude plugin packages from the bundle so they stay lazily resolvable. On Android, cold-start cost is dominated by syscall count, so *any* avoided module open is expensive relative to a bundler's inlined copy.

**(e) `--max-semi-space-size` / memory flags on a 7.6 GB device** — *not a cold-start lever; tune it for steady state only, and only with measurements.* Measured: semi-space 16 MiB → 187.7 ms, 64 MiB → 205.4 ms, `--max-old-space-size=2048` → 221.3 ms (baseline 187.6 ms). Node's docs explain the mechanics and the reason not to guess: *"Since the young generation size of the V8 heap is three times the size of the semi-space, an increase of 1 MiB to semi-space … causes the heap size to increase by 3 MiB. The throughput improvement depends on your workload (see #42511)"*, and *"For memory limits up to and including 2 GiB, the default max size of a semi-space will be less than 16 MiB on 64-bit systems"* ([Node CLI docs](https://nodejs.org/api/cli.html#--max-semi-space-size=SIZE)). On Android, be careful that the app's cgroup/JVM limits — not physical 7.6 GB — govern what V8 thinks it has; a bigger semi-space can raise RSS by 3 MiB per MiB and invite the LMK killer. Net: low priority, benchmark-driven, never on faith.

**(f) Bundling the kernel with esbuild into fewer files** — *best return on effort; do this first.* 187.6 → 60.0 ms (−68 %), and it is a build-step change, not a runtime change. I used `bun build --target=node --format=cjs --minify` purely as a host-side esbuild substitute (4,712 modules → 0.64 MB in 83 ms); plain esbuild in the port's toolchain is equivalent.
Caveats: dynamic `require(expr)` / `import(pkg)` cannot be statically bundled — cordis plugin loading must stay `external`; `__dirname`/`__filename`-relative asset resolution changes meaning inside a bundle (DSH resolves config, presets and profile templates from disk — those must stay file-based); the `internal/modules/*` access must survive (it does, including from a snapshot/SEA — verified); source maps/stack traces get worse unless you keep `--sourcemap`; and bundling multiplies with (a)/(b), since snapshots require a single inlined entry anyway.

**(g) `NODE_COMPILE_CACHE` / bytecode caching** — *only after bundling, and pre-warm it at install time.* Measured honestly: on the **unbundled** tree it is a **net loss** (1st run 1.12 s while writing 4,712 files / 5.3 MiB; steady state 247 ms vs 187.6 ms baseline). On the **bundled** single file it is a **net win** (60.0 → 51.0 ms, ~15 %). The interpretation is mechanical: the cache costs one lookup per module, so it loses when modules are tiny and numerous and wins when there are few large compilations. Docs/status: compile cache is **no longer experimental as of Node v24.15.0** (the shipped 24.18.1 qualifies), covers *"a CommonJS, an ECMAScript Module, or a TypeScript module"*, is *"typically only reusable in the same version of Node.js"*, is invalidated when module paths move unless you set `portable: true` / `NODE_COMPILE_CACHE_PORTABLE=1`, and is only **written to disk when the instance is about to exit** unless you call `module.flushCompileCache()` ([Node module docs](https://nodejs.org/api/module.html#module-compile-cache), [`module.enableCompileCache()`](https://nodejs.org/api/module.html#moduleenablecompilecachecachedir)). Practical recipe for the port: enable it *after* bundling, use `portable: true`, and **pre-warm during the existing first-run unpack** (the port already writes 28,402 files there, so it is the natural place to pay the cache-write cost once and `flushCompileCache()` before first user-visible boot).

### 5.4 Priority for cold start of a ~28k-file tree on Android

1. **Bundle the fixed core** (biggest, cheapest, lowest-risk; measured −68 %).
2. **Wrap it in SEA with `useSnapshot: true` and `execArgv: ["--expose-internals", …]`** (measured 5.9–6.0×; also solves flag-baking and single-artifact distribution; pay the snapshot-build cost once, on-device, at install/first-run). Keep anything that must touch Node's internal loaders or that cannot be serialized behind `v8.startupSnapshot.addDeserializeCallback()` / `setDeserializeMainFunction()`.
3. **Keep the process warm** (foreground service; never re-`exec`; probe the port, not the PID).
4. **`NODE_COMPILE_CACHE` + `portable: true`, pre-warmed at install**, only once (1) is in place.
5. Ignore `--max-semi-space-size`/`--max-old-space-size` for cold start; revisit later for steady-state GC with on-device benchmarks.

---

## 6. What I could not verify (limits of this report)

* **No Android device was used in this session.** The host is macOS arm64; I ran Bun 1.4.3 and Node 26.1.0 locally and only *inspected* the aarch64-Android binaries (`file`, `strings`). All §2 findings are API-surface findings and therefore host-independent; the "official Android binary dies with SIGSYS" claim is from upstream issue #30766 (I did not reproduce it) — though the absence of `SYS_SECCOMP` in the v1.4.3 source is my own check.
* **The §5 numbers are macOS proxies**, and they use Node **26.1.0** while the port ships **24.18.1**. Every flag used (`--build-snapshot`, `--snapshot-blob`, `useSnapshot`, `NODE_COMPILE_CACHE`, `--max-semi-space-size`) exists in 24.x, and the "no longer experimental" transitions I cite (v24.13.1, v24.15.0) are *inside* 24.18.1 — but re-measure on the device before committing to a number.
* **Bun NAPI behaviour of `@koromix/koffi-android-arm64` under Bun was not tested** (needs an Android device). If Bun were ever reconsidered for a *different* app, that is the first experiment to run.
* `web_search` was unavailable; discovery relied on the GitHub search API, direct raw-file fetches, the npm registry, and Node's machine-readable docs. Bun issues are recent; several relevant fixes are **open and unmerged**, so this report can go stale quickly — re-check #44918, #30766 and #39775 before citing it later.

## Sources

**Bun — official docs & release artifacts**
* Bun v1.4.3 release + full asset list (incl. `bun-linux-aarch64-android.zip`) — <https://api.github.com/repos/oven-sh/bun/releases/latest> (published 2026-10-10)
* Bun Node.js compatibility matrix (all §4 quotes) — <https://bun.sh/docs/runtime/nodejs-compat> · machine-readable mirror <https://bun.sh/docs/runtime/nodejs-compat.md>
* Bun Node-API page — <https://bun.sh/docs/runtime/node-api>
* Bun installation docs (supported OS list; no Android) — <https://bun.sh/docs/installation>
* Bun README (supported platforms) — <https://raw.githubusercontent.com/oven-sh/bun/main/README.md>
* **`bun.sh/install` installer source** (no Android target; glibc fallback on `Linux aarch64`) — <https://bun.sh/install>
* Bun v1.4.3 source tree (used for `--expose-internals`, seccomp, `process.platform`, internal-module registry citations) — <https://codeload.github.com/oven-sh/bun/tar.gz/refs/tags/bun-v1.4.3>

**Bun — issues / PRs**
* #44918 Bun install.sh misidentifies Android (Termux) as glibc — <https://github.com/oven-sh/bun/issues/44918> (open, 2026-10-10)
* #30766 Android aarch64 binary killed by SIGSYS on `close_range` — <https://github.com/oven-sh/bun/issues/30766> (open, 2026-05-15)
* #39775 Turn seccomp SIGSYS traps into ENOSYS — <https://github.com/oven-sh/bun/pull/39775> (open, unmerged, updated 2026-09-04)
* #39060 / #39084 Android `openat2`/`fchmodat2` SIGSYS on `bun install` — <https://github.com/oven-sh/bun/issues/39060> · <https://github.com/oven-sh/bun/pull/39084>
* #44565 `CouldntReadCurrentDirectory` on `/sdcard` — <https://github.com/oven-sh/bun/issues/44565> (open, 2026-10-04)
* #44570 Android/Termux browser-opener `SecurityException` — <https://github.com/oven-sh/bun/issues/44570> (open, 2026-10-04)
* #39801 crash-handler platform tags, mentions "glibc, musl and android builds" — <https://github.com/oven-sh/bun/pull/39801>
* #32489 epoll_pwait2 on Android (referenced in source) — <https://github.com/oven-sh/bun/issues/32489>
* Node-API work in flight: #34146, #36804, #36089, #40260, #40225 — search `repo:oven-sh/bun node-api in:title`

**Termux**
* `packages/bun/build.sh` (`--abi=android`, NDK, version 1.4.3) — <https://raw.githubusercontent.com/termux/termux-packages/master/packages/bun/build.sh>
* The 11 Android patches — <https://github.com/termux/termux-packages/tree/master/packages/bun>
* Termux `nodejs-lts` 24.18.0-1 aarch64 (the runtime the port ships) — <https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages>

**Node.js — official docs**
* CLI options: `--build-snapshot` / `--snapshot-blob` (single-entrypoint and serializability caveats; "no longer experimental" v24.13.1) — <https://nodejs.org/api/cli.html#--build-snapshot>
* CLI options: `--max-semi-space-size`, `--max-old-space-size` — <https://nodejs.org/api/cli.html#--max-semi-space-size=SIZE>
* Single executable applications (`useSnapshot`, `useCodeCache`, `execArgv`, assets, CJS-only, cross-platform caveat) — <https://nodejs.org/api/single-executable-applications.html>
* Module compile cache (non-experimental v24.15.0; `portable`; flush-on-exit; same-version-only) — <https://nodejs.org/api/module.html#module-compile-cache> · <https://nodejs.org/api/module.html#moduleenablecompilecachecachedir>
* `v8.startupSnapshot` API — <https://nodejs.org/api/v8.html#startup-snapshot-api>
* **BUILDING.md: "Android is not a supported platform."** (plus `android-configure <NDK> <SDK> <arch>`, min SDK 24) — <https://raw.githubusercontent.com/nodejs/node/v24.x/BUILDING.md>
* Node official dist for v24.18.1 (no Android artifacts; `linux-arm64` is glibc) — <https://nodejs.org/dist/v24.18.1/>

**Android platform**
* `targetSdk ≥ 29` ⇒ app-private directory mounted `noexec` (why the port keeps targetSdk 28) — as documented in the port itself, `repo/docs/开发指南.md` §"注意事项" 1; general Android behaviour, cf. Android developer docs on app-private storage and executable files.

**This workspace (primary evidence for DSH's own requirements)**
* `repo/dsh-patches/README.md` — "DSH startup must have `--expose-internals`"
* `repo/dsh-patches/overlay-020/…/node-addon-require-builtin/lib/index.js` — the Android shim and the "hard dependency with no try/catch → even the web profile cannot start" rationale
* `.work/asar/out/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js:1575-1580` — the five required `internal/modules/*` IDs and the 6-interface validation
* `.work/asar/out/dsh/node_modules/@deepseek-ai/dsh/package.json` — DSH `0.2.0-rc.2`, dep on `node-addon-require-builtin@^0.1.6`
* `repo/android-app/src/com/deepseek/harness/MainActivity.java:3888` — the exact launch argv
* `repo/CHANGES.md` — engine readiness 17–43 s vs ~5 s actual; 160 s / >5 min post-cold-boot; 28,402 files / 208 MB; 0 native `.node`/`.so` in the payload
* `.work/fetch-node-android.sh` — how the Termux Node 24.18.0 arm64 runtime is extracted
