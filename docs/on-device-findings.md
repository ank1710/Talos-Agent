# On-device inference: what actually happens on the S24 Ultra

First round where the LiteRT-LM native layer was exercised on real hardware. Before
this, everything about it was inferred from the Kotlin/JNI wrapper source.

Device: Galaxy S24 Ultra (SM-S928B / SM8650, Snapdragon 8 Gen 3), Android 16, 10.8 GB
total RAM. Models pushed to `getExternalFilesDir("models")`.

## What works

- **The engine loads.** Logcat shows `liblitertlm_jni.so` loading from the APK and the
  runtime reaching tokenisation. This is the first proof that the native path works at
  all on this device, and that the `uses-native-library` manifest entries are right.
- **The capability probe tells the truth, and the app acts on it.** Measured on device:
  > RAM livre 3,1 GB de 10,8 GB; o Android considera o app descartável abaixo de 564 MB
  > livres, então o espaço real de trabalho é 2,6 GB. Guardando metade disso para a cache
  > KV, as ativações e o resto do app, sobram 1,3 GB para os pesos — a maior classe que
  > cabe com folga é 1-2B. Backup: CPU. Com 2,5 GB livres não há folga para o driver
  > OpenCL (seriam precisos ~3,5 GB). Armazenamento livre: 42,8 GB. ABIs: arm64-v8a. SoC: SM8650.

  With that, `ModelStore.default()` picked the **271 MB** bundle over the **1.9 GB** one.
  The app chose the model that fits *this* phone, from measured numbers — which is the
  behaviour the feature was asked for.
- **Native errors reach the user intact.** The failing turn showed:
  > Falha ao gerar resposta no modelo local: Status Code: 3. Message: Input token ids are
  > too long. Exceeding the maximum number of tokens allowed: 2184 >= 1024

## The blocker, and the fix that works

`mobile-actions_q8_ekv1024.litertlm` is the only model here built for tool calling, and
the `ekv1024` in its name is its context window: **1024 tokens**. Talos sent a system
prompt plus the JSON Schema of **13 tools**, which tokenises to **~2190**. It did not fit
by more than 2x, and the run died with `Input token ids are too long ... 2184 >= 1024`.

**Fixed by sending fewer tools.** A local run now gets a curated set of three, chosen so
one still covers a lot:

| Tool | Why it earns its tokens |
|---|---|
| `device_info` | structured device state, tiny schema |
| `shell` | the general instrument — reaches files, apps and system state, which is what makes the separate file tools redundant |
| `notify` | how the agent reports back when the app is not on screen |

Subagents are excluded on-device too: the spawner's schema is large and a child would
spend the budget twice. Cloud and desktop runs keep all 13 plus subagents.

**Verified end to end on the device, offline, with no API key and no network:**

> User: *Qual a bateria do aparelho?*
> Tool: `device_info` — 31ms — `Bateria: 80% (parada), 30.9°C / Armazenamento: 45.6 GB livres de 238.2 GB`
> Answer: *A bateria do aparelho é 80% (parada), com 30.9°C*

Model → tool call → execution → result fed back → final answer, all inside the phone.
The usage strip reads `entrada 0 · saída 0 · total 1024 tokens`, which is the KV-cache
occupancy: LiteRT exposes no prompt/completion split, and 1024 is the window being full.

The earlier conclusion stands — a tool-calling model with a 1k context cannot host a
13-tool agent — but it *can* host a 3-tool one, and that turns out to be enough for the
phone-shaped jobs.

## Open problems found this round

1. **A message in on-device mode produced neither a reply nor an error** and the turn
   stayed pending. Not reproduced deliberately; it may be a very slow load, a hang, or
   the settings flow settling mid-turn. Needs a clean repro before it can be called a bug.
2. **JNI load-order quirk.** Logcat shows
   `No implementation found for void ...NativeLibraryLoader.nativeCheckLoaded()`
   *followed by* a successful `liblitertlm_jni.so` load. Something calls into JNI before
   the library is loaded; it recovers, but the order should be understood.
3. **The user cannot override the model choice.** On entering on-device mode the app
   repoints the model field to `ModelStore.default(recommendation)`. Editing the field
   afterwards is overwritten on the next start. So someone who wants to try the 1.9 GB
   model — accepting the risk — cannot. A "force this model" affordance is missing.
4. **`supportsVision` is false for `.litertlm` names**, so attaching an image in on-device
   mode shows the "switch to deepseek-flash" hint. Honest, but it means a multimodal local
   model would need this to become capability-driven rather than name-driven.

## Not verified

The 1.9 GB Gemma 4 E2B was **never successfully loaded**: two attempts to select it were
undone (once by a corrupted text field from `adb input text` racing the IME, once by the
app repointing the model on start). Whether it fits in RAM despite the probe's verdict is
still an open question. Weights may be mmapped rather than fully resident, so the probe
could be pessimistic — that is exactly the kind of claim only a real run settles.

Nothing here ran through the desktop gateway or WorkManager.
