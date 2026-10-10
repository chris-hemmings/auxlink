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
# is accepted but stays silent, and so is one the car itself suspended (a
# chime, a gap) and then found still running. BlueZ keeps reporting "active"
# and this Pi keeps sending audio. Check and fix works because it pauses and
# starts that stream again while music is in it.
#
# So the stream runs only while the car is ready AND the source says Playing
# AND there is no call, and every start is two starts: a muted one the car
# may ignore, then a real one with music, which is the one it plays.
#   * car (re)connects  -> wait until it is ready (HFP set up, or 12 s),
#                          then the two-step start
#   * source plays      -> the two-step start (auxlink-media has already told
#                          the car "Playing" when it writes PLAY_FILE)
#   * source pauses / call -> stop, and suspend the car output (car sees pause)
# The car's A2DP sink is never idle-suspended (81-a2dp-keep.conf): a gap must
# not, by itself, be the suspend the car resumes into silence.
# If the car suspends the transport anyway, or play is pressed while a stream
# is already up, the stream is paused and started again with music flowing
# (the same thing Check and fix does) and a fresh loopback is attached. The
# sink stays acquired across that swap, so the car keeps the start it just
# accepted instead of being handed another one.
. /usr/local/lib/auxlink/common.sh
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/auxlink.conf; done

PLAY_FILE=/run/auxlink/play          # "1"/"0" from auxlink-media (missing = play)
# Not every app reports its play state to the SMO app (YouTube often does
# not), so sound actually arriving on the music input also counts as playing.
# Published for auxlink-media, which then tells the car "Playing".
PRESENT_FILE=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-audio-present
# Touched once the car's stream runs again after a call: auxlink-media holds
# the music source's "play" until then, so no music is missed.
READY_FILE=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-stream-ready
PRESENT=0; LAST_SOUND=0; NEXT_LEVEL=0
CAR_SLC_FILE=/run/auxlink/car-slc    # "1" once hfp-relay has the car's HFP set up
KICK_FILE=/run/auxlink/audio-kick    # touched by auxlink-media when the car presses play,
                                      # and when playback is told "Playing" again
KICK_SEEN=$(stat -c %Y "$KICK_FILE" 2>/dev/null || echo 0)
# Check and fix (and the app's Fix sound, via the kick file) ask for the same
# pause/start. This one is writable by the audio user, who runs audio-check.
HEAL_REQ=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-please-heal
HEAL_SEEN=$(stat -c %Y "$HEAL_REQ" 2>/dev/null || echo 0)
# auxlink-media ignores transport drops until this unix time: they are our own
# pauses. A drop after it is the car letting go, and the stream is restarted.
HOLD_FILE=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-a2dp-hold
DROP_FILE=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/auxlink-a2dp-drop
DROP_SEEN=$(stat -c %Y "$DROP_FILE" 2>/dev/null || echo 0)
CALL_ENDED_AT=0; WAS_CALL=0
# s after a call before the car's stream restarts (the car leaves call
# mode; the two-step start that follows is what it plays). Separate from the
# page's "Resume music after a call", which is when the SOURCE is played
# again (wired: auxlink-media holds it until this stream runs anyway).
call_settle() { echo 2; }
LOOP=""; LOOP_SINK_ID=""; LOOP_INPUT=""; LOOP_STARTED=0; UNLINKED=0; GONE=2; NO_CARD=0
AFTER_HEAL=0; HEAL_TIMES=""
CONNECTED_AT=0; WAITING_SAID=""; NEXT_STEREO=0; STEREO_BAD=0; STOPPED_FOR=""
XQ_FAILS=0   # SBC-XQ attempts since this script started (never reset by a disconnect)
INPUT=""; INPUT_SAID=""
# A Bluetooth music source must become an INPUT (not a stream WirePlumber
# plays straight to the default speaker, which may be the car - that would
# bypass this loopback and play twice). Only that one device: other phones'
# media (e.g. the Oppo's) still mixes into the car's audio as before.
WP_RULE=${XDG_CONFIG_HOME:-$HOME/.config}/wireplumber/wireplumber.conf.d/83-auxlink-music-source.conf
wp_rule() {
  # Keep the car-sink rule in this file too: it is the same key as
  # 81-a2dp-keep.conf, and a later snippet replaces the array rather than
  # adding to it.
  local keep='  {
    matches = [ { node.name = "~bluez_output.*" } ]
    actions = { update-props = { session.suspend-timeout-seconds = 0 } }
  }'
  local want=""
  if [ "${MUSIC_SOURCE:-wired}" = bluetooth ] && [ -n "$SOURCE" ]; then
    want="# Written by auxlink-audio: the music source is an input, not a playback stream.
