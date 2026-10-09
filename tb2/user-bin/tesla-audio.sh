#!/bin/bash
# Keeps SMO music flowing to the car:
#   XIAO I2S input --pw-loopback--> car's Bluetooth (A2DP) output
#
# It behaves like a phone: the car only plays a Bluetooth stream it sees START
# (AVDTP start) while it is ready and the source says "Playing". A stream that
# started too early - mid-reconnect, or one that simply ran on through a call -
# is accepted but stays silent. So the stream runs only while the car is
# ready AND the SMO is playing AND there is no call, and every start is a
# fresh one (new loopback) with music in it:
#   * car (re)connects  -> wait until it is ready (HFP set up, or 12 s)
#   * SMO plays         -> start (teslabridge-keys has already told the car
#                          "Playing" when it writes PLAY_FILE)
#   * SMO pauses / call -> stop, and suspend the car output (car sees pause)
# While streaming, both channels are checked every 60 s: a suspend/resume of
# the car's output (the car can do that too) leaves a running pw-loopback
# with a silent RIGHT channel until it is recreated.
. /usr/local/lib/teslabridge/common.sh
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/teslabridge.conf; done

PLAY_FILE=/run/teslabridge/play          # "1"/"0" from teslabridge-keys (missing = play)
CAR_SLC_FILE=/run/teslabridge/car-slc    # "1" once hfp-relay has the car's HFP set up
LOOP=""; LOOP_SINK_ID=""; LOOP_STARTED=0; UNLINKED=0; GONE=2; NO_CARD=0; NOT_ACTIVE=0; LAST_NUDGE=0
CONNECTED_AT=0; WAITING_SAID=""; NEXT_STEREO=0; STEREO_BAD=0; STOPPED_FOR=""
XQ_FAILS=0   # SBC-XQ attempts since this script started (never reset by a disconnect)
I2S=""

stop_loop() { [ -n "$LOOP" ] && kill "$LOOP" 2>/dev/null; LOOP=""; LOOP_SINK_ID=""; }
linked() {
  local links; links=$(timeout 5 pw-link -l 2>/dev/null)
  echo "$links" | grep -A2 "^$I2S:capture_FL" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^$I2S:capture_FR" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^to_tesla:output_FL" | grep -q "bluez_output" &&
  echo "$links" | grep -A2 "^to_tesla:output_FR" | grep -q "bluez_output"
}
# Last resort only (rate-limited): pause/resume the car's stream WITH music
# flowing (a car whose stream resumes to silence stays silent), then recreate
# the loopback (the suspend silences its right channel).
nudge() {
  timeout 5 pactl suspend-sink "$1" 1; sleep 0.5; timeout 5 pactl suspend-sink "$1" 0
  sleep 1; stop_loop
}
smo_playing() { [ "$(cat "$PLAY_FILE" 2>/dev/null || echo 1)" != 0 ]; }
car_ready() {
  local up=$(( $(date +%s) - CONNECTED_AT ))
  [ "$up" -ge 3 ] && { [ "$(cat "$CAR_SLC_FILE" 2>/dev/null)" = 1 ] || [ "$up" -ge 12 ]; }
}
# What the car is actually sent, 1 s of it: false if the right channel is
# silent while the left carries music (can't judge quiet passages: true).
stereo_ok() {
  local f=/tmp/tb-stereo-check.wav l r
  rm -f "$f"
  pw-record --target "$1" -P stream.capture.sink=true --channels 2 --format s16 "$f" &
  local p=$!; sleep 1.2; kill "$p" 2>/dev/null; wait "$p" 2>/dev/null
  l=$(sox "$f" -n remix 1 stat 2>&1 | awk '/RMS +amplitude/ {print $3}')
  r=$(sox "$f" -n remix 2 stat 2>&1 | awk '/RMS +amplitude/ {print $3}')
  [ -z "$l" ] || [ -z "$r" ] && return 0
  awk "BEGIN{exit !($l < 0.005 || $r > $l / 20)}"
}
trap 'stop_loop' EXIT

