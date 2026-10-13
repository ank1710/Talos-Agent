# Settings and local-model follow-up

Updated 12 October 2026. This follows the memory/history/skills implementation
in [memory-skills-report.md](memory-skills-report.md).

## Settings changes

- Connections are edited as drafts. A test or model-list request uses the draft
  values only after the user asks for it; **Salvar** commits provider, endpoint,
  model and key together. Leaving with unsaved edits is handled explicitly.
- Cloud and desktop credentials are bound to the selected mode, provider and
  normalized endpoint. Changing target clears the visible draft key unless an
  explicitly saved credential exists for that exact target. Legacy active keys
  migrate to their current target; inactive keys with an ambiguous destination
  are retained separately and never guessed into another provider.
- Endpoint validation rejects URLs that embed username/password, query secrets or
  fragments. Settings probes and model discovery time out and refuse redirects,
  so a test credential is not silently forwarded to a different host.
- Cloud, desktop and on-device are distinct modes. Switching restores the saved
  profile for that target; local mode has no API-key field and cannot send an
  API key. The local model selection remains separate from cloud model IDs.
- Memory has separate switches for using it at all and including it in remote
  requests. Approval settings use clearer wording and include a bulk revoke
  action. Diagnostics show the endpoint/model class appropriate to the mode and
  benchmark installed local models instead of probing a provider in local mode.
- Skills have a human display name separate from their technical identifier.
  Persistent-data lists support search, show provenance and date, and preserve
  unsaved edits when a write fails.

## Local model fit

Talos already loads any installed `.litertlm` bundle from its app-specific
`models` directory, lets the user select it, and can benchmark it. A device run
recorded in [on-device-findings.md](on-device-findings.md) used a Galaxy S24 Ultra
(SM8650, Snapdragon 8 Gen 3, Android 16, 10.8 GB measured available):

| Model | Measured result | What it tells us |
|---|---:|---|
| `mobile-actions_q8_ekv1024.litertlm` (271 MB) | 78.8 tokens/s, CPU | Very fast at its action-specialist job; it is not a general chat model. |
| `gemma-4-E2B-it.litertlm` (2.4 GB) | 35.5 tokens/s, CPU | A general conversational model with enough context to use Talos tools. |
| `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` (347 MB) | loaded in 3.3 s; first token 790 ms; 42.8 tokens/s upper bound on CPU | It loaded and generated in Talos. Its 1280-token context limits it to short turns and fewer tools. |

The first open-source candidate, Qwen3 0.6B INT4 from LiteRT Community, has now
loaded and generated on the S24 Ultra. This no-thinking `.litertlm` file is
347,251,840 bytes, uses Apache 2.0, and the model card reports roughly twice the
CPU speed of its thinking variant. Its 1280-token KV context means Talos exposes
the compact local toolset and turns must stay short. The distinct
dynamic INT4 variant is also about 329 MB but has a 4096-token context; it is a
better comparison for tool use and longer turns. The repository says it works
with LiteRT-LM 0.17.1+, and this app currently pins 0.18.0. [Artifacts, context
and license](https://huggingface.co/litert-community/Qwen3-0.6B-int4),
[4k-context Qwen3 artifacts](https://huggingface.co/litert-community/Qwen3-0.6B).

Talos' fixed local benchmark asked it to count from 1 to 500, capped output at
96 tokens, and measured loading separately from generation. Qwen3 loaded in
3.3 s, produced its first token after 790 ms and generated 190 characters in
3.1 s on CPU. Talos reports **42.8 tokens/s as an upper bound**, because LiteRT
exposes the final KV token count (prompt plus output) rather than a
completion-only count. The benchmark left the model unloaded afterwards. This
result is numerically above the earlier 35.5 tokens/s Gemma 4 E2B measurement,
but a short fixed benchmark does not establish sustained chat speed or answer
quality. Portuguese quality, tool choice and longer-context behavior still need
a direct comparison. The artifact is pinned to repository revision
`6aa2daf8aba4aa456797fb8040b36a3948bcfda7` and SHA-256
`2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`.

Gemma 3 1B IT is a second candidate if quality at 4096 context matters more than
permissive licensing: the 4-bit `.litertlm` file is about 584 MB, but access
requires accepting Google's Gemma terms. [Model files and license](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main).

Qwen2.5 1.5B Instruct remains another Apache-2.0 option, but a recent Google AI
Edge Gallery report describes a chat-template crash for that model. This does
not prove that Talos fails, but makes it a lower-priority candidate than Qwen3.
[Model files and license](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/tree/main),
[recent compatibility report](https://github.com/google-ai-edge/gallery/issues/1317).

Qwen3's speed is now measured, but its Portuguese answer quality, tool selection
and longer-context behavior have not been evaluated. Gemma 3 1B remains untested
on this phone. The model-card claim of roughly 2x CPU speed refers to Qwen3's
thinking variant, not a Talos measurement. The existing 270M action specialist
should not be presented as a faster general assistant.

## Screenshot text-wrap fix

The latest Talos screenshot showed each ordered-list body squeezed into a very
narrow column. `MarkdownText.kt` was reading the regular-expression body capture
as the list marker, so the renderer measured the whole sentence as the marker
width. Ordered-list lines now go through the pure `parseOrderedListLine` helper,
which keeps the number and `.`/`)` delimiter separate from the full body. The
host regression uses the Portuguese `1. **API (rápido...) ...` shape from the
screenshot and confirms the body stays intact. I opened that same conversation
on the S24 Ultra after installing the debug APK: the `1.` marker stays short and
the body wraps across the available chat width.

## Handoff prompt

The copy-ready DeepSeek Harness prompt is in
[prompts/deepseek-harness-settings-and-local-model.md](prompts/deepseek-harness-settings-and-local-model.md).
It asks the next agent to evaluate Qwen's Portuguese quality, compare a 4k-context
model, and decide whether to add a safe model catalog/download flow. It keeps
model weights, persona and tool authority as separate concerns.

## Validation boundary

The Android build for this follow-up completed successfully with
`./gw :app:assembleDebug`; the APK is at
`app/build/outputs/apk/debug/app-debug.apk`. Existing Kotlin/Gradle warnings remain.
The host logic check passed **22 checks**: eleven persistent-data checks, ten
Settings/connection checks and one Markdown regression. Those include scoped-key migration, mode/profile
switching, failed-save atomicity, endpoint validation, memory policy, skill title
migration and a local HTTP redirect test proving the credential does not reach
the redirect target. Host results do not establish all Compose behavior or
general model quality. The Qwen3 0.6B no-thinking bundle was downloaded
from its official Hugging Face repository and pushed to the debug app's private
model directory. Its 347,251,840-byte size and SHA-256
`2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139` match the
repository metadata on both host and device. It was selected in local mode and
benchmarked on the device; Talos' result is recorded above. The end-to-end user
chat quality and tool selection remain unverified.
