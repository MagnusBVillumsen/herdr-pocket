//! The agent shape shared by node, hub and app, and how a node builds it from
//! Herdr's output.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Idle,
    Working,
    Blocked,
    Done,
    #[serde(other)]
    Unknown,
}

impl Status {
    pub fn parse(s: &str) -> Status {
        serde_json::from_value(Value::String(s.into())).unwrap_or(Status::Unknown)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Agent {
    pub machine: String,
    pub pane: String,
    pub agent: String,
    pub status: Status,
    /// Unix seconds when `status` last changed.
    pub since: i64,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub cwd: String,
    #[serde(default)]
    pub workspace: String,
    #[serde(default)]
    pub important: bool,
}

fn text(v: &Value, key: &str) -> String {
    v[key].as_str().unwrap_or_default().to_owned()
}

/// Workspace id to label, from the `result` of `herdr workspace list`.
pub fn labels(workspaces: &Value) -> HashMap<String, String> {
    workspaces["workspaces"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|w| (text(w, "workspace_id"), text(w, "label")))
        .collect()
}

/// Agents from the `result` of `herdr agent list`, with `since` unset.
pub fn parse(list: &Value, labels: &HashMap<String, String>, machine: &str) -> Option<Vec<Agent>> {
    Some(
        list["agents"]
            .as_array()?
            .iter()
            .filter_map(|a| {
                let pane = a["pane_id"].as_str()?.to_owned();
                Some(Agent {
                    machine: machine.into(),
                    pane,
                    agent: text(a, "agent"),
                    status: Status::parse(a["agent_status"].as_str().unwrap_or_default()),
                    since: 0,
                    title: text(a, "terminal_title_stripped"),
                    cwd: text(a, "cwd"),
                    workspace: labels
                        .get(a["workspace_id"].as_str().unwrap_or_default())
                        .cloned()
                        .unwrap_or_default(),
                    important: false,
                })
            })
            .collect(),
    )
}

/// Remembers when each pane's status last changed, across polls. A pane seen
/// for the first time counts from now: Herdr does not say how long it has
/// been in its state.
#[derive(Default)]
pub struct Tracker {
    seen: HashMap<String, (Status, i64)>,
}

impl Tracker {
    pub fn stamp(&mut self, agents: &mut [Agent], now: i64) {
        let mut seen = HashMap::with_capacity(agents.len());
        for a in agents.iter_mut() {
            a.since = match self.seen.get(&a.pane) {
                Some(&(s, since)) if s == a.status => since,
                _ => now,
            };
            seen.insert(a.pane.clone(), (a.status, a.since));
        }
        self.seen = seen;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> (Value, Value) {
        let list: Value =
            serde_json::from_str(include_str!("../tests/data/agent-list.json")).unwrap();
        let ws: Value =
            serde_json::from_str(include_str!("../tests/data/workspace-list.json")).unwrap();
        (list["result"].clone(), ws["result"].clone())
    }

    #[test]
    fn maps_a_real_agent_list() {
        let (list, ws) = sample();
        let agents = parse(&list, &labels(&ws), "thor").unwrap();
        assert_eq!(agents.len(), 3);
        assert_eq!(
            agents[1],
            Agent {
                machine: "thor".into(),
                pane: "w1:pB".into(),
                agent: "claude".into(),
                status: Status::Working,
                since: 0,
                title: "Fix the build".into(),
                cwd: "/home/magnus".into(),
                workspace: "game".into(),
                important: false,
            }
        );
        assert_eq!(agents[0].status, Status::Idle);
        assert_eq!(agents[2].workspace, "Mono");
    }

    #[test]
    fn missing_fields_and_odd_statuses() {
        let list: Value = serde_json::json!({"agents": [
            {"pane_id": "w1:p1", "agent_status": "sleeping"},
            {"agent": "codex"}
        ]});
        let agents = parse(&list, &HashMap::new(), "m").unwrap();
        assert_eq!(agents.len(), 1);
        assert_eq!(agents[0].status, Status::Unknown);
        assert_eq!(agents[0].title, "");
        assert!(parse(&Value::Null, &HashMap::new(), "m").is_none());
        assert_eq!(Status::parse("blocked"), Status::Blocked);
    }

    #[test]
    fn since_moves_only_when_status_changes() {
        let (list, ws) = sample();
        let mut agents = parse(&list, &labels(&ws), "thor").unwrap();
        let mut t = Tracker::default();
        t.stamp(&mut agents, 100);
        assert!(agents.iter().all(|a| a.since == 100));
        agents[0].status = Status::Working;
        t.stamp(&mut agents, 200);
        assert_eq!(agents[0].since, 200);
        assert_eq!(agents[1].since, 100);
        let mut fewer = vec![agents[1].clone()];
        t.stamp(&mut fewer, 300);
        let mut back = agents.clone();
        t.stamp(&mut back, 400);
        assert_eq!(back[0].since, 400);
        assert_eq!(back[1].since, 100);
    }
}
