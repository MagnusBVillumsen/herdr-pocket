//! Which status changes notify, and sending them to UnifiedPush endpoints.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::agent::{Agent, Status};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Push {
    pub machine: String,
    pub pane: String,
    pub agent: String,
    pub status: Status,
    pub title: String,
    pub event: String,
}

pub fn event(from: Status, to: Status) -> Option<&'static str> {
    match (from, to) {
        (Status::Working, Status::Done | Status::Idle) => Some("finished"),
        (f, Status::Blocked) if f != Status::Blocked => Some("needs you"),
        _ => None,
    }
}

/// Notifications for the important agents in `next` whose status changed
/// since `prev`. An agent not in `prev` has no change to report.
pub fn events(prev: &[Agent], next: &[Agent], important: impl Fn(&Agent) -> bool) -> Vec<Push> {
    next.iter()
        .filter(|a| important(a))
        .filter_map(|a| {
            let p = prev.iter().find(|p| p.pane == a.pane)?;
            let ev = event(p.status, a.status)?;
            Some(Push {
                machine: a.machine.clone(),
                pane: a.pane.clone(),
                agent: a.agent.clone(),
                status: a.status,
                title: a.title.clone(),
                event: ev.into(),
            })
        })
        .collect()
}

pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap_or_default()
}

/// Posts `push` to every endpoint and returns the ones that are gone for good.
pub async fn send(client: &reqwest::Client, endpoints: &[String], push: &Push) -> Vec<String> {
    let mut dead = Vec::new();
    for e in endpoints {
        match client.post(e).json(push).send().await {
            Ok(r) if matches!(r.status().as_u16(), 404 | 410) => dead.push(e.clone()),
            Ok(r) if !r.status().is_success() => {
                eprintln!("push to {e}: {}", r.status());
            }
            Ok(_) => {}
            Err(err) => eprintln!("push to {e}: {err}"),
        }
    }
    dead
}

#[cfg(test)]
mod tests {
    use super::*;

    fn agent(pane: &str, status: Status) -> Agent {
        Agent {
            machine: "thor".into(),
            pane: pane.into(),
            agent: "claude".into(),
            status,
            since: 0,
            title: "Fix the build".into(),
            cwd: String::new(),
            workspace: String::new(),
            important: false,
        }
    }

    #[test]
    fn transitions() {
        use Status::*;
        assert_eq!(event(Working, Done), Some("finished"));
        assert_eq!(event(Working, Idle), Some("finished"));
        assert_eq!(event(Idle, Blocked), Some("needs you"));
        assert_eq!(event(Working, Blocked), Some("needs you"));
        assert_eq!(event(Unknown, Blocked), Some("needs you"));
        assert_eq!(event(Blocked, Blocked), None);
        assert_eq!(event(Blocked, Done), None);
        assert_eq!(event(Idle, Done), None);
        assert_eq!(event(Idle, Working), None);
        assert_eq!(event(Working, Unknown), None);
    }

    #[test]
    fn only_important_changes_notify() {
        let prev = [
            agent("w1:p1", Status::Working),
            agent("w1:p2", Status::Working),
        ];
        let next = [
            agent("w1:p1", Status::Done),
            agent("w1:p2", Status::Done),
            agent("w1:p3", Status::Blocked),
        ];
        let out = events(&prev, &next, |a| a.pane != "w1:p2");
        assert_eq!(
            out,
            [Push {
                machine: "thor".into(),
                pane: "w1:p1".into(),
                agent: "claude".into(),
                status: Status::Done,
                title: "Fix the build".into(),
                event: "finished".into(),
            }]
        );
        assert!(events(&next, &next, |_| true).is_empty());
        let json = serde_json::to_value(&out[0]).unwrap();
        assert_eq!(json["status"], "done");
        assert_eq!(json["event"], "finished");
    }
}
