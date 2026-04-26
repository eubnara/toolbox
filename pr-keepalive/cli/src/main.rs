//! pr-keepalive CLI.
//!
//! Default mode (run by the systemd timer): checks each PR, flags any that have
//! crossed the inactivity threshold as pending, and fires one desktop
//! notification if anything is pending. Never posts a comment on its own.
//!
//! `--confirm` mode (run by the user from a terminal): walks pending PRs,
//! shows a preview of the comment, and asks `[y/N/q]` per PR before posting.

use anyhow::{Context, Result};
use chrono::Utc;
use pr_keepalive_core::{
    confirm_one, default_config_path, default_state_path, run, Config, GhCli, Outcome, State,
};
use std::io::{BufRead, IsTerminal, Write};
use std::path::PathBuf;
use std::process::{Command, ExitCode};

#[derive(Default)]
struct Args {
    config: Option<PathBuf>,
    state: Option<PathBuf>,
    dry_run: bool,
    quiet: bool,
    confirm: bool,
    no_notify: bool,
}

fn parse_args() -> Result<Args> {
    let mut a = Args::default();
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--dry-run" | "-n" => a.dry_run = true,
            "--quiet" | "-q" => a.quiet = true,
            "--confirm" | "-c" => a.confirm = true,
            "--no-notify" => a.no_notify = true,
            "--config" => a.config = Some(it.next().map(PathBuf::from).context("--config needs a path")?),
            "--state" => a.state = Some(it.next().map(PathBuf::from).context("--state needs a path")?),
            "--help" | "-h" => {
                print_help();
                std::process::exit(0);
            }
            other => anyhow::bail!("unknown argument: {other}"),
        }
    }
    if a.confirm && a.dry_run {
        anyhow::bail!("--confirm and --dry-run are mutually exclusive");
    }
    Ok(a)
}

fn print_help() {
    println!(
        "pr-keepalive — flag long-running PRs, then comment on confirm

USAGE:
  pr-keepalive                 # check + flag pending + notify (run by timer)
  pr-keepalive --confirm       # interactively review pending bumps
  pr-keepalive --dry-run       # check only, never flag or post

OPTIONS:
  -c, --confirm     Walk pending PRs and post comments on `y`.
  -n, --dry-run     Don't flag pending, don't post.
  -q, --quiet       Suppress per-PR `skipped` lines.
      --no-notify   Skip the desktop notification.
      --config P    Override config path (default: $XDG_CONFIG_HOME/pr-keepalive/config.toml)
      --state P     Override state path  (default: $XDG_STATE_HOME/pr-keepalive/state.json)
  -h, --help        Show this help."
    );
}

fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("pr-keepalive: {e:#}");
            return ExitCode::from(2);
        }
    };

    let config_path = args.config.clone().unwrap_or_else(default_config_path);
    let state_path = args.state.clone().unwrap_or_else(default_state_path);

    let config = match Config::load(&config_path) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("pr-keepalive: {e:#}");
            return ExitCode::from(2);
        }
    };

    let mut state = match State::load_or_default(&state_path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("pr-keepalive: {e:#}");
            return ExitCode::from(2);
        }
    };

    let exit = if args.confirm {
        run_confirm(&config, &mut state, &args)
    } else {
        run_check(&config, &mut state, &args)
    };

    if let Err(e) = state.save(&state_path) {
        eprintln!("pr-keepalive: failed to save state: {e:#}");
        return ExitCode::from(1);
    }

    exit
}

