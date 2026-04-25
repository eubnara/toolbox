#!/usr/bin/env bash
# charger-watts — first-time setup.
#
# Installs:
#   /etc/modules-load.d/ec_sys.conf            ec_sys auto-load on boot
#   /usr/local/sbin/charger-watts-read         root-only EC reader
#   /usr/local/bin/charger-watts               user-facing wrapper
#   /etc/sudoers.d/charger-watts               NOPASSWD entry for the reader
#   ~/.local/share/plasma/plasmoids/...        Plasma 6 panel widget (symlinked
#                                              into this repo for live edits)
#
# Detects the HWAT field offset in DSDT automatically (works on most
# Lenovo ThinkPads). Falls back to /sys/class/typec/ for non-ThinkPad
# laptops that expose USB-PD via UCSI.
#
# Run as: bash setup.sh   (re-execs with sudo internally)

set -euo pipefail

REPO_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ $EUID -ne 0 ]]; then
    exec sudo --preserve-env=USER REPO_DIR="$REPO_DIR" "$0" "$@"
fi

TARGET_USER="${SUDO_USER:-${USER:-root}}"
TARGET_HOME=$(getent passwd "$TARGET_USER" | cut -d: -f6)

log() { printf '[charger-watts setup] %s\n' "$*"; }

#---------------------------------------------------------------------
# 1. Packages
#---------------------------------------------------------------------
log "installing acpica-tools"
DEBIAN_FRONTEND=noninteractive apt-get install -y -q acpica-tools >/dev/null

#---------------------------------------------------------------------
# 2. Detect HWAT byte offset from DSDT
#---------------------------------------------------------------------
log "detecting HWAT offset from DSDT"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT
( cd "$TMPDIR" && acpidump -n DSDT -b >/dev/null )
DSDT_BIN=$(ls "$TMPDIR"/dsdt*.dat 2>/dev/null | head -1 || true)
if [[ -z "${DSDT_BIN}" ]]; then
    log "warning: could not dump DSDT; using fallback offset 0xC9"
    HWAT_OFFSET=201
else
    iasl -d "$DSDT_BIN" >/dev/null 2>&1 || true
    DSL=${DSDT_BIN%.dat}.dsl
    HWAT_OFFSET=$(awk '
        /OperationRegion.*EmbeddedControl/ { in_ec = 1; bits = 0; next }
        in_ec && /^[[:space:]]*Field/ { in_field = 1; bits = 0; next }
        in_field {
            if (match($0, /Offset[[:space:]]*\(([^)]+)\)/, m)) {
                v = m[1]
                if (v ~ /^0x/) bits = strtonum(v) * 8
                else           bits = (v + 0) * 8
                next
            }
            if (match($0, /^[[:space:]]*([A-Z_][A-Za-z0-9_]*)[[:space:]]*,[[:space:]]*([0-9]+)/, m)) {
                if (m[1] == "HWAT") { print int(bits/8); exit }
                bits += m[2] + 0
            }
            if ($0 ~ /^[[:space:]]*\}/) { in_field = 0; in_ec = 0 }
        }
    ' "$DSL")
    if [[ -z "$HWAT_OFFSET" ]]; then
        log "HWAT field not found in DSDT — falling back to 0xC9 + UCSI"
        HWAT_OFFSET=201
    else
        log "HWAT found at byte offset $HWAT_OFFSET (0x$(printf '%X' "$HWAT_OFFSET"))"
    fi
fi

#---------------------------------------------------------------------
# 3. Auto-load ec_sys at boot + load now
#---------------------------------------------------------------------
log "configuring ec_sys auto-load"
echo "ec_sys" > /etc/modules-load.d/ec_sys.conf
modprobe -q ec_sys || log "warning: modprobe ec_sys failed"

#---------------------------------------------------------------------
# 4. Install root-only EC reader
#---------------------------------------------------------------------
log "installing /usr/local/sbin/charger-watts-read (offset=$HWAT_OFFSET)"
cat > /usr/local/sbin/charger-watts-read <<EOF
#!/usr/bin/env bash
# Reads connected USB-C PD charger wattage. Outputs a single integer (watts),
# or 0 if no charger / unable to determine.
#
# Strategy:
#   1. ThinkPad EC HWAT byte at offset $HWAT_OFFSET (auto-detected at install)
#   2. Fallback: /sys/class/power_supply/ucsi-source-psy-* for UCSI laptops
set -eu

