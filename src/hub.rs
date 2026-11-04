//! The hub: keeps every node's agents, serves the app and sends pushes.

use std::collections::{BTreeMap, HashMap};
use std::convert::Infallible;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use axum::extract::rejection::JsonRejection;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, Query, Request, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use futures_util::{SinkExt, Stream, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_stream::wrappers::WatchStream;

use crate::agent::Agent;
use crate::config::{self, Config};
use crate::notify::{self, Push};
use crate::proto::{FromNode, ToNode};
use crate::state::Saved;

const NODE_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a disconnected machine stays listed, so a node that restarts or
/// loses the network for a moment does not flicker in the app.
const LINGER: Duration = Duration::from_secs(60);
const PING: Duration = Duration::from_secs(20);
const SILENT: Duration = Duration::from_secs(60);
const SWEEP: Duration = Duration::from_secs(5);
const DEFAULT_LINES: u32 = 200;
const MAX_LINES: u32 = 10_000;

pub fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

#[derive(Debug)]
pub struct ApiError(StatusCode, String);

impl ApiError {
    fn new(code: StatusCode, msg: impl Into<String>) -> ApiError {
        ApiError(code, msg.into())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

impl From<JsonRejection> for ApiError {
    fn from(r: JsonRejection) -> ApiError {
        ApiError::new(r.status(), r.body_text())
    }
}

type Answer = oneshot::Sender<std::result::Result<Value, String>>;

struct Conn {
    id: u64,
    tx: mpsc::UnboundedSender<ToNode>,
}

#[derive(Default)]
struct Machine {
    agents: Vec<Agent>,
    conn: Option<Conn>,
    left: Option<Instant>,
}

#[derive(Serialize)]
struct MachineInfo {
    name: String,
    connected: bool,
}

/// What `/api/agents` and each SSE event carry.
#[derive(Serialize)]
struct Body {
    agents: Vec<Agent>,
    machines: Vec<MachineInfo>,
}

#[derive(Default)]
struct Inner {
    machines: BTreeMap<String, Machine>,
    saved: Saved,
    /// Requests waiting for a node's reply, by id, with the machine asked.
    pending: HashMap<u64, (String, Answer)>,
}

impl Inner {
    fn body(&self) -> Body {
        let agents: Vec<Agent> = self
            .machines
            .values()
            .flat_map(|m| m.agents.iter())
            .map(|a| Agent {
                important: self.saved.is_important(&a.machine, &a.pane),
                ..a.clone()
            })
            .collect();
        let machines: Vec<MachineInfo> = self
            .machines
            .iter()
            .map(|(n, m)| MachineInfo {
                name: n.clone(),
                connected: m.conn.is_some(),
            })
            .collect();
        Body { agents, machines }
    }

    fn json(&self) -> String {
        serde_json::to_string(&self.body()).unwrap_or_default()
    }

    fn drop_pending(&mut self, machine: &str) {
        self.pending.retain(|_, (m, _)| m != machine);
    }
}

pub struct Hub {
    inner: Mutex<Inner>,
    token: String,
    path: PathBuf,
    /// The current `/api/agents` body, for SSE.
    body: watch::Sender<Arc<String>>,
    ids: AtomicU64,
    client: reqwest::Client,
}

impl Hub {
    pub fn new(token: String, path: PathBuf, saved: Saved) -> Arc<Hub> {
        let inner = Inner {
            saved,
            ..Inner::default()
        };
        let body = watch::Sender::new(Arc::new(inner.json()));
        Arc::new(Hub {
            inner: Mutex::new(inner),
            token,
            path,
            body,
            ids: AtomicU64::new(1),
            client: notify::client(),
        })
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn publish(&self, inner: &Inner) {
        let s = inner.json();
        self.body.send_if_modified(|cur| {
            if **cur == s {
                return false;
            }
            *cur = Arc::new(s);
            true
        });
    }

    fn persist(&self, inner: &Inner) {
        if let Err(e) = inner.saved.save(&self.path) {
            eprintln!("{e:#}");
        }
    }

    fn connect(&self, machine: &str) -> (u64, mpsc::UnboundedReceiver<ToNode>) {
        let id = self.ids.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::unbounded_channel();
        let mut g = self.lock();
        let m = g.machines.entry(machine.into()).or_default();
        m.conn = Some(Conn { id, tx });
        m.left = None;
        g.drop_pending(machine);
        self.publish(&g);
        (id, rx)
    }

    fn disconnect(&self, machine: &str, id: u64) {
        let mut g = self.lock();
        let Some(m) = g.machines.get_mut(machine) else {
            return;
        };
        if m.conn.as_ref().is_none_or(|c| c.id != id) {
            return;
        }
        m.conn = None;
        m.left = Some(Instant::now());
        g.drop_pending(machine);
        self.publish(&g);
    }

    fn update(self: &Arc<Self>, machine: &str, mut agents: Vec<Agent>) {
        for a in &mut agents {
            a.machine = machine.into();
            a.important = false;
        }
        let mut g = self.lock();
        let inner = &mut *g;
        let m = inner.machines.entry(machine.into()).or_default();
        let pushes = notify::events(&m.agents, &agents, |a| {
            inner.saved.is_important(&a.machine, &a.pane)
        });
        let panes: Vec<&str> = agents.iter().map(|a| a.pane.as_str()).collect();
        let moved = inner.saved.observe(machine, &panes, unix_now());
        m.agents = agents;
        if moved {
            self.persist(&g);
        }
        self.publish(&g);
        let endpoints = g.saved.push.clone();
        drop(g);
        for p in pushes {
            self.push(endpoints.clone(), p);
        }
    }

    fn push(self: &Arc<Self>, endpoints: Vec<String>, p: Push) {
        if endpoints.is_empty() {
            return;
        }
        let hub = self.clone();
        tokio::spawn(async move {
            let dead = notify::send(&hub.client, &endpoints, &p).await;
            if dead.is_empty() {
                return;
            }
            let mut g = hub.lock();
            let mut changed = false;
            for d in &dead {
                eprintln!("dropping push endpoint {d}: it is gone");
                changed |= g.saved.remove_push(d);
            }
            if changed {
                hub.persist(&g);
            }
        });
    }

    fn reply(&self, id: u64, r: std::result::Result<Value, String>) {
        if let Some((_, tx)) = self.lock().pending.remove(&id) {
            let _ = tx.send(r);
        }
    }

    fn handle(self: &Arc<Self>, machine: &str, text: &str) {
        match serde_json::from_str::<FromNode>(text) {
            Ok(FromNode::Agents { agents }) => self.update(machine, agents),
            Ok(FromNode::Reply {
                id,
                ok,
                result,
                error,
            }) => self.reply(
                id,
                if ok {
                    Ok(result.unwrap_or_else(|| json!({})))
                } else {
                    Err(error.unwrap_or_else(|| "failed".into()))
                },
            ),
            Err(e) => eprintln!("{machine}: bad message: {e}"),
        }
    }

    /// Sends a request to the node that runs `pane` and waits for its reply.
    async fn ask(
        &self,
        machine: &str,
        pane: &str,
        make: impl FnOnce(u64) -> ToNode,
    ) -> std::result::Result<Value, ApiError> {
        let id = self.ids.fetch_add(1, Ordering::Relaxed);
        let rx = {
            let mut g = self.lock();
            let Some(m) = g.machines.get(machine) else {
                return Err(ApiError::new(
                    StatusCode::NOT_FOUND,
                    format!("no machine {machine}"),
                ));
            };
            let gone = || {
                ApiError::new(
                    StatusCode::SERVICE_UNAVAILABLE,
                    format!("{machine} is not connected"),
                )
            };
            let conn = m.conn.as_ref().ok_or_else(gone)?;
            if !m.agents.iter().any(|a| a.pane == pane) {
                return Err(ApiError::new(
                    StatusCode::NOT_FOUND,
                    format!("no agent in {pane} on {machine}"),
                ));
            }
            conn.tx.send(make(id)).map_err(|_| gone())?;
            let (tx, rx) = oneshot::channel();
            g.pending.insert(id, (machine.into(), tx));
            rx
        };
        match tokio::time::timeout(NODE_TIMEOUT, rx).await {
            Err(_) => {
                self.lock().pending.remove(&id);
                Err(ApiError::new(
                    StatusCode::GATEWAY_TIMEOUT,
                    format!("{machine} did not answer"),
                ))
            }
            Ok(Err(_)) => Err(ApiError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                format!("{machine} disconnected"),
            )),
            Ok(Ok(Err(e))) => Err(ApiError::new(StatusCode::BAD_GATEWAY, e)),
            Ok(Ok(Ok(v))) => Ok(v),
        }
    }

    async fn serve_node(self: Arc<Self>, machine: String, socket: WebSocket) {
        eprintln!("{machine} connected");
        let (id, mut rx) = self.connect(&machine);
        let (mut sink, mut stream) = socket.split();
        let mut ping = tokio::time::interval(PING);
        ping.tick().await;
        let mut heard = Instant::now();
        loop {
            tokio::select! {
                m = rx.recv() => {
                    // None: a newer connection from the same machine took over.
                    let Some(m) = m else { break };
                    let text = serde_json::to_string(&m).unwrap_or_default();
                    if sink.send(Message::Text(text.into())).await.is_err() {
                        break;
                    }
                }
                _ = ping.tick() => {
                    if heard.elapsed() > SILENT
                        || sink.send(Message::Ping(Default::default())).await.is_err()
                    {
                        break;
                    }
                }
                f = stream.next() => {
                    let Some(Ok(f)) = f else { break };
                    heard = Instant::now();
                    match f {
                        Message::Text(t) => self.handle(&machine, t.as_str()),
                        Message::Close(_) => break,
                        _ => {}
                    }
                }
            }
        }
        let _ = sink.close().await;
        self.disconnect(&machine, id);
        eprintln!("{machine} disconnected");
    }

    /// Forgets machines that have been gone for a while, and important flags
    /// whose pane is gone.
    fn sweep(&self) {
        let mut g = self.lock();
        g.machines
            .retain(|_, m| m.conn.is_some() || m.left.is_none_or(|l| l.elapsed() < LINGER));
        if g.saved.sweep(unix_now()) {
            self.persist(&g);
        }
        self.publish(&g);
    }
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len()
        && a.bytes()
            .zip(b.bytes())
            .fold(0u8, |acc, (x, y)| acc | (x ^ y))
            == 0
}

async fn auth(State(hub): State<Arc<Hub>>, req: Request, next: Next) -> Response {
    let ok = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| same(t.trim(), &hub.token));
    if !ok {
        return ApiError::new(StatusCode::UNAUTHORIZED, "missing or wrong token").into_response();
    }
    next.run(req).await
}

fn valid_machine(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
}

async fn node(
    State(hub): State<Arc<Hub>>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> std::result::Result<Response, ApiError> {
    let machine = headers
        .get("x-machine")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|m| valid_machine(m))
        .ok_or_else(|| {
            ApiError::new(
                StatusCode::BAD_REQUEST,
                "X-Machine must be a name of letters, digits, '-', '_' or '.'",
            )
        })?
        .to_owned();
    Ok(ws.on_upgrade(move |s| hub.serve_node(machine, s)))
}

async fn agents(State(hub): State<Arc<Hub>>) -> Response {
    let body = hub.body.borrow().clone();
    (
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

async fn events(
    State(hub): State<Arc<Hub>>,
) -> Sse<impl Stream<Item = std::result::Result<Event, Infallible>>> {
    let stream = WatchStream::new(hub.body.subscribe())
        .map(|b| Ok(Event::default().event("agents").data(b.as_str())));
    Sse::new(stream).keep_alive(KeepAlive::new().interval(PING).text("ping"))
}

#[derive(Deserialize)]
struct Lines {
    lines: Option<u32>,
}

async fn screen(
    State(hub): State<Arc<Hub>>,
    Path((machine, pane)): Path<(String, String)>,
    Query(q): Query<Lines>,
) -> std::result::Result<Json<Value>, ApiError> {
    let lines = q.lines.unwrap_or(DEFAULT_LINES).clamp(1, MAX_LINES);
    let v = hub
        .ask(&machine, &pane, |id| ToNode::Read {
            id,
            pane: pane.clone(),
            lines,
        })
        .await?;
    Ok(Json(
        json!({ "text": v["text"].as_str().unwrap_or_default() }),
    ))
}

async fn history(
    State(hub): State<Arc<Hub>>,
    Path((machine, pane)): Path<(String, String)>,
    Query(q): Query<Lines>,
) -> std::result::Result<Json<Value>, ApiError> {
    let lines = q.lines.unwrap_or(MAX_LINES).clamp(1, MAX_LINES);
    let v = hub
        .ask(&machine, &pane, |id| ToNode::History {
            id,
            pane: pane.clone(),
            lines,
        })
        .await?;
    Ok(Json(
        json!({ "text": v["text"].as_str().unwrap_or_default() }),
    ))
}

#[derive(Deserialize)]
struct PromptBody {
    text: String,
}

async fn prompt(
    State(hub): State<Arc<Hub>>,
    Path((machine, pane)): Path<(String, String)>,
    body: std::result::Result<Json<PromptBody>, JsonRejection>,
) -> std::result::Result<Json<Value>, ApiError> {
    let Json(b) = body?;
    if b.text.trim().is_empty() {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "text is empty"));
    }
    hub.ask(&machine, &pane, |id| ToNode::Prompt {
        id,
        pane: pane.clone(),
        text: b.text,
    })
    .await?;
    Ok(Json(json!({})))
}

#[derive(Deserialize)]
struct KeysBody {
    keys: Vec<String>,
}

async fn keys(
    State(hub): State<Arc<Hub>>,
    Path((machine, pane)): Path<(String, String)>,
    body: std::result::Result<Json<KeysBody>, JsonRejection>,
) -> std::result::Result<Json<Value>, ApiError> {
    let Json(b) = body?;
    if b.keys.is_empty() || b.keys.iter().any(|k| k.trim().is_empty()) {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "keys is empty"));
    }
    hub.ask(&machine, &pane, |id| ToNode::Keys {
        id,
        pane: pane.clone(),
        keys: b.keys,
    })
    .await?;
    Ok(Json(json!({})))
}

