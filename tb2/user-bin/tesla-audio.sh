#!/bin/bash
# Keeps SMO music flowing to the car:
#   XIAO I2S input --pw-loopback--> car's Bluetooth (A2DP) output
# It opens the car's music channel when missing, (re)starts the loopback,
# relinks it if the car's output is recreated or links drop, and nudges a car
# stream that stops taking audio. It never touches anything during a call.
. /usr/local/lib/teslabridge/common.sh
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/teslabridge.conf; done

LOOP=""; LOOP_SINK_ID=""; LOOP_STARTED=0; UNLINKED=0; GONE=0; NO_CARD=0; NOT_ACTIVE=0; LAST_NUDGE=0
# Set whenever the car (re)connects: as soon as music is linked, unmute and nudge
# the stream, like the setup page's "fix" does. After a reconnect the car
# can report the stream "active" yet play nothing until it is nudged.
NEED_KICK=1
XQ_FAILS=0   # SBC-XQ attempts the car refused, this connection
I2S=""

stop_loop() { [ -n "$LOOP" ] && kill "$LOOP" 2>/dev/null; LOOP=""; LOOP_SINK_ID=""; }
linked() {
  local links; links=$(timeout 5 pw-link -l 2>/dev/null)
  echo "$links" | grep -A2 "^$I2S:capture_FL" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^to_tesla:output_FL" | grep -q "bluez_output"
}
# Pause/resume the stream (AVDTP suspend/start): wakes a car that is
# "playing" but silent. Short, so it is barely audible.
nudge() { timeout 5 pactl suspend-sink "$1" 1; sleep 0.5; timeout 5 pactl suspend-sink "$1" 0; }
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
  # Safety net against PipeWire graph staleness (seen when the loopback's
  # source carries silence for a long stretch, e.g. SMO unplugged from the
  # XIAO): recycle the loopback node periodically regardless of anything
  # else looking fine, rather than trusting "the process is still running".
  now_ts=$(date +%s)
  if [ -n "$LOOP" ] && [ $((now_ts - ${LOOP_STARTED:-0})) -ge 600 ]; then
    echo "Recycling the loopback (periodic refresh)"
    stop_loop
  fi

  if ! $BT "$CAR_ADAPTER" "$CAR" connected 2>/dev/null; then
    GONE=$((GONE + 1))
    if [ "$GONE" -ge 2 ]; then
      [ -n "$LOOP" ] && echo "Car disconnected"
      stop_loop; NEED_KICK=1; XQ_FAILS=0
    fi
    sleep 2; continue
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

  # Music profile: SBC-XQ when the car offers it (CAR_SBC_XQ=1). Plain SBC
  # runs joint stereo and drops its bitpool under radio pressure, which
  # throws away the left/right difference first - the sound narrows and
  # loses width. SBC-XQ keeps a high-rate dual-channel stream. PipeWire
  # ranks plain SBC higher, so it has to be chosen here on every connect.
  CARDINFO=$(timeout 5 pactl list cards | sed -n "/Name: $CARD/,/^Card #/p")
  ACTIVE=$(echo "$CARDINFO" | awk -F': ' '/Active Profile/ {print $2; exit}')
  WANT=a2dp-sink
  if [ "${CAR_SBC_XQ:-1}" = 1 ] && [ "$XQ_FAILS" -lt 2 ] &&
     echo "$CARDINFO" | grep -q "a2dp-sink-sbc_xq:.*available: yes"; then
    WANT=a2dp-sink-sbc_xq
  fi
  [ "$ACTIVE" = a2dp-sink-sbc_xq ] && XQ_FAILS=0
  if [ "$ACTIVE" != "$WANT" ] && { [[ "$ACTIVE" != a2dp* ]] || ! in_call; }; then
    echo "Switching the car to $WANT"
    # Count every SBC-XQ attempt (cleared once it is actually active): a car
    # that refuses it, or quietly stays on SBC, gets plain SBC after two
    # tries instead of a switch - and a music dropout - every 2 s.
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
    echo "Car output was recreated; relinking"
    stop_loop; NEED_KICK=1; continue
  fi

  if [ -z "$LOOP" ] || ! kill -0 "$LOOP" 2>/dev/null; then
    pw-loopback --capture-props="target.object=$I2S node.name=smo_capture" \
                --playback-props="target.object=$SINK node.name=to_tesla" &
    LOOP=$!; LOOP_SINK_ID=$SINK_ID; LOOP_STARTED=$(date +%s); UNLINKED=0; NOT_ACTIVE=0
    echo "Streaming to $SINK"
    # Check again quickly while a reconnect nudge is still due.
    [ "$NEED_KICK" = 1 ] && sleep 0.5 || sleep 2; continue
  fi

  if ! linked; then
    UNLINKED=$((UNLINKED + 1))
    if [ "$UNLINKED" -ge 2 ]; then
      echo "Link to the car dropped; restarting it"
      UNLINKED=0; stop_loop; continue
    fi
  else
    UNLINKED=0
    # Nudge as soon as the car's stream is up (or after 3 s at most).
    if [ "$NEED_KICK" = 1 ] && ! in_call && { [ "$(car_transport_state)" = active ] ||
         [ $(( $(date +%s) - LOOP_STARTED )) -ge 3 ]; }; then
      if [ "$(timeout 5 pactl get-sink-mute "$SINK" | awk '{print $2}')" = yes ]; then
        echo "Car output was muted; unmuting"
        timeout 5 pactl set-sink-mute "$SINK" 0
      fi
      echo "Car (re)connected; nudging the stream so it starts playing"
      nudge "$SINK"; LAST_NUDGE=$(date +%s); NEED_KICK=0
    fi
  fi

  # During a call the car pauses music on purpose; leave it alone.
  if in_call; then
    NOT_ACTIVE=0
  else
    ts=$(car_transport_state)
    if [ -n "$ts" ] && [ "$ts" != active ]; then
      NOT_ACTIVE=$((NOT_ACTIVE + 1))
    else
      NOT_ACTIVE=0
    fi
    now=$(date +%s)
    if [ "$NOT_ACTIVE" -ge 2 ] && [ $((now - LAST_NUDGE)) -ge 20 ]; then
      echo "Car stream is '$ts' while we are sending audio; nudging it"
      nudge "$SINK"; LAST_NUDGE=$now; NOT_ACTIVE=0
    fi
  fi
  [ "$NEED_KICK" = 1 ] && sleep 0.5 || sleep 2
done
