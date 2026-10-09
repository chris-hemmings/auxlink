#!/bin/bash
# Strip the Pi down to the simplest possible rig for testing dongle
# stability: just the car dongle + the XIAO's music feed. Nothing else
# touches Bluetooth or USB - no phone link, no call relay, no pairing
# service, no setup page, no contacts sync.
#
#   auxlink-test-mode.sh on    - stop everything except the car link + music
#   auxlink-test-mode.sh off   - restore the normal full setup
#   auxlink-test-mode.sh status
#
# This only stops/starts services (reversible in seconds). For the
# cleanest possible test, also physically UNPLUG the phone dongle so it
# is not even present on the USB bus - the script cannot do that part.
set -u
. /usr/local/lib/auxlink/common.sh 2>/dev/null || true
USER_=${AUDIO_USER:-chris}
SYS_FULL="hfp-relay auxlink-media auxlink-pairing auxlink-web"
SYS_KEEP="auxlink-reconnect"
USER_FULL="pbap-sync obex"
USER_KEEP="auxlink-audio"

asuser() { runuser -u "$USER_" -- env XDG_RUNTIME_DIR=/run/user/"$(id -u "$USER_")" "$@"; }

case "${1:-}" in
  on)
    echo "Entering minimal test mode: car dongle + XIAO music only."
    sudo systemctl stop $SYS_FULL
    asuser systemctl --user stop $USER_FULL
    sudo systemctl restart $SYS_KEEP
    asuser systemctl --user restart $USER_KEEP
    echo
    echo "Stopped:  $SYS_FULL  (system)   /   $USER_FULL  (user)"
    echo "Running:  $SYS_KEEP  (system)   /   $USER_KEEP  (user)"
    echo
    echo "For the cleanest test, also unplug the phone dongle now."
    echo "Then run, e.g.:  bt-soak.sh minimal-30min 30"
    echo "Restore everything afterwards with:  auxlink-test-mode.sh off"
    ;;
  off)
    echo "Restoring the full setup."
    sudo systemctl start $SYS_FULL $SYS_KEEP
    asuser systemctl --user start $USER_FULL $USER_KEEP
    sleep 2
    "$0" status
    ;;
  status)
    echo "System services:"
    for u in $SYS_KEEP $SYS_FULL; do printf "  %-18s %s\n" "$u" "$(sudo systemctl is-active "$u" 2>/dev/null)"; done
    echo "User services:"
    for u in $USER_KEEP $USER_FULL; do printf "  %-18s %s\n" "$u" "$(asuser systemctl --user is-active "$u" 2>/dev/null)"; done
    ;;
  *)
    echo "usage: $0 on|off|status" >&2
    exit 2
    ;;
esac
