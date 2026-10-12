# Hermes

A DeepSeek-powered agent harness that runs **on the phone**, not in a browser tab.
Native Android (Kotlin + Jetpack Compose), built and installed over `adb`.

The agent loop, the tool executor and the model client all live in the app. When
the phone is next to the computer, the same client can instead point at a Hermes
Agent gateway on the desktop — the protocol is identical, only the base URL and
key change.

## Status

| Piece | State |
|---|---|
| Gradle/AGP toolchain, reproducible build, `adb install` | working |
| DeepSeek client (SSE streaming, tool calls, reasoning, usage) | working, protocol verified against the live API |
| Agent loop (multi-step tool use, step cap, cancellation) | working, verified end to end on device |
| Approval gate (per-call confirmation, remember-forever allowlist) | working, verified on device |
| Tools: `shell`, `read_file`, `write_file`, `list_dir` | working |
| Tools: `device_info`, `notify`, `clipboard_read/write`, `open_url`, `list_apps` | working, verified on device |
| Chat UI (streaming markdown, reasoning disclosure, tool cards) | working, verified on device |
| Settings (key, base URL, model, temperature, diagnostics) | working |
| Conversations on disk (list, open, delete) | working |
| Share target (text + image) and text-selection menu | working, verified via `am start` |
| Vision (gallery, camera, thumbnails, multimodal wire) | working, verified on device |
| Voice input (STT) and spoken replies (TTS) | working, verified on device |
| Scheduled / unattended runs | in progress |
| Desktop bridge | running as an isolated gateway; UI selector not built |

### Verified on the device, not just compiled

- A full agent turn: streamed, called `shell` twice, paused for approval twice, ran `uname -a`
  and `getprop`, and correctly reported the device as a Galaxy S24 Ultra.
- An image shared from another app → thumbnail → downscale/base64 → multimodal request → the
  model described the picture accurately.
- Voice input transcribed ambient speech into the composer with locale `pt-BR`; TTS loaded the
  `pt_BR` language pack and played.
- `notify` produced a real `NotificationRecord` on the `hermes_agent` channel.


## Build and install

```bash
cd hermes-android
./gw :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.hermes.harness.debug/com.hermes.harness.MainActivity
```

`./gw` pins `JAVA_HOME`, `GRADLE_USER_HOME`, `ANDROID_HOME` and `ANDROID_USER_HOME`
to paths inside this workspace, so a build never writes to `~/.gradle` or
`~/.android`.

The debug keystore is deliberately **not** committed (see `.gitignore`). AGP cannot
create one, because it would need to write to `~/.android`, which the build sandbox
denies. On a fresh clone, regenerate it before the first build:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore keystore/debug.jks \
  -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```


### Toolchain

AGP 9.4.1 · Gradle 9.8.1 · Kotlin 2.4.21 (AGP's built-in Kotlin — the
`org.jetbrains.kotlin.android` plugin is an error since AGP 9) · Compose BOM
2026.06.01 · compileSdk 36 · minSdk 26.

### Why the dependency versions are pinned low

AndroidX publishes AARs whose `minCompileSdk` runs ahead of the publicly
available SDK platform: Compose 1.12.x, `core-ktx` 1.19.x, `lifecycle` 2.11.x,
`navigation-compose` 2.10.x and OkHttp 5.5.x all demand **compileSdk 37**, and
Google's SDK repository only ships `platforms;android-36`.

So every version in `gradle/libs.versions.toml` is the newest one whose
`aar-metadata.properties` still allows 36. Re-check with:

```bash
/Users/leonardozanattaribeiro/Desktop/Code/.toolchains/probe-one.sh \
  "google|androidx.core|core-ktx" "google|androidx.compose.ui|ui-android"
```

Bump `compileSdk` the moment `platforms;android-37` appears, then raise these.

## DeepSeek API notes (verified live)

`GET /v1/models` currently returns two:

| id | name | context | input |
|---|---|---|---|
| `deepseek-flash` | DeepSeek-V4.1-Flash | 1,048,576 | text **+ image** |
| `deepseek-v4-pro` | DeepSeek-V4-Pro | 1,048,576 | text |

`deepseek-chat` no longer exists. `deepseek-flash` accepts image input, which is
what makes the camera/vision feature viable.

Streaming shape the parser relies on (confirmed against the live endpoint):

- `choices[0].delta.reasoning_content` streams chain-of-thought separately from
  `delta.content` (either may be `null` on a given chunk).
- `choices[0].delta.tool_calls[]` sends `index`, `id`, `type` and
  `function.name` **once**, then only `index` + `function.arguments` fragments.
  Fragments must be concatenated per index.
- The terminal chunk carries `finish_reason: "tool_calls"` and `usage`
  (`prompt_tokens` / `completion_tokens` / `total_tokens`, plus extra fields the
  client ignores).

## Architecture

```
ui/            ChatScreen, SettingsScreen, ScheduleScreen, ChatViewModel
               (the approval gate and the voice poller live in the view model)
