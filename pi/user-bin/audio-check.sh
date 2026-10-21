#!/bin/bash
# Check every stage of  SMO -> XIAO -> Pi (I2S) -> loopback -> Bluetooth -> car
# and say which one is broken.   Usage:  audio-check.sh [--fix]
. /usr/local/lib/auxlink/common.sh
[ -z "$CAR" ] && { echo "No car paired yet: open the setup page and pair the car."; exit 1; }
FIX=no; [ "$1" = "--fix" ] && FIX=yes
# Hub pipeline active? Then the chain is  SMO/phone -> hub -> car loop, not the
# classic single loopback: check and repair THAT topology, and never touch the
# classic "smo_capture" loop (it does not exist in hub mode).
HUB=auxlink_hub
HUB_ON=no
[ "${AUDIO_HUB:-1}" = 1 ] && timeout 5 pactl list sinks short 2>/dev/null | grep -qw "$HUB" && HUB_ON=yes
[ $HUB_ON = yes ] && echo "Note: the audio hub is active (AUDIO_HUB=1); checking the hub pipeline."
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; PROBLEM=yes; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; }
PROBLEM=no
# While the phone's own media owns the car audio, the SMO path below is idle on
# purpose - so don't flag it as broken (the phone plays straight to the car).
PHONE_SRC=no
[ "$(cat /run/auxlink/phone-media 2>/dev/null)" = 1 ] && PHONE_SRC=yes
[ $PHONE_SRC = yes ] && echo "Note: the phone's media is the source right now, so the SMO path is idle on purpose (not a fault)."

echo "1. Music source -> Pi input (source: ${MUSIC_SOURCE:-wired})"
I2S=$(music_input)
if [ -z "$I2S" ]; then
  case "${MUSIC_SOURCE:-wired}" in
    bluetooth) bad "Bluetooth music source not connected or not streaming${SOURCE:+ ($SOURCE)}" ;;
    usbc) bad "USB-C input not found (reboot after choosing USB-C? source plugged into the Pi's USB-C?)" ;;
    *) bad "XIAO I2S input not found (overlay xiao-i2s-in loaded? arecord -l)" ;;
  esac
elif [ $PHONE_SRC = yes ]; then
  ok "SMO input present; idle because the phone's media is the source"
else
  rm -f /tmp/audio-check.wav
  timeout 2 pw-record --target "$I2S" /tmp/audio-check.wav 2>/dev/null
  rms=$(sox /tmp/audio-check.wav -n stat 2>&1 | awk '/RMS +amplitude/ {print $3}')
  if [ -z "$rms" ]; then bad "could not read the music input ($I2S)"
  elif awk "BEGIN{exit !($rms > 0.002)}"; then ok "audio arriving (RMS $rms) from $I2S"
  else bad "input is silent (RMS $rms) on $I2S: source paused or volume 0? (wired/USB-C: is its output set to the Pi/XIAO? bluetooth: is a USB sound card still plugged into it? Android prefers USB)"; fi
fi

echo "2. Bluetooth link to the car"
if $BT "$CAR_ADAPTER" "$CAR" connected 2>/dev/null; then ok "car connected"
else bad "car not connected"; echo; exit 1; fi
in_call && warn "a call is in progress: the car pauses music on purpose"

echo "3. Music profile"
prof=$(timeout 5 pactl list cards | sed -n "/Name: $CARD/,/Active Profile/p" | awk -F': ' '/Active Profile/ {print $2}')
if [[ "$prof" == a2dp* ]]; then ok "profile $prof"
else bad "profile is '${prof:-none}', not a2dp-sink / a2dp-sink-sbc_xq"
     [ $FIX = yes ] && { echo "        fixing: opening the music channel"
       timeout 25 $BT "$CAR_ADAPTER" "$CAR" connect "$A2DP_SINK_UUID" >/dev/null 2>&1
       timeout 5 pactl set-card-profile "$CARD" a2dp-sink 2>/dev/null; sleep 3; }; fi

echo "4. Car output"
line=$(car_sink_line); SINK=$(echo "$line" | cut -f2); state=$(echo "$line" | awk '{print $NF}')
if [ -z "$SINK" ]; then bad "no car output exists"; echo; exit 1
elif [ "$state" = RUNNING ]; then ok "$SINK is RUNNING"
else warn "$SINK is $state"; fi
if [ "$(timeout 5 pactl get-sink-mute "$SINK" | awk '{print $2}')" = yes ]; then bad "car output is MUTED"
     [ $FIX = yes ] && timeout 5 pactl set-sink-mute "$SINK" 0
