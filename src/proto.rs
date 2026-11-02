//! WebSocket messages between node and hub.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::agent::Agent;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum FromNode {
    Agents {
        agents: Vec<Agent>,
    },
    Reply {
        id: u64,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        result: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
}

impl FromNode {
    pub fn reply(id: u64, r: anyhow::Result<Value>) -> FromNode {
        match r {
            Ok(v) => FromNode::Reply {
                id,
                ok: true,
                result: Some(v),
                error: None,
            },
            Err(e) => FromNode::Reply {
                id,
                ok: false,
                result: None,
                error: Some(format!("{e:#}")),
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum ToNode {
    Read {
        id: u64,
        pane: String,
        lines: u32,
    },
    Prompt {
        id: u64,
        pane: String,
        text: String,
    },
    Keys {
        id: u64,
        pane: String,
        keys: Vec<String>,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_documented_frames() {
        let m: ToNode = serde_json::from_str(
            r#"{"type": "keys", "id": 9, "pane": "w1:p2", "keys": ["Enter"]}"#,
        )
        .unwrap();
        assert_eq!(
            m,
            ToNode::Keys {
                id: 9,
                pane: "w1:p2".into(),
                keys: vec!["Enter".into()]
            }
        );
        let r = serde_json::to_string(&FromNode::reply(7, Err(anyhow::anyhow!("nope")))).unwrap();
        assert_eq!(r, r#"{"type":"reply","id":7,"ok":false,"error":"nope"}"#);
        let r: FromNode =
            serde_json::from_str(r#"{"type":"reply","id":7,"ok":true,"result":{}}"#).unwrap();
        assert!(matches!(r, FromNode::Reply { ok: true, .. }));
    }
}
