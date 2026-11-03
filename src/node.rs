//! The node: Herdr's startup hook on each machine. Polls Herdr, keeps the hub
//! up to date and runs what the hub asks for.

use std::fs::File;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

use crate::agent::{self, Agent, Tracker};
use crate::config::{self, Config};
use crate::herdr;
use crate::hub::unix_now;
use crate::proto::{FromNode, ToNode};

const POLL: Duration = Duration::from_secs(2);
const CONFIG_WAIT: Duration = Duration::from_secs(15);
const LOCK_WAIT: Duration = Duration::from_secs(3);
/// Failed `herdr agent list` calls in a row before the node gives up.
const HERDR_GONE: u32 = 3;
const BACKOFF_MIN: Duration = Duration::from_secs(1);
const BACKOFF_MAX: Duration = Duration::from_secs(30);
const PING: Duration = Duration::from_secs(20);
const SILENT: Duration = Duration::from_secs(60);

pub fn lock_file() -> PathBuf {
    config::runtime_dir().join("node.lock")
}

/// Takes the node lock, or returns `None` if another node holds it. The
/// kernel releases it when this process exits, however it exits.
pub fn lock() -> Result<Option<File>> {
    let path = lock_file();
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let f = File::create(&path).with_context(|| format!("opening {}", path.display()))?;
    match f.try_lock() {
        Ok(()) => Ok(Some(f)),
        Err(std::fs::TryLockError::WouldBlock) => Ok(None),
        Err(std::fs::TryLockError::Error(e)) => Err(e).context("locking the node"),
    }
}

/// `http://host:port` → `ws://host:port/api/node`.
pub fn ws_url(hub: &str) -> Result<String> {
    let hub = hub.trim_end_matches('/');
    let rest = if let Some(r) = hub.strip_prefix("https://") {
        format!("wss://{r}")
    } else if let Some(r) = hub.strip_prefix("http://") {
        format!("ws://{r}")
    } else if hub.starts_with("ws://") || hub.starts_with("wss://") {
        hub.to_owned()
    } else {
        bail!("hub must be an http(s) URL, not {hub}");
    };
    Ok(format!("{rest}/api/node"))
}

async fn poll(machine: &str, tracker: &mut Tracker) -> Result<Vec<Agent>> {
    let list = herdr::agents().await?;
    // Labels are a nicety; an agent list without them is still worth sending.
    let labels = herdr::workspaces()
        .await
        .map(|w| agent::labels(&w))
        .unwrap_or_default();
    let mut agents = agent::parse(&list, &labels, machine).context("unexpected agent list")?;
    tracker.stamp(&mut agents, unix_now());
    Ok(agents)
}

async fn execute(m: ToNode) -> FromNode {
    match m {
        ToNode::Read { id, pane, lines } => FromNode::reply(
            id,
            herdr::read(&pane, lines)
                .await
                .map(|t| json!({ "text": t })),
        ),
        ToNode::Prompt { id, pane, text } => {
            FromNode::reply(id, herdr::prompt(&pane, &text).await.map(|_| json!({})))
        }
        ToNode::Keys { id, pane, keys } => {
            FromNode::reply(id, herdr::keys(&pane, &keys).await.map(|_| json!({})))
        }
    }
}

/// The id of a request, to answer one the node cannot parse.
fn request_id(text: &str) -> Option<u64> {
    serde_json::from_str::<Value>(text).ok()?["id"].as_u64()
}

fn frame(m: &FromNode) -> Message {
    Message::Text(serde_json::to_string(m).unwrap_or_default().into())
}

/// One connection, until it drops.
async fn session(
    url: &str,
    token: &str,
    machine: &str,
    agents: &mut watch::Receiver<Vec<Agent>>,
) -> Result<()> {
    let mut req = url.into_client_request()?;
    let h = req.headers_mut();
    h.insert(
        "authorization",
        HeaderValue::from_str(&format!("Bearer {token}"))?,
    );
    h.insert("x-machine", HeaderValue::from_str(machine)?);
    let (ws, _) = tokio_tungstenite::connect_async(req)
        .await
        .context("connecting to the hub")?;
    eprintln!("connected to {url}");
    let (mut sink, mut stream) = ws.split();
    let (rtx, mut rrx) = mpsc::unbounded_channel::<FromNode>();
    let first = agents.borrow_and_update().clone();
    sink.send(frame(&FromNode::Agents { agents: first }))
        .await?;
    let mut ping = tokio::time::interval(PING);
    ping.tick().await;
    let mut heard = Instant::now();
    loop {
        tokio::select! {
            r = agents.changed() => {
                r.context("poller stopped")?;
                let now = agents.borrow_and_update().clone();
                sink.send(frame(&FromNode::Agents { agents: now })).await?;
            }
            Some(r) = rrx.recv() => sink.send(frame(&r)).await?,
            _ = ping.tick() => {
                if heard.elapsed() > SILENT {
                    bail!("the hub went silent");
                }
                sink.send(Message::Ping(Default::default())).await?;
            }
            f = stream.next() => {
                let f = match f {
                    Some(f) => f?,
                    None => bail!("the hub closed the connection"),
                };
                heard = Instant::now();
                match f {
                    Message::Text(t) => match serde_json::from_str::<ToNode>(t.as_str()) {
                        Ok(m) => {
                            let rtx = rtx.clone();
                            tokio::spawn(async move {
                                let _ = rtx.send(execute(m).await);
                            });
                        }
                        Err(e) => {
                            if let Some(id) = request_id(t.as_str()) {
                                let err = anyhow::anyhow!("unknown request: {e}");
                                sink.send(frame(&FromNode::reply(id, Err(err)))).await?;
                            }
                        }
                    },
                    Message::Close(_) => bail!("the hub closed the connection"),
                    _ => {}
                }
            }
        }
    }
}

