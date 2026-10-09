#!/bin/bash
# Test the car microphone path (for SMO voice search) without the SMO:
# asks hfp-relay for the car's mic for N seconds (default 8), records it and
# reports the level. Talk during the first half, stay quiet for the rest.
#   sudo car-mic-test.sh [seconds]
SECS=${1:-8}
REQ=/run/teslabridge/mic
RAW=/run/teslabridge/car-mic.raw
WAV=/tmp/car-mic.wav
[ "$(id -u)" = 0 ] || { echo "Run with sudo"; exit 1; }
rm -f "$RAW"
echo "Asking the car for its microphone for $SECS s: talk now..."
echo 1 > "$REQ"
sleep "$SECS"
echo 0 > "$REQ"
sleep 1
echo
journalctl -u hfp-relay --since "-$((SECS + 5)) s" --no-pager -o cat | grep -E "Car mic|BVRA"
if [ ! -s "$RAW" ]; then
  echo; echo "No audio received from the car."; exit 1
fi
sox -t raw -r 8000 -e signed -b 16 -c 1 "$RAW" "$WAV"
echo; echo "Recorded $(soxi -D "$WAV") s -> $WAV"
half=$(awk "BEGIN{print $SECS/2}")
for part in "0 $half talking" "$half $half quiet"; do
  set -- $part
  rms=$(sox "$WAV" -n trim "$1" "$2" stat 2>&1 | awk '/RMS +amplitude/ {print $3}')
  echo "  $3 half: RMS ${rms:-?}"
done
echo "Talking should be clearly louder than quiet. Copy $WAV off the Pi to listen."