while true; do
  . /etc/teslabridge.conf; CARD=bluez_card.${CAR//:/_}; CAR_RE=${CAR//:/[:_]}
  # Note: the "xiaoi2s" ALSA device is the Pi<->XIAO hardware I2S link (fixed
  # by the device-tree overlay) and stays present whether or not the SMO
  # itself is plugged into the XIAO's USB-C port - so it is not a reliable
  # signal for "SMO disconnected". Only treat it as a real outage.
  [ -z "$I2S" ] && I2S=$(i2s_source)
  if [ -z "$I2S" ]; then
    echo "XIAO I2S input not found (is the xiao-i2s-in overlay loaded? check: arecord -l)"
    sleep 5; continue
  fi

  if ! $BT "$CAR_ADAPTER" "$CAR" connected 2>/dev/null; then
    GONE=$((GONE + 1))
    if [ "$GONE" -eq 2 ]; then
      [ -n "$LOOP" ] && echo "Car disconnected"
      stop_loop
    fi
    sleep 2; continue
  fi
  if [ "$GONE" -ge 2 ]; then
    CONNECTED_AT=$(date +%s); WAITING_SAID=""
    echo "Car connected; waiting until it is ready before starting music"
  fi
  GONE=0

  # PipeWire only creates the car's card once the music channel is open.
  if ! timeout 5 pactl list cards short 2>/dev/null | grep -q "$CARD"; then
    NO_CARD=$((NO_CARD + 1))
    if [ "$NO_CARD" -ge 3 ]; then
      echo "Car connected without music; opening the A2DP channel"
      timeout 25 $BT "$CAR_ADAPTER" "$CAR" connect "$A2DP_SINK_UUID" >/dev/null 2>&1
      NO_CARD=0
    fi
    sleep 2; continue
  fi
  NO_CARD=0

  # Music profile: plain SBC unless SBC_XQ=1. SBC-XQ (high-rate dual
  # channel) keeps the stereo width that plain SBC loses under radio
  # pressure, but the Tesla DISCONNECTS when the profile is switched to it,
  # so it is opt-in and tried at most twice per Pi boot, not per connection
  # (a per-connection retry would knock the car off again on every connect).
  CARDINFO=$(timeout 5 pactl list cards | sed -n "/Name: $CARD/,/^Card #/p")
  ACTIVE=$(echo "$CARDINFO" | awk -F': ' '/Active Profile/ {print $2; exit}')
  WANT=a2dp-sink
  if [ "${SBC_XQ:-0}" = 1 ] && [ "$XQ_FAILS" -lt 2 ] &&
     echo "$CARDINFO" | grep -q "a2dp-sink-sbc_xq:.*available: yes"; then
    WANT=a2dp-sink-sbc_xq
  fi
  if [ "$ACTIVE" != "$WANT" ] && { [[ "$ACTIVE" != a2dp* ]] || ! in_call; }; then
    echo "Switching the car to $WANT"
    # Count every SBC-XQ attempt (per boot): a car that refuses it, or
    # quietly stays on SBC, gets plain SBC after two tries.
    [ "$WANT" = a2dp-sink-sbc_xq ] && XQ_FAILS=$((XQ_FAILS + 1))
    timeout 5 pactl set-card-profile "$CARD" "$WANT" 2>/dev/null
    sleep 2; continue
  fi

  LINE=$(car_sink_line)
  SINK=$(echo "$LINE" | cut -f2)
  SINK_ID=$(echo "$LINE" | cut -f1)
  if [ -z "$SINK" ]; then
    stop_loop; sleep 2; continue
  fi

  if [ -n "$LOOP_SINK_ID" ] && [ "$SINK_ID" != "$LOOP_SINK_ID" ]; then
    echo "Car output was recreated; restarting the stream"
    stop_loop; continue
  fi

  # ---- should music be streaming right now? ----
  WHY=""
  if in_call; then WHY="a call"
  elif ! smo_playing; then WHY="the SMO is paused"
  elif ! car_ready; then WHY="the car is still connecting"
  fi

  if [ -n "$WHY" ]; then
    if [ -n "$LOOP" ]; then
      echo "Stopping the music stream: $WHY"
      stop_loop
      # A phone suspends its stream on pause; the car sees it stop.
      [ "$WHY" != "the car is still connecting" ] && timeout 5 pactl suspend-sink "$SINK" 1
    fi
    if [ "$WHY" != "$WAITING_SAID" ]; then
      [ "$WHY" = "the car is still connecting" ] || echo "Not streaming: $WHY"
      WAITING_SAID=$WHY
    fi
    sleep 0.5; continue
  fi
  WAITING_SAID=""

  if [ -z "$LOOP" ] || ! kill -0 "$LOOP" 2>/dev/null; then
    # A fresh start every time. Channel layout spelled out on both sides
    # (left unspecified, the right channel was dropped inside the loopback).
    # Only ever one loopback: strays (e.g. one started by hand for a test)
    # feed the car the same music on their own timing - a skip every few s.
    pkill -f "[n]ode.name=smo_capture" && sleep 0.5
    if [ "$(timeout 5 pactl get-sink-mute "$SINK" | awk '{print $2}')" = yes ]; then
      echo "Car output was muted; unmuting"
      timeout 5 pactl set-sink-mute "$SINK" 0
    fi
    # Lift a pause-time suspend BEFORE the loopback exists: resuming under a
    # running loopback is what silences its right channel. With nothing
    # playing yet this starts nothing; the car sees START when the loopback
    # links, with music in it.
    timeout 5 pactl suspend-sink "$SINK" 0
    pw-loopback -c 2 -m '[ FL FR ]' \
                --capture-props="target.object=$I2S node.name=smo_capture audio.position=[ FL FR ]" \
                --playback-props="target.object=$SINK node.name=to_tesla audio.position=[ FL FR ]" &
    LOOP=$!; LOOP_SINK_ID=$SINK_ID; LOOP_STARTED=$(date +%s); UNLINKED=0; NOT_ACTIVE=0; STEREO_BAD=0
    NEXT_STEREO=$((LOOP_STARTED + 3))
    echo "Streaming to $SINK"
    sleep 1; continue
  fi

  if ! linked; then
    UNLINKED=$((UNLINKED + 1))
    if [ "$UNLINKED" -ge 2 ]; then
      echo "Link to the car dropped; restarting it"
      UNLINKED=0; stop_loop; continue
    fi
    sleep 1; continue
  fi
  UNLINKED=0

  now=$(date +%s)
  # Both channels really reaching the car? (two bad checks in a row = act)
  if [ "$now" -ge "$NEXT_STEREO" ]; then
    NEXT_STEREO=$((now + 60))
    if stereo_ok "$SINK"; then
      STEREO_BAD=0
    else
      STEREO_BAD=$((STEREO_BAD + 1))
      if [ "$STEREO_BAD" -ge 2 ]; then
        echo "Right channel silent at the car; recreating the loopback"
        stop_loop; continue
      fi
      NEXT_STEREO=$((now + 3))
    fi
  fi

  # Last resort: the car's stream is not taking audio at all.
  ts=$(car_transport_state)
  if [ -n "$ts" ] && [ "$ts" != active ]; then
    NOT_ACTIVE=$((NOT_ACTIVE + 1))
  else
    NOT_ACTIVE=0
  fi
  if [ "$NOT_ACTIVE" -ge 3 ] && [ $((now - LAST_NUDGE)) -ge 60 ]; then
    echo "Car stream is '$ts' while we are sending audio; nudging it (last resort)"
    nudge "$SINK"; LAST_NUDGE=$now; NOT_ACTIVE=0
    continue
  fi
  sleep 2
done
