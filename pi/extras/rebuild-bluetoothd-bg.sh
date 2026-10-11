#!/bin/bash
# Run by update.sh (in the background, as its own systemd unit) when the
# Bluetooth patches in extras/ changed: rebuild bluetoothd with them, and
# record which patches it was built with. Progress goes to Recent events on
# the setup page; the full log to /var/log/auxlink-bt-rebuild.log.
#   rebuild-bluetoothd-bg.sh <patches md5>
SUM=$1
HERE=$(cd "$(dirname "$0")" && pwd)
STAMP=/var/lib/auxlink/bluetoothd-patches
ev() { echo "$(date +%H:%M:%S) $1" >> /run/auxlink/events.log; }
ev "Updating the Pi's Bluetooth software in the background (15-20 min, needs internet)"
if bash "$HERE/build-bluetoothd-cover.sh" > /var/log/auxlink-bt-rebuild.log 2>&1; then
  mkdir -p "$(dirname "$STAMP")"
  echo "$SUM" > "$STAMP"
  ev "Bluetooth software updated. Turn the car's Bluetooth off and on so it sees the change."
else
  ev "Bluetooth software update failed (no internet?). It tries again with the next update. Log: /var/log/auxlink-bt-rebuild.log"
fi
