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

---

# Security review (second pass)

The first review's output was truncated in transit, so this pass was asked specifically
for **every** security-relevant finding. It found ten, three of them P1 — including the
one that matters most for an agent harness. Read-only; nothing was reproduced.

## P1-1 — `SAFE` tools read private data with no approval, including in scheduled runs

`SAFE` is the **default** risk (`Tool.kt:28-36`) and the loop skips approval for it
(`AgentLoop.kt:141-167`). What is currently `SAFE`:

- `read_file` accepts **absolute paths with no workspace boundary** (`FileTools.kt:16-18`)
- contacts (`ContactsTool.kt:39`) and calendar (`CalendarTool.kt:39`) are explicitly `SAFE`
- `clipboard_write` inherits the default

The scheduled worker includes these and approves every `SAFE` call **even when
`allowSensitiveTools = false`** (`ScheduledAgentWorker.kt:241-259, 321-336`).

So: content fetched by `fetch_url`, or content shared into the app, can carry an
instruction that steers the model into reading app-accessible files, contacts or
calendar — and the tool results are then sent to the selected model provider on the next
turn. That is prompt injection turning into exfiltration, and it needs no approval
anywhere along the way.

**Fix:** an explicit allowlist for unattended tools rather than a risk-label test; confine
file access to a canonical workspace including symlinks; gate contacts, calendar and
clipboard writes; and treat fetched or tool-returned content as untrusted data in the
agent policy rather than as instructions.

## P1-2 — Stopping a gateway run does not cancel the desktop run

`stop()` cancels the local job and the gate but sends no remote stop
(`ChatViewModel.kt:480-488`), and approval POSTs run in `viewModelScope` independently of
that job (`:502-514`), so a queued decision can still reach the gateway after Stop.

**Fix:** track the active gateway run and cancel it remotely; tie the approval POST to
that run's lifecycle.

## P1-3 — A pending decision can send the gateway key to a newly edited endpoint

`postDecision` reads the URL and key *after* approval rather than binding them to the
gateway that raised the prompt (`ChatViewModel.kt:593-613`). Desktop mode can stay
selected while the editable base URL changes, so the decision — and the bearer key —
go to whatever URL is in the field by then.

**Fix:** bind the decision to the originating gateway origin and credential, and refuse
to send if that origin changed.

## P2 findings

4. **`fetch_url` allows SSRF and follows redirects unchecked** (`HttpTool.kt:57-80,
   124-129`) — an approved call can reach loopback or private-network services. Block
   private/link-local destinations and revalidate every redirect.
5. **Gateway approval metadata fails OPEN** (`ChatViewModel.kt:543-560`,
   `GatewayApprovalGate.kt:312-320`) — missing or unrecognised `choices` still offers
   "Permitir", and a blank command shows a generic prompt. Still needs a tap, but the
   user can approve without a valid advertised choice or a meaningful preview. Fail
   closed instead.
6. **A failed approval POST loses the prompt** — the gate settles and removes the entry
   before the POST resolves. Keep it pending until acknowledged, with retry.
7. **Standing approvals are tool-wide and some previews hide the payload** — "Sempre"
   stores only the tool name (`ChatViewModel.kt:351-359`), so one approval covers all
   future shell commands, URLs or writes, in every conversation. `fetch_url` previews
   only the first 300 characters (`HttpTool.kt:47-54`) and `write_file` shows a character
   count, not the content (`FileTools.kt:89-94`).
8. **Stop does not reliably terminate shell work** — `ShellTool` blocks in
   `waitFor`/`join` with no cancellation cleanup (`ShellTool.kt:62-90`); the command can
   outlive the turn and descendants can survive.
9. **A subagent's timeout cannot interrupt a blocked OkHttp read** — read and call
   timeouts are zero (`DeepSeekClient.kt:266-271`), so cancellation is only noticed
   between lines. The same applies to interactive remote turns.
10. **Keys and conversations are stored unencrypted** — provider and gateway keys in
    Preferences DataStore (`Settings.kt:134-153`), conversations as plaintext JSON
    (`ConversationStore.kt:27-52`). Backup is disabled and no key logging was found, but
    device compromise plus an unrestricted `read_file` compounds it. The client also does
    not require HTTPS for non-loopback endpoints.

## Clean or already mitigated

- The on-device stream now has a 60 s no-output stall timeout (the HIGH finding, fixed).
- Gateway request ids are bound to their own approval entries — the earlier wrong-id
  finding appears fixed.
- No in-app HTTP server, no WebView, no deep-link filter.
- The only exported component is the share-capable activity, and shared text/images are
  staged rather than auto-sent.
- FileProvider is non-exported and limited to `cache/captures`.
- Shared and captured images are capped at 25 MB.
- No app screenshot-capture path found.
