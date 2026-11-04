//! A Claude Code conversation as text, from its transcript. Claude Code draws
//! on the alternate screen, so the pane itself holds little more than what is
//! visible now.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde_json::Value;

use crate::config;

/// The tail of a transcript to read; a long session runs to hundreds of MB.
const TAIL_BYTES: u64 = 16 * 1024 * 1024;
const TOOL_INPUT_CHARS: usize = 120;

/// The session id of `pane`, from the `result` of `herdr agent list`, for an
/// agent whose transcript we can read.
pub fn claude_session(agents: &Value, pane: &str) -> Option<String> {
    let a = agents["agents"]
        .as_array()?
        .iter()
        .find(|a| a["pane_id"] == pane)?;
    if a["agent"] != "claude" {
        return None;
    }
    let id = a["agent_session"]["value"].as_str()?;
    let safe = !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-');
    safe.then(|| id.to_owned())
}

/// Sessions live under a directory named after the directory they started in,
/// which may not be the pane's cwd by now, so look in all of them.
fn find_transcript(projects: &Path, session: &str) -> Option<PathBuf> {
    let name = format!("{session}.jsonl");
    std::fs::read_dir(projects)
        .ok()?
        .flatten()
        .map(|d| d.path().join(&name))
        .find(|p| p.is_file())
}

fn read_tail(path: &Path) -> Result<String> {
    let mut f = File::open(path).with_context(|| format!("opening {}", path.display()))?;
    let len = f.metadata()?.len();
    let start = len.saturating_sub(TAIL_BYTES);
    f.seek(SeekFrom::Start(start))?;
    let mut bytes = Vec::new();
    f.read_to_end(&mut bytes)?;
    let text = String::from_utf8_lossy(&bytes).into_owned();
    if start == 0 {
        return Ok(text);
    }
    // The first line is cut in half.
    Ok(text
        .split_once('\n')
        .map(|(_, rest)| rest.to_owned())
        .unwrap_or_default())
}

fn tool_line(block: &Value) -> String {
    let name = block["name"].as_str().unwrap_or("tool");
    let input = &block["input"];
    let detail = ["description", "command", "file_path", "pattern", "prompt"]
        .iter()
        .find_map(|k| input[k].as_str())
        .unwrap_or_default();
    let short: String = detail
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(TOOL_INPUT_CHARS)
        .collect();
    format!("● {name}: {short}")
        .trim_end_matches([' ', ':'])
        .to_owned()
}

/// What one transcript line adds to the conversation, if anything. Thinking and
/// tool output stay out; a tool call is one line.
fn entry(line: &str) -> Option<String> {
    let v: Value = serde_json::from_str(line).ok()?;
    let user = match v["type"].as_str()? {
        "user" => true,
        "assistant" => false,
        _ => return None,
    };
    if v["isSidechain"] == true || v["isMeta"] == true {
        return None;
    }
    let content = &v["message"]["content"];
    let parts: Vec<String> = match content {
        Value::String(s) => vec![s.clone()],
        Value::Array(blocks) => blocks
            .iter()
            .filter_map(|b| match b["type"].as_str()? {
                "text" => b["text"].as_str().map(str::to_owned),
                "tool_use" => Some(tool_line(b)),
                _ => None,
            })
            .collect(),
        _ => return None,
    };
    let text = parts.join("\n");
    let text = text.trim();
    // Reminders and command output that the harness adds as user messages.
    if text.is_empty() || (user && text.starts_with('<')) {
        return None;
    }
    Some(if user {
        format!("❯ {text}")
    } else {
        text.to_owned()
    })
}

/// The last `lines` lines of the conversation in `transcript`.
fn conversation(transcript: &str, lines: usize) -> String {
    let all: Vec<String> = transcript.lines().filter_map(entry).collect();
    let joined = all.join("\n\n");
    let rows: Vec<&str> = joined.lines().collect();
    rows[rows.len().saturating_sub(lines)..].join("\n")
}

/// The conversation of `session`, or nothing if its transcript is not here.
pub fn read(session: &str, lines: usize) -> Result<String> {
    let projects = config::home().join(".claude/projects");
    match find_transcript(&projects, session) {
        Some(p) => Ok(conversation(&read_tail(&p)?, lines)),
        None => Ok(String::new()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn finds_the_session_of_a_claude_pane() {
        let agents: Value =
            serde_json::from_str(include_str!("../tests/data/agent-list.json")).unwrap();
        let session = claude_session(&agents["result"], "w1:pB");
        assert_eq!(
            session.as_deref(),
            Some("00000000-0000-4000-8000-000000000002")
        );
        assert_eq!(claude_session(&agents["result"], "w9:p9"), None);
    }

    #[test]
    fn ignores_other_agents_and_unsafe_ids() {
        let of = |agent: &str, id: &str| json!({"agents": [{"pane_id": "p", "agent": agent, "agent_session": {"value": id}}]});
        assert_eq!(claude_session(&of("codex", "abc"), "p"), None);
        assert_eq!(claude_session(&of("claude", "../../etc/passwd"), "p"), None);
        assert_eq!(
            claude_session(&of("claude", "ab-1"), "p").as_deref(),
            Some("ab-1")
        );
    }

    #[test]
    fn keeps_the_conversation_and_drops_the_rest() {
        let lines = [
            json!({"type": "mode", "mode": "x"}),
            json!({"type": "user", "message": {"content": "fix the build"}}),
            json!({"type": "user", "isMeta": true, "message": {"content": "caveat"}}),
            json!({"type": "user", "message": {"content": "<system-reminder>x</system-reminder>"}}),
            json!({"type": "assistant", "message": {"content": [
                {"type": "thinking", "thinking": "secret"},
                {"type": "text", "text": "On it."},
                {"type": "tool_use", "name": "Bash", "input": {"description": "Run  the\ntests"}},
            ]}}),
            json!({"type": "user", "message": {"content": [{"type": "tool_result", "content": "ok"}]}}),
            json!({"type": "assistant", "isSidechain": true, "message": {"content": [{"type": "text", "text": "sub"}]}}),
            json!({"type": "assistant", "message": {"content": [{"type": "text", "text": "Done."}]}}),
        ];
        let t: Vec<String> = lines.iter().map(Value::to_string).collect();
        let want = "❯ fix the build\n\nOn it.\n● Bash: Run the tests\n\nDone.";
        assert_eq!(
            conversation(&format!("{}\nnot json", t.join("\n")), 100),
            want
        );
        assert_eq!(conversation(&t.join("\n"), 2), "\nDone.");
    }

    #[test]
    fn finds_a_transcript_under_any_project() {
        let dir = std::env::temp_dir().join(format!("herdr-pocket-history-{}", std::process::id()));
        let project = dir.join("-home-magnus-dev");
        std::fs::create_dir_all(&project).unwrap();
        let file = project.join("abc.jsonl");
        std::fs::write(&file, "x").unwrap();
        assert_eq!(find_transcript(&dir, "abc"), Some(file));
        assert_eq!(find_transcript(&dir, "nope"), None);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