else ok "volume $(timeout 5 pactl get-sink-volume "$SINK" | grep -o '[0-9]*%' | head -1) (follows the car)"; fi

echo "5. Links inside the Pi"
if [ $HUB_ON = yes ]; then
  # Hub topology:  input -> smo_to_hub -> hub ; hub.monitor -> to_tesla_cap -> car
  links=$(timeout 5 pw-link -l 2>/dev/null); feed_ok=no; mon_ok=no; out_ok=no
  echo "$links" | grep -A2 "^$I2S:capture_FL" | grep -q "smo_to_hub:" && feed_ok=yes
  echo "$links" | grep -A2 "^${HUB}:monitor_FL" | grep -q "to_tesla_cap:" && mon_ok=yes
  echo "$links" | grep -A2 "^to_tesla:output_FL" | grep -q "bluez_output" && out_ok=yes
  [ $mon_ok = yes ] && ok "hub -> car loop" || bad "hub monitor not linked to the car loop"
  [ $out_ok = yes ] && ok "car loop -> car" || bad "car loop not linked to the car"
  if [ $PHONE_SRC = yes ]; then ok "SMO feed idle (the phone is the source)"
  elif [ $feed_ok = yes ]; then ok "music input -> hub"
  else warn "music input not feeding the hub (idle while paused is normal)"; fi
  if [ $FIX = yes ] && { [ $mon_ok = no ] || [ $out_ok = no ]; }; then
    # Drop the hub car loop; auxlink-audio rebuilds it cleanly. Never touch the
    # classic smo_capture loop (it does not exist in hub mode).
    echo "        fixing: rebuilding the hub car loop"
    pkill -f "[n]ode.name=to_tesla_cap"; sleep 3; fi
elif [ $PHONE_SRC = yes ]; then
  ok "SMO loopback idle (the phone is the source; it plays straight to the car)"
else
  links=$(timeout 5 pw-link -l 2>/dev/null); in_ok=no; out_ok=no
  echo "$links" | grep -A2 "^$I2S:capture_FL" | grep -q "smo_capture:" &&
    echo "$links" | grep -A2 "^$I2S:capture_FR" | grep -q "smo_capture:" && in_ok=yes
  echo "$links" | grep -A2 "^to_tesla:output_FL" | grep -q "bluez_output" &&
    echo "$links" | grep -A2 "^to_tesla:output_FR" | grep -q "bluez_output" && out_ok=yes
  [ $in_ok = yes ] && ok "music input -> loopback" || bad "music input not linked to the loopback"
  [ $out_ok = yes ] && ok "loopback -> car" || bad "loopback not linked to the car"
  if [ $FIX = yes ] && { [ $in_ok = no ] || [ $out_ok = no ]; }; then
    echo "        fixing: restarting auxlink-audio"; systemctl --user restart auxlink-audio; sleep 6; fi
fi

echo "6. Bluetooth music stream"
ts=$(car_transport_state)
if [ -z "$ts" ]; then bad "no music stream to the car"
elif [ "$ts" = active ]; then ok "stream active"
else warn "stream is '$ts' (car not taking audio)"; fi

if [ $FIX = yes ]; then
  echo "7. Nudging the stream (pause/resume)"
  # Nudge the car sink WITH music flowing (a car that resumes to silence stays
  # silent), then drop the car-facing loop so auxlink-audio starts a fresh one.
  timeout 5 pactl suspend-sink "$SINK" 1; sleep 0.5; timeout 5 pactl suspend-sink "$SINK" 0
  if [ $HUB_ON = yes ]; then
    pkill -f "[n]ode.name=to_tesla_cap"    # hub car loop; auxlink-audio rebuilds it
  else
    pkill -f "[n]ode.name=smo_capture"     # classic loop
  fi
  sleep 3
  ok "done; listen now"
fi
echo
[ $PROBLEM = no ] && echo "Pi side healthy. Still silent? Car source = Bluetooth/auxlink, car volume, then: audio-check.sh --fix"
