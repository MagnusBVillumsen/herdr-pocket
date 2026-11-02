# herdr-pocket

Your [Herdr](https://herdr.dev) agents, on every machine, in your pocket. An
Android app lists every agent with its status, shows its screen, lets you
answer it or press a key, and sends a push notification when an agent you
marked important finishes or needs you.

```
              HTTPS + SSE              WebSocket, dialled by the node
  phone app ──────────────▶ hub ◀─────────── node ── herdr CLI ── Herdr on thor
      ▲                      │  ◀─────────── node ── herdr CLI ── Herdr on odin
      │ push                 │ HTTP POST
  ntfy app ◀─────────── ntfy server
```

- **node** runs on every machine with Herdr, as the plugin's startup hook. It
  polls `herdr agent list` every 2 seconds, sends the hub the list whenever
  it changes, and runs the hub's requests (read a pane, send a prompt, send
  keys) through the `herdr` CLI. It dials out, so the hub needs no SSH or
  network access to your machines, and it exits when Herdr does.
- **hub** runs on one always-on host. It keeps the latest list from every
  node, serves the app (JSON and server-sent events), remembers which chats
  are important, and posts notifications to the phone's UnifiedPush
  endpoint.

Both are the same binary, `herdr-pocket`. Every request carries a shared
bearer token. The wire protocol is in [docs/protocol.md](docs/protocol.md).

Notifications only fire for chats marked important in the app:

- working → done or idle: *finished*
- anything → blocked: *needs you*

## Install

### The plugin, on every machine with Herdr

```sh
herdr plugin install MagnusBVillumsen/herdr-pocket
```

This builds the binary with cargo and starts a node whenever Herdr starts.
The Nix package ships a ready plugin directory without a build step:
`herdr plugin link $(nix build --print-out-paths github:MagnusBVillumsen/herdr-pocket)/share/herdr-pocket/plugin`.

A node with no `hub` or `token` in its config does nothing, and only one node
runs per machine however many Herdr servers start one.

### The hub, on one always-on host

Install the binary (`cargo install --git https://github.com/MagnusBVillumsen/herdr-pocket`,
or `nix profile install github:MagnusBVillumsen/herdr-pocket`) and run it as
a user service. `~/.config/systemd/user/herdr-pocket-hub.service`:

```ini
[Unit]
Description=herdr-pocket hub
After=network-online.target

[Service]
ExecStart=%h/.cargo/bin/herdr-pocket hub
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```sh
systemctl --user enable --now herdr-pocket-hub
loginctl enable-linger $USER   # keep it running when you are logged out
```

The hub keeps important chats and push endpoints in
`$XDG_STATE_HOME/herdr-pocket/hub.json` (`~/.local/state/herdr-pocket/hub.json`).

### Network

Keep all of it on a private network. A [Tailscale](https://tailscale.com)
tailnet is the easy way: the phone and every machine reach the hub by its
tailnet name, and nothing is exposed to the internet. Let the hub listen on
the tailnet (`listen = "0.0.0.0:8790"`, or the host's tailnet address), or
keep it on `127.0.0.1` and put `tailscale serve --bg 8790` in front of it for
HTTPS.

### Push notifications

The app receives notifications through [UnifiedPush](https://unifiedpush.org),
so there is no Google account or Firebase involved. Install
[ntfy](https://ntfy.sh) on the phone (F-Droid or Play Store) as the
distributor; the herdr-pocket app registers with it and hands the hub its
endpoint. The hub POSTs a small JSON message to that endpoint for each
notification and forgets endpoints that answer 404 or 410.

The messages pass through the ntfy server, and contain the machine, pane,
agent and chat title. To keep them private, run your own ntfy server on the
tailnet and point the ntfy app at it.

### The Android app

The app lives in [app/](app/). See its README for how to build and install
it.

## Config

`~/.config/herdr-pocket/config.toml` (`$XDG_CONFIG_HOME` is honoured), the same
file on the hub and on the nodes:

```toml
hub = "http://heimdall:8790"   # where nodes connect, and the URL `pair` shows
token = "…"                    # shared secret; `herdr-pocket token` makes one
listen = "0.0.0.0:8790"        # hub only; default 127.0.0.1:8790
machine = "thor"               # node only; default the short hostname
```

Use the same `token` everywhere. The node finds Herdr through
`$HERDR_BIN_PATH`, which Herdr sets for its hooks, else `herdr` on `PATH`.

## Commands

| Command | What it does |
|---|---|
| `herdr-pocket hub` | Run the hub. |
| `herdr-pocket node` | Run a node (the plugin does this for you). |
| `herdr-pocket token` | Print a new random token. |
| `herdr-pocket pair [--url URL]` | Print a QR code and a `herdr-pocket://pair` link with the hub URL and token. Scan it with the app. `--url` is the address the phone uses, default `hub` from the config. |
| `herdr-pocket status` | Show the config, whether a node runs here, whether the hub answers, and its machines. |

All take `--config PATH`.

To pair the phone, run `herdr-pocket pair` on any machine with the config,
for example `herdr-pocket pair --url https://heimdall.tail1234.ts.net`, and
scan the code with the app.

## Development

```sh
nix develop
cargo test
cargo build && tests/e2e.sh
```

`tests/e2e.sh` runs a hub and a node on `127.0.0.1` against a fake `herdr`
([tests/fake-herdr](tests/fake-herdr)) and a local HTTP listener as the push
endpoint, then drives the app API with curl: the agent list, SSE, screen,
prompt, keys, important flags, push on status changes, and the node exiting
with Herdr. It never touches a real Herdr or push server.

## License

MIT or Apache-2.0, at your option.
