#!/bin/bash
# Keeps music flowing to the car:
#   music input --pw-loopback--> car's Bluetooth (A2DP) output
# The music input is whatever MUSIC_SOURCE selects: the XIAO over I2S
# (wired), a phone/player streaming to the Pi over Bluetooth, or the Pi's
# USB-C port as a USB sound card.
#
# It behaves like a phone: the car only plays a Bluetooth stream it sees START
# (AVDTP start) while it is ready and the source says "Playing". A stream that
# started too early - mid-reconnect, or one that simply ran on through a call -
# is accepted but stays silent. So the stream runs only while the car is
# ready AND the SMO is playing AND there is no call, and every start is a
# fresh one (new loopback) with music in it:
#   * car (re)connects  -> wait until it is ready (HFP set up, or 12 s)
#   * SMO plays         -> start (auxlink-media has already told the car
#                          "Playing" when it writes PLAY_FILE)
#   * SMO pauses / call -> stop, and suspend the car output (car sees pause)
# While streaming, both channels are checked every 60 s: a suspend/resume of
# the car's output (the car can do that too) leaves a running pw-loopback
# with a silent RIGHT channel until it is recreated.
#
# A car can also accept a stream and play SILENCE with everything on the Pi
# looking healthy (seen after a call and after reconnecting), which nothing
# here can detect. So at those moments, a few seconds after music starts, the
# stream is restarted once anyway (what "Check and fix" does), and pressing
# play in the car (auxlink-media writes KICK_FILE) does the same.
. /usr/local/lib/auxlink/common.sh
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/auxlink.conf; done

