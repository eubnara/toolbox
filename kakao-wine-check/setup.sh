#!/usr/bin/env bash
# kakao-wine-check — setup.
#
# Installs (all under the invoking user's home, no sudo needed):
#   ~/bin/kakao-wine-check                              the check script
#   ~/.config/systemd/user/kakao-wine-check.service     systemd user unit
#   ~/.config/systemd/user/kakao-wine-check.timer       runs monthly
#
# Idempotent — safe to re-run after editing the source.
#
# Run as: bash setup.sh

set -euo pipefail

REPO_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ $EUID -eq 0 ]]; then
    echo "[kakao-wine-check setup] do not run as root — this is a per-user tool" >&2
    exit 1
fi

log() { printf '[kakao-wine-check setup] %s\n' "$*"; }

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "[kakao-wine-check setup] missing required command: $1" >&2
        exit 1
    }
}
need curl
need jq
need notify-send
need systemctl

mkdir -p "$HOME/bin"
install -m 0755 "$REPO_DIR/kakao-wine-check" "$HOME/bin/kakao-wine-check"
log "installed script -> $HOME/bin/kakao-wine-check"

UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
install -m 0644 "$REPO_DIR/systemd/kakao-wine-check.service" "$UNIT_DIR/kakao-wine-check.service"
install -m 0644 "$REPO_DIR/systemd/kakao-wine-check.timer"   "$UNIT_DIR/kakao-wine-check.timer"
log "installed systemd units -> $UNIT_DIR"

systemctl --user daemon-reload
systemctl --user enable --now kakao-wine-check.timer >/dev/null
log "enabled & started kakao-wine-check.timer"

cat <<DONE

[kakao-wine-check setup] done.

  manual run:     kakao-wine-check
  timer status:   systemctl --user list-timers kakao-wine-check.timer
  view logs:      journalctl --user -u kakao-wine-check.service
DONE