#[derive(Deserialize)]
struct ImportantBody {
    important: bool,
}

async fn important(
    State(hub): State<Arc<Hub>>,
    Path((machine, pane)): Path<(String, String)>,
    body: std::result::Result<Json<ImportantBody>, JsonRejection>,
) -> std::result::Result<Json<Value>, ApiError> {
    let Json(b) = body?;
    let mut g = hub.lock();
    let listed = g
        .machines
        .get(&machine)
        .is_some_and(|m| m.agents.iter().any(|a| a.pane == pane));
    if b.important && !listed {
        return Err(ApiError::new(
            StatusCode::NOT_FOUND,
            format!("no agent in {pane} on {machine}"),
        ));
    }
    if g.saved.set_important(&machine, &pane, b.important) {
        hub.persist(&g);
        hub.publish(&g);
    }
    Ok(Json(json!({})))
}

#[derive(Deserialize)]
struct PushBody {
    endpoint: String,
}

fn endpoint(
    body: std::result::Result<Json<PushBody>, JsonRejection>,
) -> std::result::Result<String, ApiError> {
    let Json(b) = body?;
    let e = b.endpoint.trim();
    if !(e.starts_with("https://") || e.starts_with("http://")) {
        return Err(ApiError::new(
            StatusCode::BAD_REQUEST,
            "endpoint must be an http(s) URL",
        ));
    }
    Ok(e.to_owned())
}

