#!/bin/bash
# Switch power to the Pi's USB ports:  bt-usb-cycle.sh off | on | cycle
# Realtek RTL8761BU dongles keep their firmware across a warm reboot and then
# refuse to start (-110); only cutting power clears that. USB port power is
# ganged on Pi 3 and Pi 4, so this switches every port on every switchable hub
# (on a Pi 4 that means both the USB 2 and USB 3 hubs, as uhubctl requires).
hubs=$(uhubctl 2>/dev/null | awk '/Current status for hub/ {print $5}')
[ -z "$hubs" ] && { echo "uhubctl found no switchable hubs" >&2; exit 1; }
set_all() { for h in $hubs; do uhubctl -l "$h" -a "$1" >/dev/null 2>&1; done; }
case "$1" in
  off)   set_all off ;;
  on)    set_all on ;;
  cycle) set_all off; sleep 3; set_all on ;;
  *)     echo "usage: $0 off|on|cycle" >&2; exit 2 ;;
esac