fn run_check(config: &Config, state: &mut State, args: &Args) -> ExitCode {
    let reports = run(&GhCli, config, state, args.dry_run);

    let mut had_error = false;
    let mut became_pending = 0usize;
    for r in &reports {
        let line = format!("[{}] {} — {}", r.outcome.label(), r.slug.slug(), r.outcome.detail());
        match &r.outcome {
            Outcome::Error(_) => {
                had_error = true;
                eprintln!("{line}");
            }
            Outcome::Skipped(_) if args.quiet => {}
            _ => println!("{line}"),
        }
        if matches!(r.outcome, Outcome::BecamePending) {
            became_pending += 1;
        }
    }

    let pending_total = state.pending().len();
    if became_pending > 0 && !args.no_notify {
        notify(pending_total);
        // Mark notified for everything currently pending so we don't spam.
        for p in state.pending_mut() {
            p.pending_notified_at = Some(Utc::now());
        }
    }

    if had_error {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}

fn run_confirm(config: &Config, state: &mut State, _args: &Args) -> ExitCode {
    let stdin = std::io::stdin();
    if !stdin.is_terminal() {
        eprintln!("pr-keepalive: --confirm requires an interactive terminal");
        return ExitCode::from(2);
    }

    let pending_slugs: Vec<String> = state
        .pending()
        .iter()
        .map(|p| p.slug.clone())
        .collect();

    if pending_slugs.is_empty() {
        println!("No pending bumps. Nothing to confirm.");
        return ExitCode::SUCCESS;
    }

    println!(
        "{} PR(s) pending confirmation:\n",
        pending_slugs.len()
    );

    let mut had_error = false;
    let mut posted = 0usize;
    let mut cleared = 0usize;
    let mut deferred = 0usize;
    let mut quit_early = false;

    let mut stdin_lock = stdin.lock();
    let stdout = std::io::stdout();
    let mut stdout_lock = stdout.lock();

    for slug in &pending_slugs {
        if quit_early {
            deferred += 1;
            continue;
        }
        let Some(pr) = state.prs.iter_mut().find(|p| &p.slug == slug) else {
            continue;
        };

        // Print preview
        let now = Utc::now();
        let days_idle = (now - pr.last_updated_at).num_days();
        println!("──────────────────────────────────────────────");
        println!("  {} — {}", pr.slug, pr.title);
        println!("  {}", pr.url);
        println!(
            "  Last activity: {} ({}d ago)",
            pr.last_updated_at.format("%Y-%m-%d %H:%M UTC"),
            days_idle
        );
        if let Some(since) = pr.pending_since {
            let pending_for = (now - since).num_days();
            println!("  Pending for:   {pending_for}d");
        }
        println!("  Comment to post:");
        for line in config.comment.trim().lines() {
            println!("    │ {line}");
        }
        print!("  Post comment? [y/N/q] ");
        let _ = stdout_lock.flush();

        let mut buf = String::new();
        if stdin_lock.read_line(&mut buf).is_err() {
            eprintln!("input error");
            return ExitCode::from(1);
        }
        let answer = buf.trim().to_lowercase();
        match answer.as_str() {
            "y" | "yes" => {
                let report = confirm_one(&GhCli, config, pr);
                match &report.outcome {
                    Outcome::Confirmed => {
                        posted += 1;
                        println!("  -> posted ✓");
                    }
                    Outcome::Cleared(why) => {
                        cleared += 1;
                        println!("  -> not posted (cleared: {why})");
                    }
                    Outcome::Error(why) => {
                        had_error = true;
                        eprintln!("  -> ERROR: {why}");
                    }
                    other => {
                        eprintln!("  -> unexpected outcome: {}", other.label());
                    }
                }
            }
            "q" | "quit" => {
                quit_early = true;
                deferred += 1;
                println!("  -> quitting; remaining stay pending");
            }
            _ => {
                deferred += 1;
                println!("  -> deferred (still pending)");
            }
        }
        println!();
    }

    println!(
        "Done. posted={posted} cleared={cleared} deferred={deferred}{}",
        if had_error { " (with errors)" } else { "" }
    );

    if had_error {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}

fn notify(pending_count: usize) {
    let body = format!(
        "{pending_count} PR(s) ready to bump.\nRun: pr-keepalive --confirm"
    );
    let _ = Command::new("notify-send")
        .args([
            "-a",
            "pr-keepalive",
            "-i",
            "dialog-information",
            "-u",
            "normal",
            "PR keep-alive",
            &body,
        ])
        .status();
}