async fn push_add(
    State(hub): State<Arc<Hub>>,
    body: std::result::Result<Json<PushBody>, JsonRejection>,
) -> std::result::Result<Json<Value>, ApiError> {
    let e = endpoint(body)?;
    let mut g = hub.lock();
    if g.saved.add_push(&e) {
        hub.persist(&g);
    }
    Ok(Json(json!({})))
}

async fn push_remove(
    State(hub): State<Arc<Hub>>,
    body: std::result::Result<Json<PushBody>, JsonRejection>,
) -> std::result::Result<Json<Value>, ApiError> {
    let e = endpoint(body)?;
    let mut g = hub.lock();
    if g.saved.remove_push(&e) {
        hub.persist(&g);
    }
    Ok(Json(json!({})))
}

pub fn router(hub: Arc<Hub>) -> Router {
    Router::new()
        .route("/api/node", get(node))
        .route("/api/agents", get(agents))
        .route("/api/events", get(events))
        .route("/api/agents/{machine}/{pane}/screen", get(screen))
        .route("/api/agents/{machine}/{pane}/history", get(history))
        .route("/api/agents/{machine}/{pane}/prompt", post(prompt))
        .route("/api/agents/{machine}/{pane}/keys", post(keys))
        .route("/api/agents/{machine}/{pane}/important", put(important))
        .route("/api/push", post(push_add).delete(push_remove))
        .fallback(|| async { ApiError::new(StatusCode::NOT_FOUND, "not found") })
        .method_not_allowed_fallback(|| async {
            ApiError::new(StatusCode::METHOD_NOT_ALLOWED, "method not allowed")
        })
        .layer(middleware::from_fn_with_state(hub.clone(), auth))
        .with_state(hub)
}