# The car sink is never idle-suspended (same rule as 81-a2dp-keep.conf).
monitor.bluez.rules = [
  {
    matches = [ { node.name = \"~bluez_input.${SOURCE//:/_}.*\" } ]
    actions = { update-props = { bluez5.media-source-role = \"input\", node.autoconnect = false } }
  },
$keep
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

# A phone call, or the car's mic borrowed for voice search (the car is in
# call mode for that too).
car_busy() { in_call || [ "$(cat /run/auxlink/mic-active 2>/dev/null)" = 1 ]; }
stop_loop() { [ -n "$LOOP" ] && kill "$LOOP" 2>/dev/null; LOOP=""; LOOP_SINK_ID=""; AFTER_HEAL=0; }
linked() {
  local links; links=$(timeout 5 pw-link -l 2>/dev/null)
  echo "$links" | grep -A2 "^$INPUT:capture_FL" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^$INPUT:capture_FR" | grep -q "smo_capture:" &&
  echo "$links" | grep -A2 "^to_tesla:output_FL" | grep -q "bluez_output" &&
  echo "$links" | grep -A2 "^to_tesla:output_FR" | grep -q "bluez_output"
}
# Our own pauses. auxlink-media will not treat the transport leaving "active"
# as the car letting go until this time.
hold_a2dp() { echo $(( $(date +%s) + $1 )) > "$HOLD_FILE"; }
holding() {
  local until; until=$(cat "$HOLD_FILE" 2>/dev/null || echo 0)
  [ "$until" -gt "$(date +%s)" ] 2>/dev/null
}
# What Check and fix does, and what actually makes a silent Tesla play:
# pause and start the stream WHILE MUSIC IS IN IT, then drop the loopback.
# Resuming into silence is the state the car stays in. Idle-suspend is off,
# so the transport stays up with no client and the next loopback feeds the
# start the car just accepted (a brand-new start here is what it ignores).
# The suspend also silences a running loopback's right channel, which is why
# the loopback is replaced afterwards.
RESTART_MUTED=""
heal_stream() {
  # Resuming into silence is the state the car stays in. Caller retries.
  [ "$PRESENT" = 1 ] || return 1
  local now recent=0 t kept=""
  now=$(date +%s)
  for t in $HEAL_TIMES; do
    [ $((now - t)) -le 20 ] && recent=$((recent + 1)) && kept="$kept $t"
  done
  HEAL_TIMES=$kept
  if [ "$recent" -ge 4 ]; then
    echo "Car stream restarted 4 times in 20 s; waiting before trying again"
    hold_a2dp 15
    return 1
  fi
  HEAL_TIMES="$HEAL_TIMES $now"
  unmute_input
  hold_a2dp 8
  echo "Restarting the car's stream ($2) with music in it"
  timeout 5 pactl suspend-sink "$1" 1
  sleep 0.4
  timeout 5 pactl suspend-sink "$1" 0
  sleep 0.5
  stop_loop
  AFTER_HEAL=1
}
# The sacrificial half of a two-step start: pause/resume with the music input
# muted, so a car that plays the first start plays silence, and the fresh
# loopback started next is the one it hears. (The input, not the car output:
# muting that would change the car's volume.)
quick_restart() {
  hold_a2dp 8
  timeout 5 pactl set-source-mute "$INPUT" 1 && RESTART_MUTED=$INPUT
  timeout 5 pactl suspend-sink "$1" 1; sleep 0.2; timeout 5 pactl suspend-sink "$1" 0
  sleep 0.2; stop_loop
}
# Start the loopback (input -> car). The caller has lifted any suspend.
start_loop() {
  [ "$1" = muted ] || unmute_input
  pw-loopback -c 2 -m '[ FL FR ]' \
              --capture-props="target.object=$INPUT node.name=smo_capture audio.position=[ FL FR ]" \
              --playback-props="target.object=$SINK node.name=to_tesla audio.position=[ FL FR ]" &
  LOOP=$!; LOOP_SINK_ID=$SINK_ID; LOOP_INPUT=$INPUT; LOOP_STARTED=$(date +%s); UNLINKED=0; STEREO_BAD=0
  NEXT_STEREO=$((LOOP_STARTED + 3))
  echo "Streaming to $SINK"
}
unmute_input() {
  [ -n "$RESTART_MUTED" ] && timeout 5 pactl set-source-mute "$RESTART_MUTED" 0
  RESTART_MUTED=""
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
trap 'stop_loop; unmute_input' EXIT

# 81-a2dp-keep.conf is only read when WirePlumber starts. Reload it once
# after this file is installed, so a gap in the music does not suspend the
# car (that suspend is the one it resumes into silence).
KEEP_CONF=${XDG_CONFIG_HOME:-$HOME/.config}/wireplumber/wireplumber.conf.d/81-a2dp-keep.conf
KEEP_STAMP=${XDG_STATE_HOME:-$HOME/.local/state}/auxlink/a2dp-nosuspend
if [ -f "$KEEP_CONF" ] && [ ! -f "$KEEP_STAMP" ]; then
  mkdir -p "$(dirname "$KEEP_STAMP")"
  echo "Reloading WirePlumber so a gap in the music does not suspend the car"
  if systemctl --user restart wireplumber; then
    echo 1 > "$KEEP_STAMP"
  fi
  sleep 2
fi

while true; do
  . /etc/auxlink.conf; CARD=bluez_card.${CAR//:/_}; CAR_RE=${CAR//:/[:_]}
  wp_rule
  # Track the call here, first: a Bluetooth source's stream is closed during
  # a call, and the end must be timed from the call, not from its return.
  if car_busy; then WAS_CALL=1
  elif [ "$WAS_CALL" = 1 ]; then WAS_CALL=0; CALL_ENDED_AT=$(date +%s); fi
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
    # No input = no sound: say so (a Bluetooth source closes its stream on
    # pause - left at "1", the car would keep showing Playing).
    if [ "$PRESENT" = 1 ]; then
      PRESENT=0; LAST_SOUND=0; echo 0 > "$PRESENT_FILE"; echo "No sound from the SMO"
    fi
    stop_loop
    # A Bluetooth source closes its stream on pause: look again quickly, so
    # play is heard at once.
    if [ "${MUSIC_SOURCE:-wired}" = bluetooth ]; then sleep 0.5; else sleep 3; fi
    continue
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
  check_sound
  WHY=""
  if car_busy; then WHY="a call"
  elif [ $(( $(date +%s) - CALL_ENDED_AT )) -lt "$(call_settle)" ]; then WHY="the call just ended"
  # Paused on its own, even while a tail of sound is still arriving. Leaving
  # the stream up while the car is told "Paused" is the mute that never
  # lifts: the car keeps the transport and does not play the next start.
  elif ! smo_playing; then WHY="the SMO is paused"
  elif ! car_ready; then WHY="the car is still connecting"
  fi

  if [ -n "$WHY" ]; then
    unmute_input
    if [ -n "$LOOP" ]; then
      echo "Stopping the music stream: $WHY"
      stop_loop
      # A phone suspends its stream on pause; the car sees it stop.
      # hold_a2dp: that suspend is ours, not the car letting go.
      case "$WHY" in "the car is still connecting"|"the call just ended") ;;
        *) hold_a2dp 8; timeout 5 pactl suspend-sink "$SINK" 1 ;; esac
    fi
    if [ "$WHY" != "$WAITING_SAID" ]; then
      case "$WHY" in "the car is still connecting"|"the call just ended") ;;
        *) echo "Not streaming: $WHY" ;; esac
      WAITING_SAID=$WHY
    fi
    sleep 0.5; continue
  fi
  WAITING_SAID=""

  now=$(date +%s)
  # Play in the car, Fix sound, or Check and fix. While a stream is already
  # up, that means the car has it and is not playing it: pause and start
  # again with music. While it is down, the start below is that fresh start,
  # so the request is only remembered as seen.
  kick=$(stat -c %Y "$KICK_FILE" 2>/dev/null || echo 0)
  req=$(stat -c %Y "$HEAL_REQ" 2>/dev/null || echo 0)
  if [ "$kick" != "$KICK_SEEN" ] || [ "$req" != "$HEAL_SEEN" ]; then
    if [ -n "$LOOP" ] && kill -0 "$LOOP" 2>/dev/null && [ "$PRESENT" = 1 ] &&
       [ $((now - LOOP_STARTED)) -ge 2 ]; then
      KICK_SEEN=$kick; HEAL_SEEN=$req
      heal_stream "$SINK" "play was pressed, or check and fix" && continue
    elif [ -z "$LOOP" ] || ! kill -0 "$LOOP" 2>/dev/null; then
      KICK_SEEN=$kick; HEAL_SEEN=$req
    fi
  fi
  # The car suspended the transport (a chime, a gap, its own pause). If it
  # has already resumed, it resumed a stream it will not play. auxlink-media
  # records the leave even when it lasted a fraction of a second.
  drop=$(stat -c %Y "$DROP_FILE" 2>/dev/null || echo 0)
  hold_until=$(cat "$HOLD_FILE" 2>/dev/null || echo 0)
  if [ "$drop" != "$DROP_SEEN" ]; then
    if [ -n "$LOOP" ] && [ "$drop" -gt "$hold_until" ] && ! holding; then
      if heal_stream "$SINK" "the car suspended the stream"; then
        DROP_SEEN=$drop
        continue
      fi
    else
      DROP_SEEN=$drop
    fi
  fi

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
    if [ "$AFTER_HEAL" = 1 ]; then
      AFTER_HEAL=0
      # The transport was just started with music in it. Attach a loopback
      # only while that start is still up. Suspending here would be a new
      # start, which is the one the car accepts and then does not play.
      if [ "$(car_transport_state)" = active ]; then
        hold_a2dp 4
        start_loop
        date +%s > "$READY_FILE"
        sleep 1; continue
      fi
      echo "The restarted stream did not stay open; starting it again"
    fi
    # Lift a pause-time suspend BEFORE the loopback exists: resuming under a
    # running loopback is what silences its right channel.
    hold_a2dp 8
    timeout 5 pactl suspend-sink "$SINK" 0
    # Two starts, every time. The Tesla sometimes ignores the first and
    # sometimes plays it; that one carries silence. The second carries the
    # music, and is the one that gets heard either way.
    timeout 5 pactl set-source-mute "$INPUT" 1 && RESTART_MUTED=$INPUT
    start_loop muted
    for _ in $(seq 20); do linked && break; sleep 0.1; done
    echo "Second start, so a car that ignored the first one plays this stream"
    quick_restart "$SINK"
    # The resume above ran under the muted loopback. Idle-suspend is off, so
    # killing that loopback does not drop the transport, and the next one
    # would keep feeding a start that carried silence. Suspend explicitly,
    # lift it with no client (that starts nothing), then link the music.
    hold_a2dp 8
    timeout 5 pactl suspend-sink "$SINK" 1
    sleep 0.2
    timeout 5 pactl suspend-sink "$SINK" 0
    start_loop
    for _ in $(seq 20); do linked && break; sleep 0.1; done
    date +%s > "$READY_FILE"     # auxlink-media now plays the source
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

  # Both channels really reaching the car? (two bad checks in a row = act)
  if [ "$now" -ge "$NEXT_STEREO" ]; then
    NEXT_STEREO=$((now + 60))
    if stereo_ok "$SINK"; then
      STEREO_BAD=0
    else
      STEREO_BAD=$((STEREO_BAD + 1))
      if [ "$STEREO_BAD" -ge 2 ]; then
        heal_stream "$SINK" "the right channel was silent" && continue
        NEXT_STEREO=$((now + 15))
      else
        NEXT_STEREO=$((now + 3))
      fi
    fi
  fi

  # Backup for a drop the signal missed: the transport is still down.
  ts=$(car_transport_state)
  if [ -n "$ts" ] && [ "$ts" != active ] && ! holding; then
    heal_stream "$SINK" "the stream is '$ts'" && continue
  fi
  sleep 2
done
