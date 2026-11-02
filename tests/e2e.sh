#!/usr/bin/env bash
# End-to-end test on this machine: a hub on 127.0.0.1, a node driving
# tests/fake-herdr, and a local HTTP listener as the push endpoint. Nothing
# touches the real Herdr or a real push server.
#
#   cargo build && tests/e2e.sh [path/to/herdr-pocket]
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
bin=${1:-$root/target/debug/herdr-pocket}
t=$(mktemp -d)
pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  rm -rf "$t"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; for f in "$t"/*.log; do echo "--- $f" >&2; cat "$f" >&2; done; exit 1; }
ok() { echo "ok - $*"; }
# wait_for <seconds> <command…>
wait_for() {
  local n=$(($1 * 10)); shift
  for ((i = 0; i < n; i++)); do "$@" >/dev/null 2>&1 && return 0; sleep 0.1; done
  return 1
}
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }

export XDG_CONFIG_HOME=$t/config XDG_STATE_HOME=$t/state XDG_RUNTIME_DIR=$t/run
export HERDR_BIN_PATH=$root/tests/fake-herdr FAKE_HERDR_DIR=$t/herdr
mkdir -p "$XDG_CONFIG_HOME/herdr-pocket" "$FAKE_HERDR_DIR" "$XDG_RUNTIME_DIR"

port=$(free_port)
push_port=$(free_port)
token=$("$bin" token)
hub=http://127.0.0.1:$port
cat >"$XDG_CONFIG_HOME/herdr-pocket/config.toml" <<EOF
hub = "$hub"
token = "$token"
listen = "127.0.0.1:$port"
machine = "testbox"
EOF

agents() { # agents <status of w1:p1> <status of w1:p2>
  cat >"$FAKE_HERDR_DIR/agents.json.new" <<EOF
{"id":"cli:agent:list","result":{"agents":[
 {"agent":"claude","agent_status":"$1","cwd":"/home/u/dev/game","pane_id":"w1:p1","terminal_title_stripped":"Fix the build","workspace_id":"w1"},
 {"agent":"codex","agent_status":"$2","cwd":"/home/u/dev/site","pane_id":"w1:p2","terminal_title_stripped":"","workspace_id":"w2"}
],"type":"agent_list"}}
EOF
  mv "$FAKE_HERDR_DIR/agents.json.new" "$FAKE_HERDR_DIR/agents.json"
}
agents working working
echo '{"id":"cli:workspace:list","result":{"type":"workspace_list","workspaces":[{"workspace_id":"w1","label":"game"},{"workspace_id":"w2","label":"site"}]}}' \
  >"$FAKE_HERDR_DIR/workspaces.json"
: >"$FAKE_HERDR_DIR/calls.log"

# Push listener: 200 on /ok, 410 on /gone; logs every body it gets.
cat >"$t/push.py" <<'EOF'
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
log = sys.argv[2]
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        with open(log, "a") as f:
            f.write(f"{self.path} {self.headers.get('Content-Type')} {body}\n")
        self.send_response(410 if self.path == "/gone" else 200)
        self.end_headers()
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
EOF
: >"$t/push.log"
python3 -I "$t/push.py" "$push_port" "$t/push.log" &
pids+=($!)

api() { # api <method> <path> [body] → prints "<status> <body>"
  local args=(-s -o "$t/body" -w '%{http_code}' -X "$1" -H "Authorization: Bearer $token")
  [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
  local code
  code=$(curl "${args[@]}" "$hub$2")
  echo "$code $(cat "$t/body")"
}
expect() { # expect <what> <actual> <pattern>; only * is special
  local p=${3//\\/\\\\}
  p=${p//[/\\[}
  p=${p//]/\\]}
  p=${p//\?/\\?}
  [[ $2 == $p ]] || fail "$1: got '$2', want '$3'"
  ok "$1"
}

"$bin" hub >"$t/hub.log" 2>&1 &
pids+=($!)
hub_pid=$!
wait_for 10 curl -sf -H "Authorization: Bearer $token" "$hub/api/agents" || fail "hub did not start"

expect "no token is refused" "$(curl -s -o /dev/null -w '%{http_code}' "$hub/api/agents")" 401
expect "wrong token is refused" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer nope' "$hub/api/push")" 401
expect "empty hub" "$(api GET /api/agents)" '200 {"agents":[],"machines":[]}'

"$bin" node >"$t/node.log" 2>&1 &
node_pid=$!
pids+=($node_pid)
listed() { api GET /api/agents | grep -q '"connected":true' && api GET /api/agents | grep -q 'w1:p2'; }
wait_for 10 listed || fail "node did not show up"
ok "node connected"
body=$(api GET /api/agents)
expect "agent shape" "$body" '200 {"agents":[{"machine":"testbox","pane":"w1:p1","agent":"claude","status":"working","since":*,"title":"Fix the build","cwd":"/home/u/dev/game","workspace":"game","important":false},{"machine":"testbox","pane":"w1:p2","agent":"codex","status":"working","since":*,"title":"","cwd":"/home/u/dev/site","workspace":"site","important":false}],"machines":[{"name":"testbox","connected":true}]}'

"$bin" node >"$t/node2.log" 2>&1 || fail "second node failed"
ok "a second node exits at once"

curl -sN -H "Authorization: Bearer $token" "$hub/api/events" >"$t/sse.log" &
pids+=($!)
wait_for 5 grep -q '^event: agents' "$t/sse.log" || fail "no SSE on connect"
ok "SSE sends agents on connect"

expect "screen" "$(api GET '/api/agents/testbox/w1%3Ap1/screen?lines=5')" '200 {"text":"screen of w1:p1\n--source recent-unwrapped --lines 5\n"}'
expect "screen default lines" "$(api GET /api/agents/testbox/w1:p2/screen)" '200 {"text":"screen of w1:p2\n--source recent-unwrapped --lines 200\n"}'
expect "prompt" "$(api POST /api/agents/testbox/w1:p1/prompt '{"text":"fix the tests"}')" '200 {}'
expect "prompt that looks like an option" "$(api POST /api/agents/testbox/w1:p1/prompt '{"text":"-v please"}')" '200 {}'
expect "keys" "$(api POST /api/agents/testbox/w1:p2/keys '{"keys":["Enter","C-c"]}')" '200 {}'
expect "herdr saw them" "$(cat "$FAKE_HERDR_DIR/calls.log")" $'prompt w1:p1 fix the tests\nprompt w1:p1  -v please\nkeys w1:p2 Enter C-c'
expect "unknown pane" "$(api POST /api/agents/testbox/w9:p9/keys '{"keys":["Enter"]}')" '404 {"error":"no agent in w9:p9 on testbox"}'
expect "unknown machine" "$(api GET /api/agents/odin/w1:p1/screen)" '404 {"error":"no machine odin"}'
expect "empty prompt" "$(api POST /api/agents/testbox/w1:p1/prompt '{"text":" "}')" '400 {"error":"text is empty"}'
expect "bad json" "$(api POST /api/agents/testbox/w1:p1/keys '{"keys":')" '400 {"error":*}'
expect "unknown route" "$(api GET /api/nothing)" '404 {"error":"not found"}'

expect "push add" "$(api POST /api/push "{\"endpoint\":\"http://127.0.0.1:$push_port/ok\"}")" '200 {}'
expect "push add gone" "$(api POST /api/push "{\"endpoint\":\"http://127.0.0.1:$push_port/gone\"}")" '200 {}'
expect "push needs a URL" "$(api POST /api/push '{"endpoint":"ntfy"}')" '400 {"error":"endpoint must be an http(s) URL"}'
expect "important on" "$(api PUT /api/agents/testbox/w1:p1/important '{"important":true}')" '200 {}'
expect "important unknown pane" "$(api PUT /api/agents/testbox/w9:p9/important '{"important":true}')" '404 *'
expect "important shows" "$(api GET /api/agents)" '200 {"agents":[{"machine":"testbox","pane":"w1:p1",*"important":true},*"important":false}]*'
grep -q '"pane": "w1:p1"' "$XDG_STATE_HOME/herdr-pocket/hub.json" || fail "important not saved"
ok "important saved in hub.json"
wait_for 3 grep -q '"important":true' "$t/sse.log" || fail "SSE missed the important change"
ok "SSE sends changes"

agents done done
wait_for 8 grep -q '"event":"finished"' "$t/push.log" || fail "no push on working -> done"
sleep 1
expect "one push to each endpoint, for the important agent only" "$(cut -d' ' -f1-2 "$t/push.log" | sort | tr '\n' ' ')" "/gone application/json /ok application/json "
expect "push body" "$(grep '^/ok' "$t/push.log" | cut -d' ' -f3-)" '{"machine":"testbox","pane":"w1:p1","agent":"claude","status":"done","title":"Fix the build","event":"finished"}'
wait_for 3 bash -c "! grep -q /gone '$XDG_STATE_HOME/herdr-pocket/hub.json'" || fail "410 endpoint kept"
ok "410 endpoint dropped"
since=$(api GET /api/agents | grep -o '"since":[0-9]*' | head -1)
grep -q '"status":"done"' "$t/sse.log" || fail "SSE missed the status change"
ok "SSE sends status changes"

: >"$t/push.log"
agents blocked idle
wait_for 8 grep -q '"event":"needs you"' "$t/push.log" || fail "no push on -> blocked"
ok "push on blocked"
expect "since moved" "$( [[ $(api GET /api/agents | grep -o '"since":[0-9]*' | head -1) != "$since" || $since == "" ]] && echo moved)" moved

expect "important off" "$(api PUT /api/agents/testbox/w1:p1/important '{"important":false}')" '200 {}'
: >"$t/push.log"
agents working idle
sleep 3
agents done idle
sleep 3
expect "no push once not important" "$(cat "$t/push.log")" ""
api PUT /api/agents/testbox/w1:p1/important '{"important":true}' >/dev/null

"$bin" status >"$t/status.log" 2>&1 || fail "status failed"
grep -q "reachable" "$t/status.log" && grep -q "testbox *connected *2 agents" "$t/status.log" || fail "status output"
ok "status sees the hub and the machine"
"$bin" pair --url http://hub.example:8790 >"$t/pair.log" 2>&1 || fail "pair failed"
grep -q "herdr-pocket://pair?url=http%3A%2F%2Fhub.example%3A8790&token=$token" "$t/pair.log" || fail "pair link"
ok "pair prints the link"

touch "$FAKE_HERDR_DIR/gone"
wait_for 12 bash -c "! kill -0 $node_pid" || fail "node outlived herdr"
ok "node exits once herdr is gone"
wait_for 5 bash -c "curl -s -H 'Authorization: Bearer $token' $hub/api/agents | grep -q '\"connected\":false'" || fail "machine still connected"
ok "machine shows disconnected"
expect "disconnected machine" "$(api POST /api/agents/testbox/w1:p1/keys '{"keys":["Enter"]}')" '503 {"error":"testbox is not connected"}'

kill "$hub_pid"
wait "$hub_pid" 2>/dev/null || true
"$bin" hub >>"$t/hub.log" 2>&1 &
pids+=($!)
wait_for 10 curl -sf -H "Authorization: Bearer $token" "$hub/api/agents" || fail "hub did not restart"
rm "$FAKE_HERDR_DIR/gone"
"$bin" node >>"$t/node.log" 2>&1 &
pids+=($!)
wait_for 10 listed || fail "node did not reconnect"
expect "important survives a restart" "$(api GET /api/agents)" '200 {"agents":[{"machine":"testbox","pane":"w1:p1",*"important":true}*'
expect "push remove" "$(api DELETE /api/push "{\"endpoint\":\"http://127.0.0.1:$push_port/ok\"}")" '200 {}'
grep -q '"push": \[\]' "$XDG_STATE_HOME/herdr-pocket/hub.json" || fail "push not removed"
ok "push endpoint removed"

echo "all passed"