/// Keeps a connection to the hub, reconnecting with backoff.
async fn connect_loop(
    url: String,
    token: String,
    machine: String,
    mut agents: watch::Receiver<Vec<Agent>>,
) {
    let mut wait = BACKOFF_MIN;
    loop {
        let start = Instant::now();
        if let Err(e) = session(&url, &token, &machine, &mut agents).await {
            eprintln!("{e:#}");
        }
        // A connection that lasted a while was a good one: retry soon.
        if start.elapsed() > BACKOFF_MAX {
            wait = BACKOFF_MIN;
        }
        tokio::time::sleep(wait).await;
        wait = (wait * 2).min(BACKOFF_MAX);
    }
}

/// Waits for a config with both `hub` and `token`, re-reading it every
/// [CONFIG_WAIT], so a token added after Herdr started needs no restart.
/// Returns `None` once Herdr is gone.
async fn wait_for_config(
    mut config: Config,
    path: &std::path::Path,
) -> Option<(String, String, Config)> {
    let mut dog = Watchdog::new(config.machine());
    let mut told = false;
    loop {
        if let (Some(h), Some(t)) = (config.hub.clone(), config.token.clone()) {
            return Some((h, t, config));
        }
        if !told {
            eprintln!(
                "herdr-pocket: no hub or token in {}; waiting for them",
                path.display()
            );
            told = true;
        }
        tokio::time::sleep(CONFIG_WAIT).await;
        if !dog.alive().await {
            return None;
        }
        match config::load(path) {
            Ok(c) => config = c,
            Err(e) => eprintln!("{e:#}"),
        }
    }
}

/// Herdr is gone once it has failed [HERDR_GONE] checks in a row.
struct Watchdog {
    machine: String,
    misses: u32,
}

impl Watchdog {
    fn new(machine: String) -> Self {
        Watchdog { machine, misses: 0 }
    }

    async fn alive(&mut self) -> bool {
        if poll(&self.machine, &mut Tracker::default()).await.is_ok() {
            self.misses = 0;
        } else {
            self.misses += 1;
        }
        self.misses < HERDR_GONE
    }
}

/// Waits for the node lock instead of giving up, so a node that Herdr starts
/// while an older one is still winding down takes over when that one exits.
async fn wait_for_lock(machine: &str) -> Result<Option<File>> {
    let mut dog = Watchdog::new(machine.to_owned());
    let mut told = false;
    loop {
        if let Some(l) = lock()? {
            return Ok(Some(l));
        }
        if !told {
            eprintln!("herdr-pocket: another node runs here; waiting to take over");
            told = true;
        }
        tokio::time::sleep(LOCK_WAIT).await;
        if !dog.alive().await {
            return Ok(None);
        }
    }
}

pub async fn run(config: Config, path: &std::path::Path) -> Result<()> {
    let Some(_lock) = wait_for_lock(&config.machine()).await? else {
        return Ok(());
    };
    let Some((hub, token, config)) = wait_for_config(config, path).await else {
        return Ok(());
    };
    let token = token.as_str();
    let url = ws_url(&hub)?;
    let machine = config.machine();
    let mut tracker = Tracker::default();
    let mut misses = 0;
    let first = loop {
        match poll(&machine, &mut tracker).await {
            Ok(a) => break a,
            Err(e) => {
                misses += 1;
                if misses >= HERDR_GONE {
                    eprintln!("herdr is not answering ({e:#}); stopping");
                    return Ok(());
                }
                tokio::time::sleep(POLL).await;
            }
        }
    };
    let (tx, rx) = watch::channel(first);
    tokio::spawn(connect_loop(url, token.to_owned(), machine.clone(), rx));
    misses = 0;
    loop {
        tokio::time::sleep(POLL).await;
        match poll(&machine, &mut tracker).await {
            Ok(a) => {
                misses = 0;
                tx.send_if_modified(|cur| {
                    if *cur == a {
                        return false;
                    }
                    *cur = a;
                    true
                });
            }
            Err(e) => {
                misses += 1;
                if misses >= HERDR_GONE {
                    eprintln!("herdr is gone ({e:#}); stopping");
                    return Ok(());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hub_urls() {
        assert_eq!(
            ws_url("http://heimdall:8790").unwrap(),
            "ws://heimdall:8790/api/node"
        );
        assert_eq!(
            ws_url("https://hub.ts.net/").unwrap(),
            "wss://hub.ts.net/api/node"
        );
        assert!(ws_url("heimdall:8790").is_err());
        assert_eq!(request_id(r#"{"type":"zap","id":4}"#), Some(4));
    }
}
