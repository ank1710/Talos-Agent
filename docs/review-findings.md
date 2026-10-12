# Read-only review findings

A review run against `a78e69e` by a second agent, asked to investigate three open
questions and review the newest code. Nothing was modified; every finding below is
from reading the source. Ordered by severity.

## 1. The JNI load-order warning is benign — CLOSED

Logcat showed, in this order:

```
E No implementation found for void ...NativeLibraryLoader.nativeCheckLoaded() ...
D nativeloader: Load .../liblitertlm_jni.so ... ok
```

**Not a Talos bug.** LiteRT-LM's own `NativeLibraryLoader` deliberately probes
`nativeCheckLoaded()` *first*, catches the resulting `UnsatisfiedLinkError`, and only
then calls `System.loadLibrary`. The observed sequence is that probe working as
designed (`NativeLibraryLoader.kt` and `Engine.kt` at upstream tag v0.18.0).

**Do not "fix" it.** Adding an app-side `System.loadLibrary` would duplicate the SDK's
loader. This closes the open question — it was listed as something to understand, and
now it is understood.

## 2. A turn that never finishes: the on-device stream has no timeout — HIGH

The incident: a message in on-device mode produced neither a reply nor an error.

Normal failures do surface — a load failure becomes `Failed` at `OnDeviceBackend.kt:209`
and `:213`, and an exception during generation at `:334`. A Flow that completes *without
events* still reaches Talos's `Finished` (`:272`, `:326`), so it would produce an empty
turn and `Completed`, not a hang.

What stays active forever is an operation that never returns. LiteRT-LM's Flow adapter
waits in `awaitClose {}` until native `onDone` or `onError` arrives, and Talos collects
that Flow **without a timeout**. If the native callback never comes, neither terminal
event is ever emitted and the turn sits there.

**Fix:** wrap the on-device generation in a timeout and emit `Failed` with a Portuguese
message when it expires, so a hung native call becomes a visible failure instead of a
silent one. The incident's own timing was not captured, so this is the code-supported
mechanism rather than a reproduced cause.

## 3. P1 — a gateway approval can be answered with the wrong request's ids

`ChatViewModel.onGatewayFrame()` attaches the run/request ids, then registers the gate
*inside* a `viewModelScope.launch`. The gate later snapshots those shared mutable fields
(`GatewayApprovalGate.kt:105`, `:132`). If a second `approval.request` frame arrives
before that launch runs, the first prompt can be paired with the **second** request's
ids — so the user taps "Permitir" and the decision targets a different action than the
one they read.

Related: an `approval.responded` frame that arrives before the registration lands will
miss at `ChatViewModel.kt:497`, stranding the request that registers afterwards.

The protocol explicitly allows concurrent prompts, and concurrency was never verified
live. Severity is high because this is the code that decides whether a dangerous action
runs.

## 4. P2 — a subagent's timeout cannot interrupt a stalled remote stream

`SpawnSubagentTool.kt:184` bounds the child with `withTimeout`, but the remote backend
uses **blocking** OkHttp (`execute()`, `readUtf8Line()` at `DeepSeekClient.kt:67` and
`:83`) with read and call timeouts deliberately disabled (`:268`, `:270`). A coroutine
timeout cannot interrupt a thread parked in a blocking read, and the OkHttp call is not
cancelled. A stalled server can therefore hold the parent past the advertised timeout.

## 5. P2 — a failed approval POST loses the prompt

The UI settles and clears the prompt before the POST completes
(`ChatViewModel.kt:438`, `:442`). If the POST fails in transport, the client returns
`Result.failure` (`GatewayApprovalClient.kt:85`) and the caller only sets an error — the
prompt is already gone, with nothing to retry. Separately, the decision reads the
*current* gateway settings at `:519`, so editing the endpoint or key while a prompt is
pending sends that decision somewhere else.

## Checked and found clean

The tool registry's depth bound and its per-turn capture of backend and credentials
(`ChatViewModel.kt:186`, `:252`, `:260`): no bug found.

## Not determined

Whether the P1 race or the stalled stream actually occurred in a real run. Neither was
reproduced; both are read from the code.
