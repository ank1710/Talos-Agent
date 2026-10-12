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
   **Addressed** — see "Model override and a real speed benchmark" below.
4. **`supportsVision` is false for `.litertlm` names**, so attaching an image in on-device
   mode shows the "switch to deepseek-flash" hint. Honest, but it means a multimodal local
   model would need this to become capability-driven rather than name-driven.

## Model override and a real speed benchmark

Added after that round, and **not yet run on a device** — nothing below is a measurement.

The probe answers "does this fit in RAM?" from memory, and it is deliberately pessimistic
(weights may be a mapping the kernel can evict). Nothing answered "is this actually
usable?", so Settings → "Onde roda o modelo" now lists every installed bundle and lets the
user select one — including one above the measured budget, with a warning on its row — and
measure it with `ModelBenchmark.kt`: release, load, generate a fixed prompt with a fixed
output cap, report, then put back whatever was loaded before.

`getTokenCount()` is still the only token count LiteRT-LM exposes through this seam, and it
is KV-cache occupancy with no prompt/completion split, so the speed figure is published as
a **ceiling** ("no máximo"), with its basis printed next to it in the UI; a character-based
estimate is the fallback when the occupancy is not consistent with the text that arrived,
and no figure at all when neither is honest. `benchmark()` returns that basis as data, so
the number cannot reach the screen without it.

Two things worth knowing for whoever runs this first:

- The exact prefill/decode timers do exist (`Conversation.getBenchmarkInfo()`,
  `@ExperimentalApi`), but the native library logs *"Benchmark is not enabled. Please make
  sure the BenchmarkParams is set in the EngineSettings."* — they need
  `ExperimentalFlags.enableBenchmark = true` before `Engine.initialize()`. That flag was
  deliberately **not** switched on: it changes engine behaviour for ordinary chat
  (it appears to wait for weight conversion) and could not be verified on hardware, which
  is exactly the kind of change that should not ride along with a measurement feature.
- The old frustration should be gone: entering on-device mode no longer overwrites a model
  the user picked from the list, so the 1.9 GB Gemma can now be selected, measured, and
  left in place.

## Not verified

The 1.9 GB Gemma 4 E2B was **never successfully loaded**: two attempts to select it were
undone (once by a corrupted text field from `adb input text` racing the IME, once by the
app repointing the model on start). Whether it fits in RAM despite the probe's verdict is
still an open question. Weights may be mmapped rather than fully resident, so the probe
could be pessimistic — that is exactly the kind of claim only a real run settles.

Nothing here ran through the desktop gateway or WorkManager.

## Measured benchmark (first real run)

Run on the device from Ajustes → "Onde roda o modelo" → "Medir velocidade". Fixed prompt,
96-token cap, temperature 0. The figure is a **ceiling** and the UI says so next to it:
it is KV occupancy at the end of the conversation (preface + prompt + answer) divided by
generation time, because LiteRT exposes no prompt/completion split.

| Model | Load | Generate | Backend | Speed | Outcome |
|---|---|---|---|---|---|
| `mobile-actions_q8_ekv1024.litertlm` (271 MB) | **290 ms** | 116 chars in **1.6 s** | CPU | **78.8 tok/s** (ceiling) | OK |
| `gemma-4-E2B-it-gpu.litertlm` (1.9 GB) | 217 ms | 0 chars | — | not measurable | **FAILED_TO_LOAD** |

So the working model loads in about a third of a second and answers a short prompt in
under two seconds, on CPU and with no network. That is a usable local agent, not a demo.

### The large model's failure is NOT about RAM — and the probe was not wrong

217 ms and this from the native library:

```
Failed to create engine: NOT_FOUND:
  └ llm_litert_compiled_model_executor_factory.cc:91
  └ tf_lite_prefill_decode not found in the model.
```

The bundle does not contain the `tf_lite_prefill_decode` section the **CPU** executor
needs. `mobile-actions_q8_ekv1024.litertlm` does contain it — the string is visible in its
header.

