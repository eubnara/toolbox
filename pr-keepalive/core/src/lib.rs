//! pr-keepalive core: keeps long-running PRs from being closed by stale-bot.
//!
//! Two-stage flow:
//!   1. `run()` — checks each PR. When a PR crosses the inactivity threshold
//!      it is *flagged pending* in state.json; no comment is posted.
//!   2. `confirm_one()` — called interactively after the user OKs the bump.
//!      Re-checks the PR (in case it moved on), posts the comment, clears
//!      the pending flag.
//!
//! All side-effecting GitHub work goes through the `GhClient` trait so it can
//! be stubbed in tests; the production impl shells out to the `gh` CLI.

use anyhow::{anyhow, bail, Context, Result};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Command;

pub const DEFAULT_THRESHOLD_DAYS: i64 = 80;
pub const DEFAULT_COMMENT: &str = "Friendly bump — still active, please keep open.";

#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    pub prs: Vec<String>,
    #[serde(default = "default_threshold_days")]
    pub threshold_days: i64,
    #[serde(default = "default_comment")]
    pub comment: String,
}

fn default_threshold_days() -> i64 {
    DEFAULT_THRESHOLD_DAYS
}
fn default_comment() -> String {
    DEFAULT_COMMENT.to_string()
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("reading config {}", path.display()))?;
        let cfg: Config = toml::from_str(&raw)
            .with_context(|| format!("parsing config {}", path.display()))?;
        if cfg.prs.is_empty() {
            bail!("config has empty `prs` list");
        }
        Ok(cfg)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrSlug {
    pub owner: String,
    pub repo: String,
    pub number: u64,
}

impl PrSlug {
    pub fn parse(s: &str) -> Result<Self> {
        let (repo_part, num_part) = s
            .split_once('#')
            .ok_or_else(|| anyhow!("expected `owner/repo#N`, got `{s}`"))?;
        let (owner, repo) = repo_part
            .split_once('/')
            .ok_or_else(|| anyhow!("expected `owner/repo` before `#`, got `{repo_part}`"))?;
        let number: u64 = num_part
            .parse()
            .map_err(|_| anyhow!("PR number not an integer: `{num_part}`"))?;
        Ok(Self {
            owner: owner.to_string(),
            repo: repo.to_string(),
            number,
        })
    }

    pub fn repo_arg(&self) -> String {
        format!("{}/{}", self.owner, self.repo)
    }

    pub fn slug(&self) -> String {
        format!("{}/{}#{}", self.owner, self.repo, self.number)
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct GhPrView {
    pub state: String,
    pub title: String,
    pub url: String,
    #[serde(rename = "updatedAt")]
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct State {
    pub generated_at: Option<DateTime<Utc>>,
    pub prs: Vec<PrState>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrState {
    pub slug: String,
    pub state: String,
    pub title: String,
    pub url: String,
    pub last_updated_at: DateTime<Utc>,
    pub next_bump_at: DateTime<Utc>,
    pub last_bump_at: Option<DateTime<Utc>>,
    pub last_check_at: DateTime<Utc>,
    pub last_check_result: String,
    /// True when the PR has crossed the threshold and is awaiting a human OK
    /// before a comment is posted.
    #[serde(default)]
    pub pending_confirmation: bool,
    /// First time we flagged this round of pending. Cleared on confirm/dismiss.
    #[serde(default)]
    pub pending_since: Option<DateTime<Utc>>,
    /// When the user was last notified about this pending bump.
    #[serde(default)]
    pub pending_notified_at: Option<DateTime<Utc>>,
}

impl State {
    pub fn load_or_default(path: &Path) -> Result<Self> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("reading state {}", path.display()))?;
        let s: State = serde_json::from_str(&raw)
            .with_context(|| format!("parsing state {}", path.display()))?;
        Ok(s)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)
                .with_context(|| format!("creating state dir {}", dir.display()))?;
        }
        let raw = serde_json::to_string_pretty(self)?;
        std::fs::write(path, raw)
            .with_context(|| format!("writing state {}", path.display()))?;
        Ok(())
    }

