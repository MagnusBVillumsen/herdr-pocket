mod agent;
mod cli;
mod config;
mod herdr;
mod history;
mod hub;
mod node;
mod notify;
mod proto;
mod state;

use std::path::PathBuf;

use anyhow::Result;
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(
    version,
    about = "Follow and answer your Herdr agents on every machine from your phone"
)]
struct Cli {
    /// Config file [default: $XDG_CONFIG_HOME/herdr-pocket/config.toml]
    #[arg(long, global = true)]
    config: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Serve the app and the nodes, and send push notifications
    Hub,
    /// Bridge this machine's Herdr to the hub (Herdr's startup hook)
    Node,
    /// Print a new random token for the config
    Token,
    /// Show a QR code that pairs the app with the hub
    Pair {
        /// The hub URL as the phone reaches it [default: hub from the config]
        #[arg(long)]
        url: Option<String>,
    },
    /// Show the config, whether the hub answers, and its machines
    Status,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let path = cli.config.clone().unwrap_or_else(config::path);
    let load = || config::load(&path);
    match cli.cmd {
        Cmd::Token => {
            println!("{}", cli::token()?);
            Ok(())
        }
        Cmd::Hub => hub::run(&load()?, &path).await,
        Cmd::Node => node::run(load()?, &path).await,
        Cmd::Pair { url } => cli::pair(&load()?, &path, url),
        Cmd::Status => cli::status(&load()?, &path).await,
    }
}
