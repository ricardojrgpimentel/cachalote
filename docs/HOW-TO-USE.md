# How to use Cachalote

**Cachalote** puts the **DeepSeek Harness** agent inside your Android phone: it reads the screen,
taps, types, runs commands, sees images and — if you want — works on a **second screen** without
touching yours.

This guide is for people who want to **use** the app. To understand how it was built, see the
[`README.md`](../README.md) and [`docs/knowledge-android.md`](knowledge-android.md) (in Portuguese).

> **Portuguese version:** [`COMO-USAR.md`](COMO-USAR.md).

---

## 1. What you need

| | |
|---|---|
| Phone | Android 10 or newer (tested on a Samsung S10+ running LineageOS 23 / Android 16) |
| Space | ~250 MB for the app plus the engine |
| One of three | **root** (Magisk), **Shizuku**, or **nothing** (embedded mode) |
| Key | a DeepSeek API key (`sk-…`) — you type it in the app and it stays on the phone |

**Which one?** With root the agent is more capable (it runs commands as root, with a path guard and
your approval). With Shizuku it runs as *shell*. With nothing, the engine lives **inside the app**
and the Android sandbox is the limit — safer, less capable.

## 2. Install

1. Install the **release** APK ([project releases](https://github.com/ricardojrgpimentel/cachalote/releases)).
2. Open the app. In embedded mode it downloads the engine on first use (~160 MB) and checks the
   `sha256` — if it does not match, it refuses.
3. In **Status**, follow the **First steps** list: the daemon, the channel token, the key and
   accessibility. Each line says what is missing and has the button that fixes it.

## 3. Configure

- **Provider key** (*Key* tab): type your `sk-…` key and tap **Save to daemon**. It is written to
  `home/.env` with mode `600` (root only). **The app does not keep it** — which is also why it is
  **not** included in backups.
- **Accessibility** (*Powers* tab → *Open Settings → Accessibility*): this is what gives the agent
  eyes and fingers on the real screen. Without it, it can only send notifications.
- **Allowed capabilities**: each capability (see the screen, tap, type, keys, screenshots, virtual
  screen) has a switch. By default **only notifications** are on: you decide the rest. Every
  request the agent makes is logged right next to it.
- **Allowed bash areas**: outside those folders the agent must ask for permission **on the phone**,
  and you see the exact command before approving.

## 4. Using it

Type what you want in *Chat* and tap **Send**. You can attach an image, dictate by voice, and watch
the agent's reasoning while it works. Conversations live in **Sessions** (the ✓ marks the active
one; you can resume or close it).

Two things change the experience:

- **Long turns:** you can leave the app. When the agent finishes, a notification appears (only when
  the app is not in the foreground — it is not meant to be noise).
- **Approvals:** when the agent needs to leave the workspace, the app shows a dialog with the exact
  command. Nothing happens without you seeing it.
- **Turn telemetry:** after each turn the conversation shows its steps, tool calls and duration
  (`turn 3 · 1 step · 1.8 s`), and the **Summary** tab has a "Turns and time" card with the last six.
  It comes from the session log — the engine does not send these numbers.
- **Search conversations:** the list has a search box that matches the title and preview, without
  needing accents ("correcao" finds "correção").

## 5. Virtual screen: a second display

In the *Powers* tab, the **Virtual screen** card. Once started, the agent gets a display of its own:
it opens apps there, sees them through screenshots and taps them — **without touching your screen**
and **without needing accessibility**. With the **Live** switch you can watch what it is doing.

Useful for: letting the agent work while you use the phone; testing an app without moving it away;
and keeping screenshots from catching your notifications or conversations.

Note: the virtual screen lives **inside the app**. Close the app and it is gone.

## 6. External tools (MCP)

If you have an MCP server (say, an internal tool), you can hook it up in the **MCP servers** card,
as JSON:

```json
[{"name":"ping","command":"/data/adb/cachalote/runtime/bin/node",
  "args":["/data/local/tmp/my-server.mjs"],
  "env":[{"name":"LD_LIBRARY_PATH","value":"/data/adb/cachalote/runtime/lib"}]}]
```

The field accepts `stdio` servers (with an **absolute** command) and HTTP ones
(`{"name":"x","type":"http","url":"…"}`). Watch the `env`: if the command uses the daemon's Node it
needs `LD_LIBRARY_PATH`, otherwise it dies in the linker and the agent answers that it does not have
the tool — with no error anywhere in the logs.

## 7. Backup, licences and the rest

- **Settings backup**: exports mode, capabilities, bash areas, kernel URL and MCP servers to a file.
  It does **not** include the key or the channel token (on purpose).
- **Third-party licences**: the full texts of what the app redistributes, inside the app.
- **Language**: English and European Portuguese, following the system language.

## 8. When something goes wrong

Open a terminal (or `adb`) and run:

```sh
su -c '/data/adb/cachalote/bin/cachalote doctor'
```

It checks the whole chain — files, engine, key, daemon, `oom_score_adj`, watchdog, ports,
accessibility, notifications, disk and logs — and tells you what is wrong. It exits with code 1 if
there are problems.

Other useful things:

| Situation | What to do |
|---|---|
| "the connection to the engine dropped" | the agent reconnects on its own; if it insists, **Restart engine** in *Status* |
| The app will not connect to the daemon | check in *Status* whether the daemon and the token are fine; `doctor` tells you which piece fails |
| Accessibility turned itself off | this happens on every app reinstall: turn it back on in Settings |
| I want to see what the agent did | *Powers* tab → **Log** (recent requests) and the logs in `/data/adb/cachalote/logs/` |

## 9. Privacy and safety (what you should know)

- **The key stays on the phone.** The app never stores or copies it; the daemon writes it.
- **The agent asks for permission** to go outside the workspace, and everything is logged.
- **With root, confinement is declarative.** The engine runs as root and Android does not enforce
  the DSH sandbox: what stops a command outside the workspace is the approval you give, not the
  operating system. The app says so, in the *Powers* tab.
- **What leaves the phone:** the prompts and the images you attach go to the DeepSeek API (that is
  how the model answers). Nothing else leaves on the app's initiative.
- **The virtual screen helps here**: its screenshots do not catch what is on your screen.

## 10. Honest limitations

- The agent does **not** replace an attentive user: approve with care, especially in root mode.
- **Closing sessions** only works on the active session (that is what the engine allows).
- **Session titles** are derived from your first prompt (the engine does not expose its own yet).
- The virtual screen lives inside the app: close the app and it is gone.