    fn index(&self) -> HashMap<String, PrState> {
        self.prs
            .iter()
            .map(|p| (p.slug.clone(), p.clone()))
            .collect()
    }

    pub fn pending(&self) -> Vec<&PrState> {
        self.prs.iter().filter(|p| p.pending_confirmation).collect()
    }

    pub fn pending_mut(&mut self) -> Vec<&mut PrState> {
        self.prs
            .iter_mut()
            .filter(|p| p.pending_confirmation)
            .collect()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Not yet at threshold, or PR is no longer open.
    Skipped(String),
    /// PR newly crossed the threshold this run; pending flag set.
    BecamePending,
    /// PR was already pending from a previous run; still waiting for confirm.
    AlreadyPending,
    /// `--confirm` posted the comment.
    Confirmed,
    /// Pending was cleared because the PR moved on (someone else commented,
    /// PR was closed, etc.) — no comment was posted.
    Cleared(String),
    /// Dry-run hit the threshold; would have flagged pending.
    DryRunWouldFlag,
    /// Failed to talk to GitHub.
    Error(String),
}

impl Outcome {
    pub fn label(&self) -> &'static str {
        match self {
            Outcome::Skipped(_) => "skipped",
            Outcome::BecamePending => "pending-new",
            Outcome::AlreadyPending => "pending",
            Outcome::Confirmed => "confirmed",
            Outcome::Cleared(_) => "cleared",
            Outcome::DryRunWouldFlag => "would-flag",
            Outcome::Error(_) => "error",
        }
    }

    pub fn detail(&self) -> String {
        match self {
            Outcome::Skipped(r) | Outcome::Cleared(r) | Outcome::Error(r) => r.clone(),
            Outcome::BecamePending => "needs confirm — run `pr-keepalive --confirm`".to_string(),
            Outcome::AlreadyPending => "still waiting for confirm".to_string(),
            Outcome::Confirmed => "comment posted".to_string(),
            Outcome::DryRunWouldFlag => "dry-run: would flag pending".to_string(),
        }
    }
}

pub trait GhClient {
    fn pr_view(&self, slug: &PrSlug) -> Result<GhPrView>;
    fn pr_comment(&self, slug: &PrSlug, body: &str) -> Result<()>;
}

pub struct GhCli;

