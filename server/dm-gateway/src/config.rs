use anyhow::{bail, Context, Result};
use std::{env, net::{IpAddr, SocketAddr}};

pub struct ServeConfig {
    pub tunnel_listen: SocketAddr,
    pub game_bind: IpAddr,
    pub game_ports: (u16, u16),
}

pub fn parse_args() -> Result<Option<ServeConfig>> {
    let mut args: Vec<String> = env::args().skip(1).collect();
    if args.first().is_some_and(|arg| arg == "--help" || arg == "-h") {
        return Ok(None);
    }
    if args.first().is_some_and(|arg| arg == "serve") {
        args.remove(0);
    } else if args.first().is_some_and(|arg| arg == "host") {
        bail!("the host command is no longer supported; host integration is provided by the Minecraft client")
    } else if args.first().is_some_and(|arg| !arg.starts_with('-')) {
        bail!("unknown command '{}' (use --help for usage)", args[0])
    }
    parse_serve(&mut args.into_iter())
}

fn parse_serve(args: &mut impl Iterator<Item = String>) -> Result<Option<ServeConfig>> {
    let mut tunnel_listen = "0.0.0.0:25566".parse().unwrap();
    let mut game_bind = "0.0.0.0".parse().unwrap();
    let mut game_ports = (26000, 26999);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--help" | "-h" => return Ok(None),
            "--tunnel-listen" => tunnel_listen = args.next().context("--tunnel-listen requires an address")?.parse().context("invalid tunnel listen address")?,
            "--game-bind" => game_bind = args.next().context("--game-bind requires an IP address")?.parse().context("invalid game bind address")?,
            "--game-ports" => game_ports = parse_port_range(&args.next().context("--game-ports requires a range")?)?,
            other => bail!("unknown serve option '{other}' (use --help for usage)"),
        }
    }
    Ok(Some(ServeConfig { tunnel_listen, game_bind, game_ports }))
}

fn parse_port_range(value: &str) -> Result<(u16, u16)> {
    let (first, last) = value.split_once('-').context("game port range must be FIRST-LAST")?;
    let first: u16 = first.parse().context("invalid first game port")?;
    let last: u16 = last.parse().context("invalid last game port")?;
    if first == 0 || last == 0 || first > last { bail!("game port range must be nonzero and ascending"); }
    Ok((first, last))
}

pub fn print_usage() {
    println!("dm-gateway\n\nUsage:\n  dm-gateway [--tunnel-listen ADDR] [--game-bind IP] [--game-ports FIRST-LAST]\n  dm-gateway serve [--tunnel-listen ADDR] [--game-bind IP] [--game-ports FIRST-LAST]\n\nDefaults: --tunnel-listen 0.0.0.0:25566 --game-bind 0.0.0.0 --game-ports 26000-26999\n\nHost integration is provided by the Minecraft client.");
}
