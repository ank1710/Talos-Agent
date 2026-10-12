# Remote approvals over the desktop gateway

Implementation brief for the one feature the user asked for that is **not built**:
the *desktop* agent pausing to ask permission, with the prompt reaching the phone
and the answer going back.

**Verdict: supported.** The gateway is a first-class approval surface, not a
degraded one. The following was verified live against the running gateway on
`127.0.0.1:8642` — a real gated write was triggered, observed pending, and
resolved from the API — not merely read out of the source.

## The two paths

Both work, and the app already uses the second one:

| Path | How approval arrives |
|---|---|
| `POST /v1/runs` + `GET /v1/runs/{id}/events` (SSE) | `approval.request` event |
| `POST /v1/chat/completions` with `stream: true` | same `approval.request` event; `run_id` is the `chatcmpl-…` id |

## The wire shapes

Pending, in the SSE stream:

```json
{"event":"approval.request","run_id":"run_9c28...","request_id":"6dfdb829...",
 "command":"<write to AGENTS.md>","pattern_key":"protected_instruction_file",
 "choices":["once","deny"],"seq":1}
```

Reconnect/replay — `GET /v1/runs/{run_id}` while pending returns:

```json
{"status":"waiting_for_approval","last_event":"approval.request",
 "approval":{"request_id":"...","choices":["once","deny"]}}
```

Decision:

```
POST /v1/runs/{run_id}/approval        Authorization: Bearer <API_SERVER_KEY>
{"choice":"once"}                       → {"object":"hermes.run.approval_response",
                                           "run_id":"...","choice":"once","resolved":1}
```

Valid choices: `once`, `session`, `always`, `deny` (aliases `approve`/`approved`/`allow`
map to `once`). Optional `request_id` targets one of several concurrent prompts;
`all: true` resolves them all. A duplicate POST returns **409 `approval_not_pending`**.
`POST /v1/runs/{id}/stop` cancels while pending.

**Only offer the choices the event advertises.** Some gates offer just
`["once","deny"]` (one-operation gates such as protected instruction files) while
others offer `["once","session","always","deny"]`.

## The trap that will bite on the first attempt

In the chat-completions stream the approval arrives as a **bare JSON `data:` frame
with no `choices` key** — it is not an OpenAI chunk.

`DeepSeekClient.streamChat` currently decodes every frame as `StreamChunk`, whose
`choices` field defaults to an empty list, with `ignoreUnknownKeys = true`. So an
approval frame decodes **successfully into an empty chunk and is silently
dropped**. The parser must be changed to inspect the raw JSON for an `event` field
before falling back to the chunk shape — not simply to "handle unknown frames",
which it already appears to do.

## Where it plugs into the app

`agent/Approval.kt` already defines the seam: `ApprovalGate.request(...)` suspends
the loop, and `ChatViewModel` is just one possible answerer. A gateway-backed gate
is a second implementation of the same interface — no changes to `AgentLoop`.

The work is therefore: a gateway approval transport (parse the event, POST the
decision), a second `ApprovalGate`, and surfacing it in the UI as a notification
with actions so it can be answered from the shade.

## Live verification attempt — did NOT reach the approval path

The feature is implemented and compiles (`dcc3af8`), but the first live attempt to
verify it end to end **failed to trigger a gate**, so it remains runtime-unverified.

What was tried: PC mode on, gateway key present, `adb reverse tcp:8642` up, and the
prompt *"Escreva um arquivo AGENTS.md com o texto teste na pasta de trabalho"*.

What happened: the desktop agent wrote
`/Users/leonardozanattaribeiro/Desktop/Code/hermes-bridge/cache/scratch/AGENTS.md`
and reported success. **No `approval.request` was emitted**, so no prompt reached
the phone.

Two facts worth carrying forward:

1. **The gate is location- or context-sensitive in a way not yet characterised.**
   The research probe *did* trip it with an `AGENTS.md` under `/tmp`, reporting
   `pattern_key: protected_instruction_file`. The same filename under the gateway's
   own scratch directory did not. Until that difference is understood, a test that
   lets the agent choose the location is not a test of the approval path.
2. **The desktop agent cannot write to its own working directory here** — it
   reported `Operation not permitted` for `/Users/leonardozanattaribeiro` and fell
   back to its scratch dir. That constrains which paths a phone-driven request can
   even reach.

To verify properly, the prompt must force a path that matches the protected
pattern (the probe's approach: a throwaway `AGENTS.md` in a fresh temp directory),
rather than describing the file and letting the agent pick.

## Gateway-side policy worth knowing

- The server-side agent's toolsets come from `platform_toolsets.api_server`
  (`api_server.py:2374`, `hermes_cli/tools_config.py:573-583`). That key is
  **absent** in this machine's `config.yaml`, so the platform default applies.
  The global `toolsets: [hermes-cli, web]` key does **not** drive the API-server
  agent — a natural but wrong assumption.
- Command policy lives in `tools/approval.py`: `approvals.mode` (`manual` here),
  `approvals.timeout` (60s), non-bypassable `approvals.deny` globs, a
  `command_allowlist`, hardline floors, and a smart-approval guardian LLM.
- Fail-closed: an unanswered prompt does not become consent. Verified indirectly —
  a denied chat-completions approval left the target file uncreated.

## Not verified

- Whether the running server's git revision matches the source tree read.
- A timeout-triggered fail-closed specifically on the approval path (every prompt
  raised was resolved).
- Concurrent multiple pending approvals on one run, the `all` flag, and
  `session`/`always` persistence.
- Profile-scoped or restricted keys; only the owner bearer key was exercised.