impl GhClient for GhCli {
    fn pr_view(&self, slug: &PrSlug) -> Result<GhPrView> {
        let out = Command::new("gh")
            .args([
                "pr",
                "view",
                &slug.number.to_string(),
                "--repo",
                &slug.repo_arg(),
                "--json",
                "state,title,url,updatedAt",
            ])
            .output()
            .context("running `gh pr view` (is the gh CLI installed and on PATH?)")?;
        if !out.status.success() {
            bail!(
                "`gh pr view {}` failed: {}",
                slug.slug(),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        let view: GhPrView = serde_json::from_slice(&out.stdout)
            .with_context(|| format!("parsing `gh pr view` output for {}", slug.slug()))?;
        Ok(view)
    }

    fn pr_comment(&self, slug: &PrSlug, body: &str) -> Result<()> {
        let out = Command::new("gh")
            .args([
                "pr",
                "comment",
                &slug.number.to_string(),
                "--repo",
                &slug.repo_arg(),
                "--body",
                body,
            ])
            .output()
            .context("running `gh pr comment`")?;
        if !out.status.success() {
            bail!(
                "`gh pr comment {}` failed: {}",
                slug.slug(),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(())
    }
}

pub struct CheckOptions<'a> {
    pub config: &'a Config,
    pub dry_run: bool,
    pub now: DateTime<Utc>,
}

pub struct CheckReport {
    pub slug: PrSlug,
    pub outcome: Outcome,
    pub pr_state: Option<PrState>,
}

fn check_one<G: GhClient>(
    gh: &G,
    slug: &PrSlug,
    prev: Option<&PrState>,
    opts: &CheckOptions,
) -> CheckReport {
    let view = match gh.pr_view(slug) {
        Ok(v) => v,
        Err(e) => {
            return CheckReport {
                slug: slug.clone(),
                outcome: Outcome::Error(format!("{e:#}")),
                pr_state: prev.cloned(),
            };
        }
    };

    let threshold = Duration::days(opts.config.threshold_days);
    let next_bump_at = view.updated_at + threshold;
    let was_pending = prev.map(|p| p.pending_confirmation).unwrap_or(false);
    let is_open = view.state == "OPEN";
    let needs_bump = is_open && opts.now >= next_bump_at;

    let outcome = if !is_open && was_pending {
        Outcome::Cleared(format!("state={} (was pending)", view.state))
    } else if !is_open {
        Outcome::Skipped(format!("state={} (not OPEN)", view.state))
    } else if was_pending && !needs_bump {
        Outcome::Cleared("PR has new activity since flagging".into())
    } else if !needs_bump {
        let days_left = (next_bump_at - opts.now).num_days();
        Outcome::Skipped(format!("{days_left}d until threshold"))
    } else if was_pending {
        Outcome::AlreadyPending
    } else if opts.dry_run {
        Outcome::DryRunWouldFlag
    } else {
        Outcome::BecamePending
    };

    let pending_confirmation = matches!(
        outcome,
        Outcome::BecamePending | Outcome::AlreadyPending
    );

    let pending_since = match (&outcome, prev.and_then(|p| p.pending_since)) {
        (Outcome::BecamePending, _) => Some(opts.now),
        (Outcome::AlreadyPending, Some(t)) => Some(t),
        (Outcome::AlreadyPending, None) => Some(opts.now),
        _ => None,
    };

    let pending_notified_at = if pending_confirmation {
        prev.and_then(|p| p.pending_notified_at)
    } else {
        None
    };

    let pr_state = PrState {
        slug: slug.slug(),
        state: view.state,
        title: view.title,
        url: view.url,
        last_updated_at: view.updated_at,
        next_bump_at,
        last_bump_at: prev.and_then(|p| p.last_bump_at),
        last_check_at: opts.now,
        last_check_result: format!("{}: {}", outcome.label(), outcome.detail()),
        pending_confirmation,
        pending_since,
        pending_notified_at,
    };

    CheckReport {
        slug: slug.clone(),
        outcome,
        pr_state: Some(pr_state),
    }
}

pub fn run<G: GhClient>(
    gh: &G,
    config: &Config,
    state: &mut State,
    dry_run: bool,
) -> Vec<CheckReport> {
    let prev = state.index();
    let now = Utc::now();
    let opts = CheckOptions {
        config,
        dry_run,
        now,
    };

    let mut reports = Vec::with_capacity(config.prs.len());
    let mut new_prs = Vec::with_capacity(config.prs.len());

    for raw in &config.prs {
        let slug = match PrSlug::parse(raw) {
            Ok(s) => s,
            Err(e) => {
                reports.push(CheckReport {
                    slug: PrSlug {
                        owner: String::new(),
                        repo: String::new(),
                        number: 0,
                    },
                    outcome: Outcome::Error(format!("invalid slug `{raw}`: {e:#}")),
                    pr_state: None,
                });
                continue;
            }
        };

        let prev_for_pr = prev.get(&slug.slug());
        let report = check_one(gh, &slug, prev_for_pr, &opts);
        if let Some(ref s) = report.pr_state {
            new_prs.push(s.clone());
        } else if let Some(p) = prev_for_pr {
            new_prs.push(p.clone());
        }
        reports.push(report);
    }

    state.generated_at = Some(now);
    state.prs = new_prs;
    reports
}

/// Re-checks a pending PR and posts the keep-alive comment if still warranted.
/// Updates the matching `PrState` in place.
pub fn confirm_one<G: GhClient>(
    gh: &G,
    config: &Config,
    pr: &mut PrState,
) -> CheckReport {
    let slug = match PrSlug::parse(&pr.slug) {
        Ok(s) => s,
        Err(e) => {
            return CheckReport {
                slug: PrSlug {
                    owner: String::new(),
                    repo: String::new(),
                    number: 0,
                },
                outcome: Outcome::Error(format!("invalid slug: {e:#}")),
                pr_state: Some(pr.clone()),
            };
        }
    };

    let view = match gh.pr_view(&slug) {
        Ok(v) => v,
        Err(e) => {
            let outcome = Outcome::Error(format!("{e:#}"));
            pr.last_check_at = Utc::now();
            pr.last_check_result = format!("{}: {}", outcome.label(), outcome.detail());
            return CheckReport {
                slug,
                outcome,
                pr_state: Some(pr.clone()),
            };
        }
    };

    let now = Utc::now();
    let threshold = Duration::days(config.threshold_days);
    let next_bump_at = view.updated_at + threshold;
    let still_open = view.state == "OPEN";
    let still_warranted = still_open && now >= next_bump_at;

    let outcome = if !still_open {
        Outcome::Cleared(format!("state={} since flagging", view.state))
    } else if !still_warranted {
        Outcome::Cleared("PR has new activity since flagging".into())
    } else {
        match gh.pr_comment(&slug, &config.comment) {
            Ok(()) => Outcome::Confirmed,
            Err(e) => Outcome::Error(format!("comment failed: {e:#}")),
        }
    };

    pr.state = view.state;
    pr.title = view.title;
    pr.url = view.url;
    pr.last_check_at = now;
    pr.last_check_result = format!("{}: {}", outcome.label(), outcome.detail());

    match &outcome {
        Outcome::Confirmed => {
            pr.last_updated_at = now;
            pr.next_bump_at = now + threshold;
            pr.last_bump_at = Some(now);
            pr.pending_confirmation = false;
            pr.pending_since = None;
            pr.pending_notified_at = None;
        }
        Outcome::Cleared(_) => {
            pr.last_updated_at = view.updated_at;
            pr.next_bump_at = next_bump_at;
            pr.pending_confirmation = false;
            pr.pending_since = None;
            pr.pending_notified_at = None;
        }
        _ => { /* error: keep pending so the user can retry */ }
    }

    CheckReport {
        slug,
        outcome,
        pr_state: Some(pr.clone()),
    }
}

pub fn default_config_path() -> PathBuf {
    xdg_dir("XDG_CONFIG_HOME", ".config").join("pr-keepalive/config.toml")
}

pub fn default_state_path() -> PathBuf {
    xdg_dir("XDG_STATE_HOME", ".local/state").join("pr-keepalive/state.json")
}

fn xdg_dir(env: &str, fallback: &str) -> PathBuf {
    if let Some(v) = std::env::var_os(env) {
        let p = PathBuf::from(v);
        if !p.as_os_str().is_empty() {
            return p;
        }
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    home.join(fallback)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct StubGh {
        view: RefCell<GhPrView>,
        comments: RefCell<Vec<String>>,
    }
    impl GhClient for StubGh {
        fn pr_view(&self, _: &PrSlug) -> Result<GhPrView> {
            Ok(self.view.borrow().clone())
        }
        fn pr_comment(&self, _: &PrSlug, body: &str) -> Result<()> {
            self.comments.borrow_mut().push(body.to_string());
            Ok(())
        }
    }

    fn cfg() -> Config {
        Config {
            prs: vec!["apache/hadoop#8458".into()],
            threshold_days: 80,
            comment: "bump".into(),
        }
    }

    fn stub(state: &str, days_ago: i64) -> StubGh {
        StubGh {
            view: RefCell::new(GhPrView {
                state: state.into(),
                title: "t".into(),
                url: "u".into(),
                updated_at: Utc::now() - Duration::days(days_ago),
            }),
            comments: RefCell::new(vec![]),
        }
    }

    #[test]
    fn parses_slug() {
        let s = PrSlug::parse("apache/hadoop#8458").unwrap();
        assert_eq!(s.owner, "apache");
        assert_eq!(s.repo, "hadoop");
        assert_eq!(s.number, 8458);
    }

    #[test]
    fn skips_when_not_yet_threshold() {
        let gh = stub("OPEN", 10);
        let mut st = State::default();
        let reports = run(&gh, &cfg(), &mut st, false);
        assert!(matches!(reports[0].outcome, Outcome::Skipped(_)));
        assert!(gh.comments.borrow().is_empty());
        assert!(!st.prs[0].pending_confirmation);
    }

    #[test]
    fn flags_pending_at_threshold_no_post() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let reports = run(&gh, &cfg(), &mut st, false);
        assert_eq!(reports[0].outcome, Outcome::BecamePending);
        assert!(gh.comments.borrow().is_empty(), "must not post");
        assert!(st.prs[0].pending_confirmation);
        assert!(st.prs[0].pending_since.is_some());
    }

    #[test]
    fn second_run_says_already_pending_no_post() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let _ = run(&gh, &cfg(), &mut st, false);
        let reports = run(&gh, &cfg(), &mut st, false);
        assert_eq!(reports[0].outcome, Outcome::AlreadyPending);
        assert!(gh.comments.borrow().is_empty());
    }

    #[test]
    fn confirm_posts_and_clears_pending() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let _ = run(&gh, &cfg(), &mut st, false);
        let report = confirm_one(&gh, &cfg(), &mut st.prs[0]);
        assert_eq!(report.outcome, Outcome::Confirmed);
        assert_eq!(gh.comments.borrow().len(), 1);
        assert!(!st.prs[0].pending_confirmation);
        assert!(st.prs[0].last_bump_at.is_some());
    }

    #[test]
    fn confirm_clears_when_pr_closed_in_meantime() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let _ = run(&gh, &cfg(), &mut st, false);
        // Simulate PR being closed before user confirmed
        gh.view.borrow_mut().state = "CLOSED".into();
        let report = confirm_one(&gh, &cfg(), &mut st.prs[0]);
        assert!(matches!(report.outcome, Outcome::Cleared(_)));
        assert!(gh.comments.borrow().is_empty());
        assert!(!st.prs[0].pending_confirmation);
    }

    #[test]
    fn next_run_clears_pending_if_pr_was_externally_updated() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let _ = run(&gh, &cfg(), &mut st, false);
        // Simulate someone else commenting (updated_at jumps to "now")
        gh.view.borrow_mut().updated_at = Utc::now();
        let reports = run(&gh, &cfg(), &mut st, false);
        assert!(matches!(reports[0].outcome, Outcome::Cleared(_)));
        assert!(!st.prs[0].pending_confirmation);
    }

    #[test]
    fn dry_run_never_flags_or_posts() {
        let gh = stub("OPEN", 90);
        let mut st = State::default();
        let reports = run(&gh, &cfg(), &mut st, true);
        assert_eq!(reports[0].outcome, Outcome::DryRunWouldFlag);
        assert!(gh.comments.borrow().is_empty());
        assert!(!st.prs[0].pending_confirmation);
    }

    #[test]
    fn skips_closed_pr() {
        let gh = stub("CLOSED", 200);
        let mut st = State::default();
        let reports = run(&gh, &cfg(), &mut st, false);
        assert!(matches!(reports[0].outcome, Outcome::Skipped(_)));
        assert!(gh.comments.borrow().is_empty());
    }
}
