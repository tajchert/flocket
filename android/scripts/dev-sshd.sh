#!/usr/bin/env bash
# Runs a throwaway, unprivileged OpenSSH server for end-to-end tests of Flocket against a real
# herdr-core, without touching ~/.ssh or the system sshd.
#
#   scripts/dev-sshd.sh start [port]        # default 2222, listens on 127.0.0.1 only
#   scripts/dev-sshd.sh authorize "<ssh-ed25519 AAAA… comment>"
#   scripts/dev-sshd.sh stop
#   scripts/dev-sshd.sh status
#
# The Android emulator reaches it at 10.0.2.2:<port>. Logins are as the current user, with
# public-key auth only, using keys listed in the harness' own authorized_keys file. In the app,
# configure the host with an isolated hc session name (e.g. `flocket-e2e`) so the developer's
# real terminals are never touched.
set -euo pipefail

STATE_DIR="${FLOCKET_SSHD_DIR:-${TMPDIR:-/tmp}/flocket-dev-sshd}"
PORT="${2:-2222}"
SSHD_BIN="${SSHD_BIN:-$(command -v sshd || echo /usr/sbin/sshd)}"

cmd="${1:-}"
case "$cmd" in
  start)
    mkdir -p "$STATE_DIR"
    chmod 700 "$STATE_DIR"
    [[ -f "$STATE_DIR/host_ed25519" ]] || ssh-keygen -q -t ed25519 -N '' -f "$STATE_DIR/host_ed25519" -C flocket-dev-sshd
    touch "$STATE_DIR/authorized_keys"
    chmod 600 "$STATE_DIR/authorized_keys"
    cat > "$STATE_DIR/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $STATE_DIR/host_ed25519
PidFile $STATE_DIR/sshd.pid
AuthorizedKeysFile $STATE_DIR/authorized_keys
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
AllowUsers $(id -un)
# hc refuses long socket paths (macOS TMPDIR); give sessions a short one.
SetEnv TMPDIR=/tmp
LogLevel INFO
EOF
    if [[ -f "$STATE_DIR/sshd.pid" ]] && kill -0 "$(cat "$STATE_DIR/sshd.pid")" 2>/dev/null; then
      echo "already running (pid $(cat "$STATE_DIR/sshd.pid"))"
    else
      "$SSHD_BIN" -f "$STATE_DIR/sshd_config" -E "$STATE_DIR/sshd.log"
      sleep 0.5
      echo "started on 127.0.0.1:$PORT (emulator: 10.0.2.2:$PORT), pid $(cat "$STATE_DIR/sshd.pid")"
    fi
    echo "host key: $(ssh-keygen -lf "$STATE_DIR/host_ed25519.pub")"
    ;;
  authorize)
    key="${2:?usage: authorize \"<public key line>\"}"
    [[ "$key" == ssh-* || "$key" == ecdsa-* ]] || { echo "not an OpenSSH public key" >&2; exit 2; }
    grep -qxF "$key" "$STATE_DIR/authorized_keys" 2>/dev/null || echo "$key" >> "$STATE_DIR/authorized_keys"
    echo "authorized: $(echo "$key" | ssh-keygen -lf -)"
    ;;
  stop)
    if [[ -f "$STATE_DIR/sshd.pid" ]]; then
      kill "$(cat "$STATE_DIR/sshd.pid")" 2>/dev/null || true
      rm -f "$STATE_DIR/sshd.pid"
      echo "stopped"
    else
      echo "not running"
    fi
    ;;
  status)
    if [[ -f "$STATE_DIR/sshd.pid" ]] && kill -0 "$(cat "$STATE_DIR/sshd.pid")" 2>/dev/null; then
      echo "running (pid $(cat "$STATE_DIR/sshd.pid")), state in $STATE_DIR"
    else
      echo "not running"
    fi
    ;;
  *)
    sed -n '2,13p' "$0"
    exit 2
    ;;
esac
