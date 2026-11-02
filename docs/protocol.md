# herdr-pocket protocol

```
phone app ──HTTPS/SSE──▶ hub (always-on host) ◀──WebSocket── node (one per machine, inside Herdr)
                         │
                         └──HTTP POST──▶ UnifiedPush endpoint (ntfy) ──▶ phone
```

One binary, `herdr-pocket`, with two roles:

- **node** runs on every machine with Herdr, as the plugin's `[[startup]]`
  hook. It dials out to the hub, so the hub needs no SSH or network access to
  the machines, and it exits when Herdr does. It talks to Herdr only through
  the `herdr` CLI (`HERDR_BIN_PATH`).
- **hub** runs on one always-on host (a user service). It keeps the latest
  agent list from every node, serves the app, remembers which chats are
  important, and sends push notifications.

All traffic is meant to stay on a private network such as a tailnet; the hub
also requires a bearer token on every request.

## Config

`$XDG_CONFIG_HOME/herdr-pocket/config.toml`, the same file for both roles:

```toml
hub = "http://heimdall:8790"   # node: where to connect
token = "…"                    # shared secret; `herdr-pocket token` makes one
listen = "0.0.0.0:8790"        # hub only; default 127.0.0.1:8790
machine = "thor"               # node: default is the short hostname
```

Hub state (important chats, push endpoints) lives in
`$XDG_STATE_HOME/herdr-pocket/hub.json`.

## Agents

An agent is identified by `machine` + `pane` (Herdr pane id such as `w1:p2`).

```json
{
  "machine": "thor",
  "pane": "w1:p2",
  "agent": "claude",
  "status": "working",
  "since": 1793600000,
  "title": "Fix the build",
  "cwd": "/home/magnus/dev/game",
  "workspace": "game",
  "important": false
}
```

- `status`: `idle`, `working`, `blocked`, `done` or `unknown`, as Herdr
  reports it.
- `since`: unix seconds when `status` last changed (set by the node). A pane
  the node sees for the first time counts from then, as Herdr does not say
  how long it has been in its state.
- `title`: the agent's name or terminal title when Herdr has one, else empty.
- `important` is set by the hub, never by the node.

## Node ↔ hub

`GET /api/node` upgraded to a WebSocket, with
`Authorization: Bearer <token>` and `X-Machine: <name>`. JSON text frames.
The name is letters, digits, `-`, `_` and `.` (else 400); the hub uses it as
every agent's `machine`, whatever the node put there. A new connection with
the same name replaces the old one. Both sides send a WebSocket ping every
20 s and drop a connection that has been silent for 60 s, so a machine that
goes to sleep shows up as disconnected.

Node → hub:

```json
{"type": "agents", "agents": [Agent, …]}          // full list, on connect and on every change
{"type": "reply", "id": 7, "ok": true, "result": {…}}
{"type": "reply", "id": 7, "ok": false, "error": "…"}
```

Hub → node (each answered by one `reply` with the same `id`):

```json
{"type": "read",   "id": 7, "pane": "w1:p2", "lines": 200}   // result: {"text": "…"}
{"type": "prompt", "id": 8, "pane": "w1:p2", "text": "…"}    // result: {}
{"type": "keys",   "id": 9, "pane": "w1:p2", "keys": ["Enter"]}  // result: {}
```

- `read` → `herdr pane read <pane> --source recent-unwrapped --lines N`
- `prompt` → `herdr agent prompt <pane> <text>`. Herdr's CLI takes any
  argument that starts with `-` for an option and has no `--`, so the node
  sends such a text with a leading space.
- `keys` → `herdr pane send-keys <pane> <key>…`; a key that starts with `-` is
  refused.
  Key names are Herdr's: `Enter`, `esc`, `up`, `down`, `tab`, `backspace`,
  or a character such as `1` or `y`.

A failed command, or a request the node does not know, gets `ok: false` with
Herdr's error message.

The node polls `herdr agent list` every 2 s and sends `agents` only when
something changed. It reconnects with backoff (1 s → 30 s) and exits once
Herdr stops answering (3 failed polls in a row), like `herdr-sleep watch
--hook`. Only one node runs per machine (a lock in `$XDG_RUNTIME_DIR`); a
second one exits at once, as does a node with no `hub` or `token`. A node
that disconnects stays in the hub's list with `connected: false` and its last
agents for 60 s, then disappears.

## App ↔ hub

Every request carries `Authorization: Bearer <token>`. Errors are
`{"error": "…"}` with a 4xx/5xx status. `{machine}` and `{pane}` are
URL-encoded path segments.

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/api/agents` | | `{"agents": [Agent…], "machines": [{"name": "thor", "connected": true}]}` |
| GET | `/api/events` | | Server-sent events: `event: agents` with the same body as `/api/agents`, on connect and on every change; a `: ping` comment every 20 s |
| GET | `/api/agents/{machine}/{pane}/screen?lines=200` | | `{"text": "…"}` |
| POST | `/api/agents/{machine}/{pane}/prompt` | `{"text": "…"}` | `{}` |
| POST | `/api/agents/{machine}/{pane}/keys` | `{"keys": ["Enter"]}` | `{}` |
| PUT | `/api/agents/{machine}/{pane}/important` | `{"important": true}` | `{}` |
| POST | `/api/push` | `{"endpoint": "https://…"}` | `{}` |
| DELETE | `/api/push` | `{"endpoint": "https://…"}` | `{}` |

`lines` defaults to 200 and is clamped to 1–10000. `text` and `keys` must not
be empty (400), and `endpoint` must be an `http://` or `https://` URL (400).

| Status | When |
|---|---|
| 400 | bad body or `X-Machine` |
| 401 | missing or wrong token |
| 404 | unknown route, machine not in the list, or pane not in its machine's agents (`important: false` always succeeds) |
| 502 | the node answered `ok: false`; `error` is Herdr's message |
| 503 | the machine is disconnected, or disconnected while the hub waited |
| 504 | the node did not answer within 10 s |

## Notifications

Only **important** chats notify. The hub watches status changes:

- `working` → `done` or `idle`: "finished"
- any → `blocked`: "needs you"

A pane the hub has no earlier status for (its node just connected, or the
hub just started) does not notify. It POSTs this JSON, as
`Content-Type: application/json`, to every registered UnifiedPush endpoint:

```json
{"machine": "thor", "pane": "w1:p2", "agent": "claude",
 "status": "done", "title": "Fix the build", "event": "finished"}
```

An endpoint that answers 404 or 410 is removed. Important flags are keyed by
`machine` + `pane` and dropped when that pane is gone from its node's list
for 10 minutes: the clock starts with the first list that lacks the pane and
stops if a later list has it again. Flags, their clocks and the endpoints are
saved in `hub.json`, so they survive a restart.

## Pairing

`herdr-pocket pair [--url URL]` prints a QR code (and the link) for
`herdr-pocket://pair?url=<hub url>&token=<token>`, both percent-encoded.
Scanning it opens the app, which saves both. The URL defaults to `hub` from
the config, else `http://<hostname>:<listen port>`.