agent/         Model, AgentLoop, AgentEvent, Approval, SystemPrompt
llm/           DeepSeekClient (OkHttp + SSE), Wire DTOs (text + multimodal parts)
tools/         Tool interface, registry, shell/file tools, device tools
media/         MediaResolver — content URI to downscaled base64 data URL, with an LRU
voice/         VoiceController — SpeechRecognizer + TextToSpeech, main-thread marshalled
schedule/      ScheduledTask, ScheduledTaskStore, ScheduledAgentWorker, ScheduleScheduler
store/         SettingsRepository (DataStore), ConversationStore (JSON files)
notifications/ HermesNotifications — the single channel everything posts to
ui/theme/      HermesPalette + HermesTheme
ui/markdown/   pure, total markdown parser + Compose renderer
```

The agent loop owns a private transcript copy and never mutates the caller's
history; every change is published as an `AgentEvent`, so `ChatViewModel` stays
the single source of truth for persisted state.

`ApprovalGate` is the seam worth keeping: the loop suspends on it, and the
view model completes it from the UI. That same pause can later be answered from
a notification or from the desktop without touching the loop.

## Desktop bridge (not yet enabled)

Hermes Agent on the desktop can expose an OpenAI-compatible server:

```bash
echo "API_SERVER_KEY=$(openssl rand -hex 32)" >> ~/.hermes/.env
hermes gateway run          # listens on 127.0.0.1:8642
adb reverse tcp:8642 tcp:8642
```

Then set the app's base URL to `http://127.0.0.1:8642/v1` with the same key
(there is a "Usar gateway local" shortcut in Settings).

**Security note:** `API_SERVER_KEY` grants unscoped operator authority over the
desktop agent, and the gateway binds loopback-only by default. Enable it
deliberately, and keep `adb reverse` (USB) rather than exposing the port on the
network.

## Known gaps

Ordered by how much they matter.

- **The delegated (PC-mode) round trip is verified.** With the gateway key in
  place, a message sent from the phone in "No PC" mode reached the desktop agent,
  which ran `uname -s` **on the Mac** and answered "É Mac: Darwin, não Android" —
  the ~29k input tokens of that turn are the desktop agent's prompt, not the
  phone's. Two things had to be right: the URL/model/key swap, and the credential
  split (`apiKey` vs `gatewayApiKey`). Note the feature depends on the desktop
  gateway and `adb reverse` both being up; the drawer chip switches back.
- **Remote PC approvals are built and verified end to end.** The desktop agent
  pauses, `approval.request` reaches the phone over the SSE stream the app already
  consumes, the dialog offers only the choices the event advertises, and the
  decision POSTs back to `/v1/runs/{run_id}/approval` — confirmed by the protected
  file appearing on the Mac afterwards. See
  [docs/remote-approvals.md](docs/remote-approvals.md), which also keeps the record
  of the first attempt that *looked* successful without ever tripping the gate.
  Concurrency, `all: true` and a live `approval.responded` frame remain unverified.
- **SAF is not built.** Calendar read, contacts search, intents and shell cover the
  rest of the "deep Android integration" scope; only the Storage Access Framework
  document picker is missing.
- **Calendar read and contacts search are done. SAF is not.** Both follow the
  same permission contract: with the permission missing they return an error
  naming the exact permission to grant, never a silent no-op.
  `calendar_read` was verified with real events (pt-BR formatting, all-day
  handling, calendar origin) and it volunteered to open Android settings via
  `open_url` and flagged an overlapping-event conflict unprompted. `contacts_search`
  requires a `query` on purpose — dumping the address book into the model context
  would be both useless and a privacy problem — and was verified with a
  non-existent name so that no real contact data had to leave the device.
- **The completion notification for a scheduled run is confirmed.** A forced
  unattended run persisted `lastStatus: "ok"` and left a real
  `NotificationRecord` on the `hermes_agent` channel, so the worker's
  `HermesNotifications.post` call is exercised, not just written.
- **The API keys are stored in app-private DataStore, not behind the Keystore.**
  Fine for a private build; harden before distributing.
- **`release` builds are unsigned and unminified.**
- **No automated tests.** The markdown parser was fuzz-validated out of band and
  the protocol shapes were checked against the live API, but the agent loop,
  tools and scheduler have only been exercised by hand.
- **`local.properties` is machine-specific and gitignored.**

## Verifying on a device

`adb` automation of this UI is fragile, and it produced misleading results three
times while building: taps landing in another app (One UI opens Google search
from BACK on the home screen), a `dumpsys` grep that matched a different app, and
`input text` racing the soft keyboard. Confirm each step against `mCurrentFocus`
before trusting it, and prefer reading state the app wrote to disk
(`files/schedule/tasks.json`, `files/datastore/hermes_settings.preferences_pb`)
over inferring it from a screenshot.