pub fn state_file() -> PathBuf {
    config::state_dir().join("hub.json")
}

async fn shutdown() {
    let term = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut s) => {
                s.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term => {}
    }
}

pub async fn run(config: &Config, path: &std::path::Path) -> Result<()> {
    let token = config
        .token
        .clone()
        .filter(|t| !t.is_empty())
        .with_context(|| {
            format!(
                "no token in {}; make one with `herdr-pocket token`",
                path.display()
            )
        })?;
    let file = state_file();
    let saved = Saved::load(&file)?;
    let hub = Hub::new(token, file, saved);
    let listener = tokio::net::TcpListener::bind(config.listen())
        .await
        .with_context(|| format!("listening on {}", config.listen()))?;
    eprintln!("herdr-pocket hub listening on {}", listener.local_addr()?);
    let sweeper = hub.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(SWEEP).await;
            sweeper.sweep();
        }
    });
    // Open SSE streams never end on their own, so the hub exits on a signal
    // without waiting for them.
    tokio::select! {
        r = axum::serve(listener, router(hub)) => r.context("serving")?,
        _ = shutdown() => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::Status;

    fn agent(pane: &str, status: Status) -> Agent {
        Agent {
            machine: String::new(),
            pane: pane.into(),
            agent: "claude".into(),
            status,
            since: 1,
            title: String::new(),
            cwd: String::new(),
            workspace: String::new(),
            important: false,
        }
    }

    fn hub(name: &str) -> Arc<Hub> {
        let dir =
            std::env::temp_dir().join(format!("herdr-pocket-hub-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        Hub::new("t".into(), dir.join("hub.json"), Saved::default())
    }

    #[tokio::test]
    async fn body_marks_important_and_machines() {
        let h = hub("body");
        let (id, _rx) = h.connect("thor");
        h.update("thor", vec![agent("w1:p1", Status::Working)]);
        h.lock().saved.set_important("thor", "w1:p1", true);
        let b = serde_json::to_value(h.lock().body()).unwrap();
        assert_eq!(b["agents"][0]["machine"], "thor");
        assert_eq!(b["agents"][0]["important"], true);
        assert_eq!(b["machines"], json!([{"name": "thor", "connected": true}]));
        h.disconnect("thor", id);
        assert_eq!(
            serde_json::to_value(h.lock().body()).unwrap()["machines"][0]["connected"],
            false
        );
        assert_eq!(h.lock().body().agents.len(), 1);
    }

    #[tokio::test]
    async fn ask_routes_replies_and_errors() {
        let h = hub("ask");
        let err = h
            .ask("thor", "w1:p1", |id| ToNode::Read {
                id,
                pane: "w1:p1".into(),
                lines: 1,
            })
            .await
            .unwrap_err();
        assert_eq!(err.0, StatusCode::NOT_FOUND);
        let (id, mut rx) = h.connect("thor");
        h.update("thor", vec![agent("w1:p1", Status::Idle)]);
        let h2 = h.clone();
        let node = tokio::spawn(async move {
            let Some(ToNode::Read { id, .. }) = rx.recv().await else {
                panic!("expected read")
            };
            h2.reply(id, Ok(json!({"text": "hi"})));
            let Some(ToNode::Keys { id, .. }) = rx.recv().await else {
                panic!("expected keys")
            };
            h2.reply(id, Err("pane_not_found".into()));
            rx
        });
        let v = h
            .ask("thor", "w1:p1", |id| ToNode::Read {
                id,
                pane: "w1:p1".into(),
                lines: 1,
            })
            .await
            .unwrap();
        assert_eq!(v["text"], "hi");
        let e = h
            .ask("thor", "w1:p1", |id| ToNode::Keys {
                id,
                pane: "w1:p1".into(),
                keys: vec![],
            })
            .await
            .unwrap_err();
        assert_eq!(e.0, StatusCode::BAD_GATEWAY);
        let _rx = node.await.unwrap();
        assert_eq!(
            h.ask("thor", "w9:p9", |id| ToNode::Read {
                id,
                pane: "w9:p9".into(),
                lines: 1
            })
            .await
            .unwrap_err()
            .0,
            StatusCode::NOT_FOUND
        );
        h.disconnect("thor", id);
        assert_eq!(
            h.ask("thor", "w1:p1", |id| ToNode::Read {
                id,
                pane: "w1:p1".into(),
                lines: 1
            })
            .await
            .unwrap_err()
            .0,
            StatusCode::SERVICE_UNAVAILABLE
        );
    }

    #[test]
    fn tokens_compare_whole() {
        assert!(same("abc", "abc"));
        assert!(!same("abc", "abd"));
        assert!(!same("abc", "abcd"));
        assert!(valid_machine("thor-2.lan"));
        assert!(!valid_machine("a/b"));
        assert!(!valid_machine(""));
    }
}
