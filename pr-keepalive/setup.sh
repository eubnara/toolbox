#!/usr/bin/env bash
# pr-keepalive — first-time setup.
#
# Installs (all under the invoking user's home, no sudo needed):
#   ~/bin/pr-keepalive                              release binary
#   ~/.config/pr-keepalive/config.toml              copied from example if missing
#   ~/.config/systemd/user/pr-keepalive.service     systemd user unit
#   ~/.config/systemd/user/pr-keepalive.timer       runs at boot + every 24h
#
# Idempotent — safe to re-run after editing the source.
#
# Run as: bash setup.sh

set -euo pipefail

REPO_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ $EUID -eq 0 ]]; then
    echo "[pr-keepalive setup] do not run as root — this is a per-user tool" >&2
    exit 1
fi

log() { printf '[pr-keepalive setup] %s\n' "$*"; }

#---------------------------------------------------------------------
# 1. Prerequisites
#---------------------------------------------------------------------
need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "[pr-keepalive setup] missing required command: $1" >&2
        exit 1
    }
}
need cargo
need gh
need systemctl

if ! gh auth status >/dev/null 2>&1; then
    echo "[pr-keepalive setup] gh is not authenticated. Run: gh auth login" >&2
    exit 1
fi

if ! command -v notify-send >/dev/null 2>&1; then
    log "warning: notify-send not found — install libnotify-bin for desktop notifications"
    log "         (the tool still works without it; you just won't be reminded)"
fi

#---------------------------------------------------------------------
# 2. Build release binary
#---------------------------------------------------------------------
log "building release binary"
( cd "$REPO_DIR" && cargo build --release --quiet )

BIN_SRC="$REPO_DIR/target/release/pr-keepalive"
BIN_DST="$HOME/bin/pr-keepalive"
mkdir -p "$HOME/bin"
install -m 0755 "$BIN_SRC" "$BIN_DST"
log "installed binary -> $BIN_DST"

#---------------------------------------------------------------------
# 3. Config (copy example only if missing — never overwrite user edits)
#---------------------------------------------------------------------
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/pr-keepalive"
CONFIG_DST="$CONFIG_DIR/config.toml"
mkdir -p "$CONFIG_DIR"
if [[ ! -e "$CONFIG_DST" ]]; then
    cp "$REPO_DIR/config.example.toml" "$CONFIG_DST"
    log "wrote starter config -> $CONFIG_DST  (edit it to add your PRs)"
else
    log "config already exists at $CONFIG_DST  (left untouched)"
fi

#---------------------------------------------------------------------
# 4. systemd user units
#---------------------------------------------------------------------
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
install -m 0644 "$REPO_DIR/systemd/pr-keepalive.service" "$UNIT_DIR/pr-keepalive.service"
install -m 0644 "$REPO_DIR/systemd/pr-keepalive.timer"   "$UNIT_DIR/pr-keepalive.timer"
log "installed systemd units -> $UNIT_DIR"

systemctl --user daemon-reload
systemctl --user enable --now pr-keepalive.timer >/dev/null
log "enabled & started pr-keepalive.timer"

#---------------------------------------------------------------------
# 5. Smoke test (dry-run, never posts a comment)
#---------------------------------------------------------------------
log "smoke test (dry-run)"
if "$BIN_DST" --dry-run; then
    log "  -> ok"
else
    log "  -> FAILED (see output above)"
    exit 1
fi

cat <<DONE

[pr-keepalive setup] done.

  edit config:    \$EDITOR \$XDG_CONFIG_HOME/pr-keepalive/config.toml
  manual run:     pr-keepalive --dry-run
  view state:     cat \$XDG_STATE_HOME/pr-keepalive/state.json
  timer status:   systemctl --user list-timers pr-keepalive.timer
  view logs:      journalctl --user -u pr-keepalive.service
DONE
