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

## Live verification — PASSED

Verified end to end from the phone, after one earlier attempt that did not reach
the gate (kept below, because the reason it failed is the reason this test works).

The prompt must **force the path**: *"Escreva o /tmp/hermes-approval-test/AGENTS.md
com o texto teste"*. Letting the agent choose the location is not a test — it wrote
to its own scratch directory and no gate fired.

What was observed:

1. The desktop agent paused; the phone showed **"Permitir ação no computador?"**
   with the preview `<write to AGENTS.md>` — the gateway's `command` field, arriving
   on the `approval.request` SSE frame. The title differs from the local gate's
   ("Permitir fetch_url?"), which is how the UI distinguishes the source.
2. The dialog offered **only Permitir and Recusar, no "Sempre"** — correct, because
   the event advertised `["once","deny"]`. The advertised-choices rule holds in
   practice, not just in the mapping table.
3. Tapping Permitir POSTed the decision, the desktop resumed, and
   `/tmp/hermes-approval-test/AGENTS.md` appeared on the Mac containing `teste`.

That exercises the whole path: the parser trap avoided, the run-id threading, the
choice mapping, the POST, and the desktop's fail-closed wait.

### The earlier attempt that did NOT reach the gate

Kept because the failure mode is easy to repeat. The prompt was *"Escreva um
arquivo AGENTS.md com o texto teste na pasta de trabalho"* — it let the agent pick
the location, and it wrote `cache/scratch/AGENTS.md` with **no `approval.request`
emitted at all**. Seeing "Pronto" on screen would have looked like success while
testing nothing. Two facts worth carrying forward:

1. **The gate is location-sensitive.** The same filename under `/tmp` trips
   `pattern_key: protected_instruction_file`; under the gateway's own scratch
   directory it does not. A test must pin the path.
2. **The desktop agent cannot write to its own cwd here** — it reported
   `Operation not permitted` for `/Users/leonardozanattaribeiro` and fell back to
   scratch, which constrains what a phone-driven request can reach.

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
