#!/bin/bash
# Watch everything that can drop the car link, in one stream, with times.
#   kernel : dongle crashes (hw err), USB resets, SCO corruption
#   btmon  : connect / disconnect events and the reason code for each
#   services: what the reconnect / audio / relay scripts did
# Also saves a full Bluetooth capture to /tmp/car.btsnoop (open in Wireshark).
trap 'kill 0' EXIT
sudo btmon -w /tmp/car.btsnoop >/dev/null 2>&1 &
sudo btmon 2>/dev/null | grep --line-buffered -E "Connect Complete|Disconnect Complete|Reason:|Status: [^S]|Link Supervision|Role Change" \
  | sed -u 's/^/[btmon] /' &
sudo journalctl -k -f -n 0 | grep --line-buffered -i -E "hw err|reset|corrupt|rtl|usb 1-1" | sed -u 's/^/[kernel] /' &
sudo journalctl -f -n 0 -u auxlink-reconnect -u hfp-relay -u auxlink-pairing -u auxlink-media | grep --line-buffered -v -E "CLCC|  -> " | sed -u 's/^/[svc] /' &
journalctl --user -f -n 0 --user-unit auxlink-audio 2>/dev/null | sed -u 's/^/[audio] /' &
wait
