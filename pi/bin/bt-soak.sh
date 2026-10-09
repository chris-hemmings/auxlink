#!/bin/bash
# Bluetooth soak test: run music/calls against whatever is connected right
# now, and log every sign of trouble with a timestamp, so separate test runs
# can be compared on hard numbers instead of "it felt flaky".
#
#   bt-soak.sh NAME MINUTES
#
# Writes results/<NAME>/ (log.txt, summary.txt, a full btsnoop capture, and
# any firmware crash dumps) and appends one line to results/SUMMARY.txt.
set -u
NAME=${1:?usage: bt-soak.sh NAME MINUTES}
MIN=${2:?usage: bt-soak.sh NAME MINUTES}
OUT="$HOME/bt-soak-results/$NAME"
mkdir -p "$OUT"
SECS=$((MIN * 60))

echo "Recording '$NAME' for $MIN minutes into $OUT"
echo "Leave it running (music playing / call in progress) and come back."

sudo btmon -w "$OUT/capture.btsnoop" >/dev/null 2>&1 &
BTMON=$!
trap 'kill $BTMON 2>/dev/null' EXIT

{
  echo "=== $(date -Is) start: $NAME ($MIN min) ==="
  vcgencmd measure_temp 2>/dev/null
  vcgencmd get_throttled 2>/dev/null
  echo "--- connected devices ---"
  bluetoothctl devices Connected 2>/dev/null
  echo "--- adapters ---"
  bluetoothctl list 2>/dev/null
  echo "==="
} > "$OUT/log.txt"

# Live counters, written as events happen (so a crash mid-run is not lost).
( journalctl -k -f -n 0 | grep --line-buffered -i -E \
    "hw err|corrupted sco|unknown connection handle|usb disconnect|usb.*reset|under-voltage" \
  | while read -r line; do echo "$(date -Is) KERNEL: $line" >> "$OUT/log.txt"; done ) &
KLOG=$!

( journalctl -f -n 0 -u auxlink-reconnect -u hfp-relay 2>/dev/null \
    | grep --line-buffered -i -E "disconnect|reconnect|power-cycl" \
  | while read -r line; do echo "$(date -Is) SVC: $line" >> "$OUT/log.txt"; done ) &
SLOG=$!

( while sleep 30; do
    t=$(vcgencmd measure_temp 2>/dev/null | grep -o '[0-9.]*')
    th=$(vcgencmd get_throttled 2>/dev/null | cut -d= -f2)
    echo "$(date -Is) STATUS: temp=${t:-?} throttled=${th:-?}" >> "$OUT/log.txt"
  done ) &
TLOG=$!

sleep "$SECS"
kill $KLOG $SLOG $TLOG "$BTMON" 2>/dev/null
wait 2>/dev/null

HWERR=$(grep -c "hw err" "$OUT/log.txt")
CORRUPT=$(grep -c "corrupted sco" "$OUT/log.txt")
USBRESET=$(grep -ci "usb.*reset\|usb disconnect" "$OUT/log.txt")
DISCONNECT=$(grep -c "SVC:.*[Dd]isconnect" "$OUT/log.txt")
THROTTLE=$(grep "STATUS" "$OUT/log.txt" | grep -v "throttled=0x0" | wc -l)
MAXTEMP=$(grep -o "temp=[0-9.]*" "$OUT/log.txt" | cut -d= -f2 | sort -g | tail -1)

# Keep any firmware crash dumps the kernel produced during this run.
for d in /sys/class/devcoredump/devcd*/data; do
  [ -e "$d" ] && cp "$d" "$OUT/devcoredump-$(basename "$(dirname "$d")").bin" 2>/dev/null
done

{
  echo "name=$NAME minutes=$MIN"
  echo "hw_err=$HWERR corrupted_sco=$CORRUPT usb_reset_or_disconnect=$USBRESET"
  echo "service_disconnects=$DISCONNECT throttle_events=$THROTTLE max_temp=${MAXTEMP:-?}"
  echo "hw_err_per_hour=$(awk -v h=$HWERR -v m=$MIN 'BEGIN{printf "%.1f", h*60/m}')"
} > "$OUT/summary.txt"

cat "$OUT/summary.txt"
echo "$(date -Is) $NAME: hw_err=$HWERR corrupted_sco=$CORRUPT disconnects=$DISCONNECT throttle=$THROTTLE max_temp=${MAXTEMP:-?}" \
  >> "$HOME/bt-soak-results/SUMMARY.txt"
echo
echo "Full log: $OUT/log.txt"
echo "All runs so far: cat ~/bt-soak-results/SUMMARY.txt"