PLAY_FILE=/run/auxlink/play          # "1"/"0" from auxlink-media (missing = play)
# Not every app reports its play state to the SMO app (YouTube often does
# not), so sound actually arriving on the music input also counts as playing.
# Published for auxlink-media, which then tells the car "Playing".
PRESENT_FILE=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-audio-present
PRESENT=0; LAST_SOUND=0; NEXT_LEVEL=0
CAR_SLC_FILE=/run/auxlink/car-slc    # "1" once hfp-relay has the car's HFP set up
KICK_FILE=/run/auxlink/audio-kick    # touched by auxlink-media when the car presses play
KICK_SEEN=$(stat -c %Y "$KICK_FILE" 2>/dev/null || echo 0)
RECHECK=""        # why the next fresh stream gets one restart (car connected / call ended)
RECHECK_AT=0      # when to do it (0 = not pending)
CALL_ENDED_AT=0; WAS_CALL=0
CALL_SETTLE=2     # s after a call before music restarts (the car leaves call mode)
LOOP=""; LOOP_SINK_ID=""; LOOP_INPUT=""; LOOP_STARTED=0; UNLINKED=0; GONE=2; NO_CARD=0; NOT_ACTIVE=0; LAST_NUDGE=0
CONNECTED_AT=0; WAITING_SAID=""; NEXT_STEREO=0; STEREO_BAD=0; STOPPED_FOR=""
XQ_FAILS=0   # SBC-XQ attempts since this script started (never reset by a disconnect)
INPUT=""; INPUT_SAID=""
# A Bluetooth music source must become an INPUT (not a stream WirePlumber
# plays straight to the default speaker, which may be the car - that would
# bypass this loopback and play twice). Only that one device: other phones'
# media (e.g. the Oppo's) still mixes into the car's audio as before.
WP_RULE=${XDG_CONFIG_HOME:-$HOME/.config}/wireplumber/wireplumber.conf.d/83-auxlink-music-source.conf
wp_rule() {
  local want=""
  if [ "${MUSIC_SOURCE:-wired}" = bluetooth ] && [ -n "$SOURCE" ]; then
    want="# Written by auxlink-audio: the music source is an input, not a playback stream.
monitor.bluez.rules = [
  {
    matches = [ { node.name = \"~bluez_input.${SOURCE//:/_}.*\" } ]
    actions = { update-props = { bluez5.media-source-role = \"input\", node.autoconnect = false } }
  }
]"
  fi
  local have; have=$(cat "$WP_RULE" 2>/dev/null)
  [ "$have" = "$want" ] && return
  if [ -n "$want" ]; then mkdir -p "$(dirname "$WP_RULE")"; printf '%s\n' "$want" > "$WP_RULE"
  else rm -f "$WP_RULE"; fi
  [ -z "$have" ] && [ -z "$want" ] && return
  echo "Music source changed: restarting WirePlumber to apply it"
  stop_loop
  systemctl --user restart wireplumber
  sleep 3
}

stop_loop() { [ -n "$LOOP" ] && kill "$LOOP" 2>/dev/null; LOOP=""; LOOP_SINK_ID=""; }
linked() {
  local links; links=$(timeout 5 pw-link -l 2>/dev/null)
  echo "$links" | grep -A2 "^$INPUT:capture_FL" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^$INPUT:capture_FR" | grep -q "smo_capture:" &&
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
# The quick version for the planned restarts: the same pause/resume with
# music flowing, but the loopback is recreated straight after the resume, so
# the mono moment it causes is a fraction of a second, not audible.
quick_restart() {
  timeout 5 pactl suspend-sink "$1" 1; sleep 0.3; timeout 5 pactl suspend-sink "$1" 0
  sleep 0.3; stop_loop
}
smo_playing() { [ "$(cat "$PLAY_FILE" 2>/dev/null || echo 1)" != 0 ]; }
# Is sound arriving from the XIAO? 0.4 s sample; the SMO sends digital
# silence when nothing plays, so a very low threshold is enough.
sound_now() {
  local f=/tmp/auxlink-level.wav rms
  rm -f "$f"
  pw-record --target "$INPUT" --channels 2 --format s16 "$f" &
  local p=$!; sleep 0.4; kill "$p" 2>/dev/null; wait "$p" 2>/dev/null
  rms=$(sox "$f" -n stat 2>&1 | awk '/RMS +amplitude/ {print $3}')
  [ -n "$rms" ] && awk "BEGIN{exit !($rms > 0.0003)}"
}
# Updates PRESENT (sound seen within the last 6 s) and its file.
check_sound() {
  local now; now=$(date +%s)
  [ "$now" -lt "$NEXT_LEVEL" ] && return
  if sound_now; then LAST_SOUND=$now; fi
  local was=$PRESENT
  if [ $((now - LAST_SOUND)) -lt 6 ]; then PRESENT=1; else PRESENT=0; fi
  if [ "$PRESENT" != "$was" ] || [ ! -f "$PRESENT_FILE" ]; then
    echo "$PRESENT" > "$PRESENT_FILE"
    [ "$PRESENT" = 1 ] && echo "Sound arriving from the SMO" || echo "No sound from the SMO"
  fi
  # Quick to notice music starting; relaxed while it plays.
  if [ -n "$LOOP" ]; then NEXT_LEVEL=$((now + 3)); else NEXT_LEVEL=$now; fi
}
car_ready() {
  local up=$(( $(date +%s) - CONNECTED_AT ))
  [ "$up" -ge 3 ] && { [ "$(cat "$CAR_SLC_FILE" 2>/dev/null)" = 1 ] || [ "$up" -ge 12 ]; }
}
# What the car is actually sent, 1 s of it: false if the right channel is
# silent while the left carries music (can't judge quiet passages: true).
stereo_ok() {
  local f=/tmp/auxlink-stereo-check.wav l r
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
  . /etc/auxlink.conf; CARD=bluez_card.${CAR//:/_}; CAR_RE=${CAR//:/[:_]}
  wp_rule
  # Note: the "xiaoi2s" ALSA device is the Pi<->XIAO hardware I2S link (fixed
  # by the device-tree overlay) and stays present whether or not the SMO
  # itself is plugged into the XIAO's USB-C port - so it is not a reliable
  # signal for "SMO disconnected". A Bluetooth source's input only exists
  # while it is connected, and the USB-C one only once the port is in
  # gadget mode.
  INPUT=$(music_input)
  if [ -z "$INPUT" ]; then
    case "${MUSIC_SOURCE:-wired}" in
      bluetooth) why="Waiting for the Bluetooth music source${SOURCE:+ ($SOURCE)} to connect" ;;
      usbc) why="USB-C music input not found (gadget mode needs a reboot after selecting USB-C)" ;;
      *) why="XIAO I2S input not found (is the xiao-i2s-in overlay loaded? check: arecord -l)" ;;
    esac
    [ "$why" != "$INPUT_SAID" ] && echo "$why"; INPUT_SAID=$why
    stop_loop; sleep 3; continue
  fi
  INPUT_SAID=""
  if [ -n "$LOOP" ] && [ "$INPUT" != "$LOOP_INPUT" ]; then
    echo "Music input is now $INPUT; restarting the stream"
    stop_loop
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
    CONNECTED_AT=$(date +%s); WAITING_SAID=""; RECHECK="the car connected"
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
  check_sound
  # The car pressed play: restart the stream (now if it is running).
  kick=$(stat -c %Y "$KICK_FILE" 2>/dev/null || echo 0)
  if [ "$kick" != "$KICK_SEEN" ]; then
    KICK_SEEN=$kick; RECHECK="play was pressed in the car"
    [ -n "$LOOP" ] && RECHECK_AT=$(date +%s)
  fi
  if in_call; then WAS_CALL=1
  elif [ "$WAS_CALL" = 1 ]; then WAS_CALL=0; CALL_ENDED_AT=$(date +%s); RECHECK="the call ended"
  fi
  WHY=""
  if in_call; then WHY="a call"
  elif [ $(( $(date +%s) - CALL_ENDED_AT )) -lt "$CALL_SETTLE" ]; then WHY="the call just ended"
  elif ! smo_playing && [ "$PRESENT" != 1 ]; then WHY="the SMO is paused"
  elif ! car_ready; then WHY="the car is still connecting"
  fi

  if [ -n "$WHY" ]; then
    if [ -n "$LOOP" ]; then
      echo "Stopping the music stream: $WHY"
      stop_loop
      # A phone suspends its stream on pause; the car sees it stop.
      case "$WHY" in "the car is still connecting"|"the call just ended") ;;
        *) timeout 5 pactl suspend-sink "$SINK" 1 ;; esac
    fi
    if [ "$WHY" != "$WAITING_SAID" ]; then
      case "$WHY" in "the car is still connecting"|"the call just ended") ;;
        *) echo "Not streaming: $WHY" ;; esac
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
                --capture-props="target.object=$INPUT node.name=smo_capture audio.position=[ FL FR ]" \
                --playback-props="target.object=$SINK node.name=to_tesla audio.position=[ FL FR ]" &
    LOOP=$!; LOOP_SINK_ID=$SINK_ID; LOOP_INPUT=$INPUT; LOOP_STARTED=$(date +%s); UNLINKED=0; NOT_ACTIVE=0; STEREO_BAD=0
    NEXT_STEREO=$((LOOP_STARTED + 3))
    echo "Streaming to $SINK"
    # One restart just after music starts flowing (see the top).
    [ -n "$RECHECK" ] && RECHECK_AT=$((LOOP_STARTED + 1))
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
  if [ "$RECHECK_AT" -gt 0 ] && [ "$now" -ge "$RECHECK_AT" ]; then
    if [ "$PRESENT" = 1 ] || smo_playing; then
      echo "Restarting the car's stream once ($RECHECK), so a car that took it silently plays it"
      RECHECK=""; RECHECK_AT=0
      quick_restart "$SINK"; continue
    fi
    RECHECK_AT=$now               # wait for music before restarting
  fi
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
