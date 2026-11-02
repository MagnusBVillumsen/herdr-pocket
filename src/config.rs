use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, Result};
use serde::Deserialize;

pub const DEFAULT_LISTEN: &str = "127.0.0.1:8790";

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    /// The hub a node connects to, such as `http://heimdall:8790`.
    pub hub: Option<String>,
    pub token: Option<String>,
    /// Where the hub listens.
    pub listen: Option<String>,
    /// This machine's name as the hub shows it.
    pub machine: Option<String>,
}

impl Config {
    pub fn listen(&self) -> &str {
        self.listen.as_deref().unwrap_or(DEFAULT_LISTEN)
    }

    pub fn machine(&self) -> String {
        self.machine.clone().unwrap_or_else(hostname)
    }
}

pub fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

fn xdg(var: &str, fallback: &str) -> PathBuf {
    std::env::var_os(var)
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(fallback))
        .join("herdr-pocket")
}

pub fn path() -> PathBuf {
    xdg("XDG_CONFIG_HOME", ".config").join("config.toml")
}

pub fn state_dir() -> PathBuf {
    xdg("XDG_STATE_HOME", ".local/state")
}

/// Where the node lock lives. The runtime dir is cleared on logout, so a
/// stale file never outlives the session that made it.
pub fn runtime_dir() -> PathBuf {
    std::env::var_os("XDG_RUNTIME_DIR")
        .filter(|v| !v.is_empty())
        .map(|d| PathBuf::from(d).join("herdr-pocket"))
        .unwrap_or_else(state_dir)
}

/// A missing file is an empty config: `token` and `pair` work without one.
pub fn load(path: &std::path::Path) -> Result<Config> {
    match std::fs::read_to_string(path) {
        Ok(raw) => toml::from_str(&raw).with_context(|| format!("parsing {}", path.display())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Config::default()),
        Err(e) => Err(e).with_context(|| format!("reading {}", path.display())),
    }
}

/// The short hostname.
pub fn hostname() -> String {
    Command::new("uname")
        .arg("-n")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_owned())
        .and_then(|h| h.split('.').next().map(String::from))
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| "localhost".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_documented_config() {
        let c: Config = toml::from_str(
            r#"
hub = "http://heimdall:8790"
token = "abc"
listen = "0.0.0.0:8790"
machine = "thor"
"#,
        )
        .unwrap();
        assert_eq!(c.hub.as_deref(), Some("http://heimdall:8790"));
        assert_eq!(c.listen(), "0.0.0.0:8790");
        assert_eq!(c.machine(), "thor");
        assert_eq!(Config::default().listen(), DEFAULT_LISTEN);
    }
}
