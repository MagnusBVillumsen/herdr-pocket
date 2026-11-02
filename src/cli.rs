//! The small commands: token, pair and status.

use std::io::Read;
use std::path::Path;
use std::time::Duration;

use anyhow::{Context, Result};
use qrcode::QrCode;
use qrcode::render::unicode::Dense1x2;
use serde_json::Value;

use crate::config::{self, Config};
use crate::{hub, node};

pub fn token() -> Result<String> {
    let mut b = [0u8; 32];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut b))
        .context("reading /dev/urandom")?;
    Ok(b.iter().map(|x| format!("{x:02x}")).collect())
}

fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub fn pair_link(url: &str, token: &str) -> String {
    format!(
        "herdr-pocket://pair?url={}&token={}",
        encode(url),
        encode(token)
    )
}

/// The hub's URL: `hub` from the config, or this machine on the listen port,
/// which is what the hub host itself has.
fn hub_url(config: &Config) -> String {
    if let Some(h) = &config.hub {
        return h.trim_end_matches('/').to_owned();
    }
    let port = config.listen().rsplit(':').next().unwrap_or("8790");
    format!("http://{}:{port}", config::hostname())
}

pub fn pair(config: &Config, path: &Path, url: Option<String>) -> Result<()> {
    let token = config.token.as_deref().with_context(|| {
        format!(
            "no token in {}; make one with `herdr-pocket token`",
            path.display()
        )
    })?;
    let url = url.unwrap_or_else(|| hub_url(config));
    let link = pair_link(&url, token);
    let code = QrCode::new(link.as_bytes()).context("making the QR code")?;
    let qr = code
        .render::<Dense1x2>()
        .dark_color(Dense1x2::Light)
        .light_color(Dense1x2::Dark)
        .quiet_zone(true)
        .build();
    println!("{qr}");
    println!("Scan with the herdr-pocket app, or open on the phone:\n{link}");
    if url.contains("127.0.0.1") || url.contains("localhost") {
        eprintln!(
            "note: {url} only works on this machine; pass --url with the address the phone uses"
        );
    }
    Ok(())
}

pub async fn status(config: &Config, path: &Path) -> Result<()> {
    let exists = path.exists();
    println!(
        "config   {}{}",
        path.display(),
        if exists { "" } else { " (missing)" }
    );
    println!("machine  {}", config.machine());
    println!(
        "token    {}",
        if config.token.is_some() {
            "set"
        } else {
            "not set"
        }
    );
    match &config.hub {
        Some(h) => println!("hub      {h}"),
        None => println!("hub      not set (nodes need it)"),
    }
    println!("listen   {} (hub only)", config.listen());
    println!("state    {}", hub::state_file().display());
    println!(
        "node     {}",
        match node::lock()? {
            Some(_) => "not running here",
            None => "running here",
        }
    );

    let url = match &config.hub {
        Some(h) => h.trim_end_matches('/').to_owned(),
        None => format!("http://{}", config.listen().replace("0.0.0.0", "127.0.0.1")),
    };
    let Some(token) = &config.token else {
        return Ok(());
    };
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()?;
    let res = client
        .get(format!("{url}/api/agents"))
        .bearer_auth(token)
        .send()
        .await;
    let body: Value = match res {
        Err(e) => {
            println!("\n{url}: not reachable ({e})");
            return Ok(());
        }
        Ok(r) if !r.status().is_success() => {
            println!("\n{url}: {}", r.status());
            return Ok(());
        }
        Ok(r) => r.json().await.context("reading the hub's answer")?,
    };
    println!("\n{url}: reachable");
    let agents = body["agents"].as_array().cloned().unwrap_or_default();
    for m in body["machines"].as_array().into_iter().flatten() {
        let name = m["name"].as_str().unwrap_or_default();
        let n = agents.iter().filter(|a| a["machine"] == name).count();
        let state = if m["connected"] == true {
            "connected"
        } else {
            "disconnected"
        };
        println!(
            "  {name:<12} {state:<13} {n} agent{}",
            if n == 1 { "" } else { "s" }
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pair_links_are_encoded() {
        assert_eq!(
            pair_link("http://heimdall:8790", "ab12"),
            "herdr-pocket://pair?url=http%3A%2F%2Fheimdall%3A8790&token=ab12"
        );
        let t = token().unwrap();
        assert_eq!(t.len(), 64);
        assert_ne!(t, token().unwrap());
    }
}