EC_IO=/sys/kernel/debug/ec/ec0/io
HWAT_OFFSET=$HWAT_OFFSET

if [[ -r /sys/class/power_supply/AC/online ]]; then
    online=\$(cat /sys/class/power_supply/AC/online)
    if [[ "\$online" != "1" ]]; then
        echo 0
        exit 0
    fi
fi

if [[ -r "\$EC_IO" ]]; then
    bytes=\$(dd if="\$EC_IO" bs=256 count=1 status=none 2>/dev/null \
            | od -An -tu1 -N1 -j"\$HWAT_OFFSET" 2>/dev/null \
            | tr -d ' \\n')
    if [[ -n "\$bytes" && "\$bytes" != "0" ]]; then
        echo "\$bytes"
        exit 0
    fi
fi

# UCSI fallback (Framework, newer Dells, etc.)
for psy in /sys/class/power_supply/ucsi-source-psy-* /sys/class/power_supply/USBC*; do
    [[ -d "\$psy" ]] || continue
    if [[ -r "\$psy/online" && "\$(cat "\$psy/online")" == "1" ]]; then
        if [[ -r "\$psy/voltage_max" && -r "\$psy/current_max" ]]; then
            v=\$(cat "\$psy/voltage_max")
            i=\$(cat "\$psy/current_max")
            echo \$(( v * i / 1000000000000 ))
            exit 0
        fi
    fi
done

echo 0
EOF
chmod 0755 /usr/local/sbin/charger-watts-read

#---------------------------------------------------------------------
# 5. User-facing wrapper
#---------------------------------------------------------------------
log "installing /usr/local/bin/charger-watts"
cat > /usr/local/bin/charger-watts <<'EOF'
#!/usr/bin/env bash
# Print connected charger wattage.
#   charger-watts          -> "100W" or "no charger"
#   charger-watts --raw    -> "100" or "0"  (used by Plasma widget)
set -eu

raw=$(sudo -n /usr/local/sbin/charger-watts-read 2>/dev/null) || {
    echo "error: cannot read EC (sudoers NOPASSWD missing? re-run setup.sh)" >&2
    exit 1
}

if [[ "${1:-}" == "--raw" ]]; then
    echo "$raw"
    exit 0
fi

if [[ "$raw" == "0" || -z "$raw" ]]; then
    echo "no charger"
else
    echo "${raw}W"
fi
EOF
chmod 0755 /usr/local/bin/charger-watts

#---------------------------------------------------------------------
# 6. sudoers NOPASSWD
#---------------------------------------------------------------------
log "installing sudoers entry for user '$TARGET_USER'"
SUDOERS_FILE=/etc/sudoers.d/charger-watts
cat > "$SUDOERS_FILE" <<EOF
# Allow $TARGET_USER to read charger wattage without password.
$TARGET_USER ALL=(root) NOPASSWD: /usr/local/sbin/charger-watts-read
EOF
chmod 0440 "$SUDOERS_FILE"
visudo -c -f "$SUDOERS_FILE" >/dev/null

#---------------------------------------------------------------------
# 7. Plasma 6 widget (symlink to repo for easy editing)
#---------------------------------------------------------------------
PLASMOID_SRC="$REPO_DIR/plasmoid"
PLASMOID_DST="$TARGET_HOME/.local/share/plasma/plasmoids/com.eub.chargerwatts"
if [[ -d "$PLASMOID_SRC" ]]; then
    log "installing Plasma widget (symlink $PLASMOID_DST -> $PLASMOID_SRC)"
    sudo -u "$TARGET_USER" mkdir -p "$(dirname "$PLASMOID_DST")"
    if [[ -e "$PLASMOID_DST" || -L "$PLASMOID_DST" ]]; then
        rm -rf "$PLASMOID_DST"
    fi
    sudo -u "$TARGET_USER" ln -s "$PLASMOID_SRC" "$PLASMOID_DST"
else
    log "plasmoid/ not found in repo dir — skipping widget install"
fi

#---------------------------------------------------------------------
# 8. Smoke test
#---------------------------------------------------------------------
log "smoke test"
if out=$(sudo -u "$TARGET_USER" /usr/local/bin/charger-watts 2>&1); then
    log "  -> charger-watts says: $out"
else
    log "  -> FAILED: $out"
    exit 1
fi

log "done."
log "CLI:    charger-watts"
log "widget: restart plasmashell -> add 'Charger Watts' from widget list"
log "        kquitapp6 plasmashell && kstart plasmashell &"
