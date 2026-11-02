//! The `herdr` CLI, the node's only way to talk to Herdr.

use anyhow::{Context, Result, bail};
use serde_json::Value;
use tokio::process::Command;

fn bin() -> String {
    std::env::var("HERDR_BIN_PATH").unwrap_or_else(|_| "herdr".into())
}

/// Runs herdr and returns its stdout, or Herdr's own error message.
async fn run(args: &[&str]) -> Result<Vec<u8>> {
    let out = Command::new(bin())
        .args(args)
        .kill_on_drop(true)
        .output()
        .await
        .context("running herdr")?;
    if !out.status.success() {
        let v: Value = serde_json::from_slice(&out.stdout).unwrap_or_default();
        let msg = match v["error"]["message"].as_str() {
            Some(m) => m.to_owned(),
            None => String::from_utf8_lossy(&out.stderr).trim().to_owned(),
        };
        bail!("herdr {}: {msg}", args[..2.min(args.len())].join(" "));
    }
    Ok(out.stdout)
}

/// The `result` of a JSON command.
async fn json(args: &[&str]) -> Result<Value> {
    let out = run(args).await?;
    let mut v: Value = serde_json::from_slice(&out).context("parsing herdr output")?;
    Ok(v["result"].take())
}

pub async fn agents() -> Result<Value> {
    json(&["agent", "list"]).await
}

pub async fn workspaces() -> Result<Value> {
    json(&["workspace", "list"]).await
}

pub async fn read(pane: &str, lines: u32) -> Result<String> {
    let n = lines.to_string();
    let out = run(&[
        "pane",
        "read",
        pane,
        "--source",
        "recent-unwrapped",
        "--lines",
        &n,
    ])
    .await?;
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Herdr's CLI takes any argument that starts with `-` for an option and has
/// no `--`, so such a prompt gets a leading space, which agents ignore.
fn prompt_arg(text: &str) -> String {
    if text.starts_with('-') {
        format!(" {text}")
    } else {
        text.to_owned()
    }
}

pub async fn prompt(pane: &str, text: &str) -> Result<()> {
    run(&["agent", "prompt", pane, &prompt_arg(text)]).await?;
    Ok(())
}

pub async fn keys(pane: &str, keys: &[String]) -> Result<()> {
    if let Some(k) = keys.iter().find(|k| k.starts_with('-')) {
        bail!("not a key: {k}");
    }
    let mut args: Vec<&str> = vec!["pane", "send-keys", pane];
    args.extend(keys.iter().map(String::as_str));
    run(&args).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompts_never_look_like_options() {
        assert_eq!(prompt_arg("-v please"), " -v please");
        assert_eq!(prompt_arg("fix it"), "fix it");
    }
}
