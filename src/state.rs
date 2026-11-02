//! What the hub keeps across restarts: important chats and push endpoints.

use std::path::Path;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

/// How long an important pane may be missing from its node's list before
/// the flag is dropped.
pub const FORGET_AFTER: i64 = 10 * 60;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Important {
    pub machine: String,
    pub pane: String,
    /// Unix seconds since the pane has been missing from its node's list.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gone: Option<i64>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Saved {
    #[serde(default)]
    pub important: Vec<Important>,
    #[serde(default)]
    pub push: Vec<String>,
}

impl Saved {
    pub fn load(path: &Path) -> Result<Saved> {
        match std::fs::read_to_string(path) {
            Ok(raw) => {
                serde_json::from_str(&raw).with_context(|| format!("parsing {}", path.display()))
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Saved::default()),
            Err(e) => Err(e).with_context(|| format!("reading {}", path.display())),
        }
    }

    /// Writes through a temporary file, so a crash never leaves half a file.
    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let tmp = path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(self)?)
            .with_context(|| format!("writing {}", tmp.display()))?;
        std::fs::rename(&tmp, path).with_context(|| format!("writing {}", path.display()))
    }

    pub fn is_important(&self, machine: &str, pane: &str) -> bool {
        self.important
            .iter()
            .any(|i| i.machine == machine && i.pane == pane)
    }

    /// Returns whether anything changed.
    pub fn set_important(&mut self, machine: &str, pane: &str, on: bool) -> bool {
        let had = self.is_important(machine, pane);
        if on && !had {
            self.important.push(Important {
                machine: machine.into(),
                pane: pane.into(),
                gone: None,
            });
        } else if !on && had {
            self.important
                .retain(|i| !(i.machine == machine && i.pane == pane));
        }
        on != had
    }

    pub fn add_push(&mut self, endpoint: &str) -> bool {
        if self.push.iter().any(|p| p == endpoint) {
            return false;
        }
        self.push.push(endpoint.into());
        true
    }

    pub fn remove_push(&mut self, endpoint: &str) -> bool {
        let n = self.push.len();
        self.push.retain(|p| p != endpoint);
        n != self.push.len()
    }

    /// Starts or stops the clock on `machine`'s important panes, given the
    /// panes its node lists now.
    pub fn observe(&mut self, machine: &str, panes: &[&str], now: i64) -> bool {
        let mut changed = false;
        for i in self.important.iter_mut().filter(|i| i.machine == machine) {
            let gone = match (panes.contains(&i.pane.as_str()), i.gone) {
                (true, _) => None,
                (false, None) => Some(now),
                (false, g) => g,
            };
            changed |= gone != i.gone;
            i.gone = gone;
        }
        changed
    }

    /// Drops flags whose pane has been gone for too long.
    pub fn sweep(&mut self, now: i64) -> bool {
        let n = self.important.len();
        self.important
            .retain(|i| i.gone.is_none_or(|g| now - g < FORGET_AFTER));
        n != self.important.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("herdr-pocket-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d.join("hub.json")
    }

    #[test]
    fn round_trips_through_the_file() {
        let path = tmp("roundtrip");
        assert_eq!(Saved::load(&path).unwrap(), Saved::default());
        let mut s = Saved::default();
        assert!(s.set_important("thor", "w1:p2", true));
        assert!(!s.set_important("thor", "w1:p2", true));
        assert!(s.add_push("https://ntfy.example/up1"));
        assert!(!s.add_push("https://ntfy.example/up1"));
        s.observe("thor", &[], 50);
        s.save(&path).unwrap();
        let back = Saved::load(&path).unwrap();
        assert_eq!(back, s);
        assert_eq!(back.important[0].gone, Some(50));
        assert!(!path.with_extension("json.tmp").exists());
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }

    #[test]
    fn reads_a_file_without_optional_fields() {
        let path = tmp("minimal");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(
            &path,
            r#"{"important": [{"machine": "a", "pane": "w1:p1"}]}"#,
        )
        .unwrap();
        let s = Saved::load(&path).unwrap();
        assert!(s.is_important("a", "w1:p1"));
        assert!(s.push.is_empty());
        std::fs::write(&path, "{").unwrap();
        assert!(Saved::load(&path).is_err());
        std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }

    #[test]
    fn forgets_panes_gone_for_ten_minutes() {
        let mut s = Saved::default();
        s.set_important("thor", "w1:p1", true);
        s.set_important("thor", "w1:p2", true);
        s.set_important("odin", "w1:p1", true);
        assert!(s.observe("thor", &["w1:p2"], 1000));
        assert!(!s.observe("thor", &["w1:p2"], 1100));
        assert_eq!(s.important[0].gone, Some(1000));
        assert!(!s.sweep(1000 + FORGET_AFTER - 1));
        s.observe("thor", &["w1:p1", "w1:p2"], 1200);
        assert!(!s.sweep(5000));
        s.observe("thor", &[], 2000);
        assert!(s.sweep(2000 + FORGET_AFTER));
        assert!(!s.is_important("thor", "w1:p1"));
        assert!(!s.is_important("thor", "w1:p2"));
        assert!(s.is_important("odin", "w1:p1"));
        assert!(s.set_important("odin", "w1:p1", false));
        assert!(!s.remove_push("x"));
    }
}