**The mistake was mine, in model selection:** `gemma-4-E2B-it-gpu.litertlm` is the
GPU-specific artifact, and the probe had chosen the **CPU** backend (not enough free RAM
for the OpenCL driver). A GPU-only bundle cannot serve a CPU backend.

So this run does **not** answer whether a 1.9 GB model fits in this phone's memory. It
answers a different and cheaper question: that bundle was never loadable here at all.
The generic `gemma-4-E2B-it.litertlm` (2.4 GB) is the artifact that would test the RAM
question, and it is the next experiment.

This is exactly what a real measurement buys over a theoretical estimate: the probe
predicted "won't fit"; the benchmark showed "won't load, for an unrelated reason, in
217 ms".

## The probe is too conservative, and the measurement proved it

Following the wrong-variant discovery, the **generic** `gemma-4-E2B-it.litertlm` (2.4 GB)
was pushed and benchmarked. It **loaded and ran**, on CPU, with no low-memory kill
anywhere in logcat — even though the same screen labelled it
*"Acima do orçamento medido (1,5 GB)"*.

| Model | Size | Load | First token | Speed (ceiling) | Probe said | Reality |
|---|---|---|---|---|---|---|
| `mobile-actions_q8_ekv1024` | 271 MB | 290 ms | 1.6 s | **78.8 tok/s** | fits | ran |
| `gemma-4-E2B-it` (generic) | **2.4 GB** | **3.1 s** | **947 ms** | **35.5 tok/s** | **does not fit** | **ran** |
| `gemma-4-E2B-it-gpu` | 1.9 GB | 217 ms | — | — | does not fit | failed: no CPU section |

**The probe's model of memory is wrong, and the UI already says why:** weights are a
memory-mapped file, so the OS pages them in on demand instead of requiring the whole
thing resident. `DeviceCapability` compares the file size against half the measured
headroom, which is a sound *safety* heuristic and a bad *feasibility* predictor.

The honest conclusion is to keep the probe as a warning and let the benchmark be the
verdict — which is the arrangement that now exists. What must NOT happen is the probe
silently preventing a model from being tried; that is why the override was added.

Speed, as expected, scales inversely with size: 78.8 tok/s at 271 MB against 35.5 tok/s
at 2.4 GB, both on CPU. Note 35.5 tok/s is still comfortably interactive.

### Consequence not yet addressed

The reduced three-tool set is chosen by **mode** (`ModelMode.ON_DEVICE`), not by the
model's context window. That was right for `ekv1024` at 1024 tokens. But the Gemma
bundles are general models with far larger windows, so they are almost certainly being
denied tools they could hold. The choice should follow the model's actual window (or be
measured), not the fact that inference happens on the phone.

## The full toolset works offline, on the large model

Sending the whole 13-tool catalogue on-device was gated by MODE rather than by the
model, which was right for the 1024-token bundle and wrong for everything else. It is
now gated by the context window the bundle declares (`ekv<N>` in the filename), with
`null` meaning "not declared" and therefore "assume there is room": a model that is too
small fails loudly with both numbers, whereas a model that had room and was handed a
crippled toolset just quietly underperforms, and that second mistake is invisible.

**Verified on device**, with `gemma-4-E2B-it.litertlm` (2.4 GB) selected:

> User: *O que tem na minha área de trabalho?*
> Tool: `list_dir` — 23 ms — `/data/user/0/com.talos.harness.debug/files/workspace (0 entradas)`
> Answer: *Não há arquivos na sua área de trabalho.*
> Usage: `total 2488 tokens`

Two things are established by that run:

1. **`list_dir` is in the full catalogue only**, so the large model was in fact given all
   13 tools and the window-based rule fired as intended.
2. **`total 2488 tokens`** — more than double the 1024 that killed the small bundle, so
   the Gemma's window is far larger than the 1024 that was previously assumed for every
   on-device model.

So the complete agent — all 13 tools, no network, no API key — runs on this phone. The
Gemma also picked the right tool unprompted, which answers the separate open question of
whether a general model (as opposed to FunctionGemma, the tool specialist) can drive the
catalogue: at least on this task, it can.
