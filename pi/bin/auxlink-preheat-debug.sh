#!/bin/bash
# Temporary overnight / preheat debugger: sample USB-C, power, audio and
# Bluetooth into a file on the SD card so a flaky reconnect after the car
# sleeps can be reconstructed later.
#
#   Log file:  /var/lib/auxlink/preheat.log  (SD card; append-only across reboots)
#   Enable:    PREHEAT_DEBUG=1 in /etc/auxlink.conf  (then restart this service)
#   Disable:   PREHEAT_DEBUG=0  (or systemctl disable --now auxlink-preheat-debug)
#
# Samples every INTERVAL seconds. Writes a line when anything interesting
# changes, plus a heartbeat every HEARTBEAT seconds so quiet periods still
# leave a trail. Kernel USB / undervoltage lines are appended as they arrive.
# Never truncates or rotates: a boot only appends a marker so earlier sessions
# stay on the card (this is a temporary debug build; delete the file by hand
# when finished).
set -u

LOG_DIR=/var/lib/auxlink
LOG=$LOG_DIR/preheat.log
OLD_LOG=/var/log/auxlink/preheat.log   # earlier draft path; migrated once if present
INTERVAL=5
HEARTBEAT=60
KERN_RE='dwc2|udc|usb_gadget|UAC1|gadget|under-voltage|Undervoltage|throttl|Voltage normalised|usb .*disconnect|usb .*reset|configfs'

. /etc/auxlink.conf 2>/dev/null || true
AUDIO_USER=${AUDIO_USER:-chris}
AUDIO_UID=$(id -u "$AUDIO_USER" 2>/dev/null || echo 1000)
PRESENT_FILE=/run/user/$AUDIO_UID/auxlink-audio-present

mkdir -p "$LOG_DIR"
chmod 755 "$LOG_DIR" 2>/dev/null || true

# Keep history across reboots and service restarts: always append.
emit() {
  printf '%s\n' "$*" >> "$LOG"
  chmod 644 "$LOG" 2>/dev/null || true
}

# One-time move from the first draft location so nothing is lost.
if [ -f "$OLD_LOG" ] && [ ! -e "$LOG" ]; then
  mv -f "$OLD_LOG" "$LOG"
  rm -f "$OLD_LOG.1" 2>/dev/null || true
elif [ -f "$OLD_LOG" ]; then
  cat "$OLD_LOG" >> "$LOG"
  rm -f "$OLD_LOG" "$OLD_LOG.1" 2>/dev/null || true
fi

# Decode Raspberry Pi get_throttled sticky / current bits into short tags.
decode_throttled() {
  local h=$1 n=0 tags=
  case "$h" in
    0x*|0X*) h=${h#0x}; h=${h#0X} ;;
    *) echo "?"; return ;;
  esac
  [[ "$h" =~ ^[0-9a-fA-F]+$ ]] || { echo "?"; return; }
  n=$((16#$h))
  [ $((n & 1))   -ne 0 ] && tags="${tags}+uv_now"
  [ $((n & 2))   -ne 0 ] && tags="${tags}+freq_now"
  [ $((n & 4))   -ne 0 ] && tags="${tags}+throt_now"
  [ $((n & 8))   -ne 0 ] && tags="${tags}+temp_now"
  [ $((n & 0x10000)) -ne 0 ] && tags="${tags}+uv_was"
  [ $((n & 0x20000)) -ne 0 ] && tags="${tags}+freq_was"
  [ $((n & 0x40000)) -ne 0 ] && tags="${tags}+throt_was"
  [ $((n & 0x80000)) -ne 0 ] && tags="${tags}+temp_was"
  echo "${tags:-+ok}"
}

bt_dev() {
  # $1 = MAC (AA:BB:...); echo connected|no|?
  local mac=$1 connected=
  [ -n "$mac" ] || { echo ""; return; }
  connected=$(timeout 2 bluetoothctl info "$mac" 2>/dev/null | awk -F': ' '/Connected:/ {print $2; exit}')
  case "$connected" in
    yes) echo connected ;;
    no) echo no ;;
    *) echo "?" ;;
  esac
}

a2dp_sink() {
  # PipeWire bluez sink state for the car (RUNNING / IDLE / SUSPENDED / -).
  local mac=$1 re state=
  [ -n "$mac" ] || { echo ""; return; }
  re=${mac//:/_}
  state=$(runuser -u "$AUDIO_USER" -- env XDG_RUNTIME_DIR=/run/user/$AUDIO_UID \
            pactl list sinks short 2>/dev/null \
          | awk -v m="bluez_output.$re" 'index($2,m)==1 {print $NF; exit}')
  echo "${state:--}"
}

snap() {
  . /etc/auxlink.conf 2>/dev/null || true
  local src=${MUSIC_SOURCE:-?}
  local udc_name="" udc_state="" gadget_bound="" card=0 tty=0
  udc_name=$(ls /sys/class/udc 2>/dev/null | head -1)
  if [ -n "$udc_name" ]; then
    udc_state=$(cat "/sys/class/udc/$udc_name/state" 2>/dev/null || echo "?")
  else
    udc_state=none
  fi
  gadget_bound=$(cat /sys/kernel/config/usb_gadget/auxlink/UDC 2>/dev/null | tr -d '\n')
  [ -z "$gadget_bound" ] && gadget_bound=unbound
  grep -q "UAC1" /proc/asound/cards 2>/dev/null && card=1
  [ -e /dev/ttyGS0 ] && tty=1

  local thr thr_tags temp volts load mem
  thr=$(vcgencmd get_throttled 2>/dev/null | cut -d= -f2)
  thr=${thr:-?}
  thr_tags=$(decode_throttled "$thr" 2>/dev/null || echo "?")
  temp=$(vcgencmd measure_temp 2>/dev/null | cut -d= -f2)
  temp=${temp:-?}
  volts=$(vcgencmd measure_volts 2>/dev/null | cut -d= -f2)
  volts=${volts:-?}
  load=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)
  mem=$(awk '/MemAvailable/ {printf "%dMB", $2/1024; exit}' /proc/meminfo 2>/dev/null)

  local app_age=-1 now
  now=$(date +%s)
  if [ -f /run/auxlink/app-link ]; then
    local ts
    ts=$(cat /run/auxlink/app-link 2>/dev/null | tr -dc '0-9')
    if [ -n "$ts" ]; then
      app_age=$((now - ts))
      [ "$app_age" -lt 0 ] && app_age=0
    fi
  fi

  local sound="?" play="?" car_off=0 call=0 car_conn="?" car_slc="?"
  [ -f "$PRESENT_FILE" ] && sound=$(tr -d '\n' < "$PRESENT_FILE")
  [ -f /run/auxlink/play ] && play=$(tr -d '\n' < /run/auxlink/play)
  [ -e /run/auxlink/car-off ] && car_off=1
  [ -f /run/auxlink/call ] && call=$(tr -d '\n' < /run/auxlink/call)
  [ -f /run/auxlink/car-connected ] && car_conn=$(tr -d '\n' < /run/auxlink/car-connected)
  [ -f /run/auxlink/car-slc ] && car_slc=$(tr -d '\n' < /run/auxlink/car-slc)

  local loop=0
  pgrep -f 'pw-loopback.*to_tesla' >/dev/null 2>&1 && loop=1

  local car_bt phone_bt a2dp
  car_bt=$(bt_dev "${CAR:-}")
  phone_bt=$(bt_dev "${PHONE:-}")
  a2dp=$(a2dp_sink "${CAR:-}")

  # PipeWire nodes that mention the UDC (USB-C sound card) — state only.
  local uac_src="-" uac_sink="-"
  if [ -n "$udc_name" ]; then
    uac_src=$(runuser -u "$AUDIO_USER" -- env XDG_RUNTIME_DIR=/run/user/$AUDIO_UID \
      pactl list sources short 2>/dev/null \
      | awk -v u="$udc_name" 'index($2,u) && $2 !~ /\.monitor$/ {print $NF; exit}')
    uac_sink=$(runuser -u "$AUDIO_USER" -- env XDG_RUNTIME_DIR=/run/user/$AUDIO_UID \
      pactl list sinks short 2>/dev/null \
      | awk -v u="$udc_name" 'index($2,u) {print $NF; exit}')
    uac_src=${uac_src:--}
    uac_sink=${uac_sink:--}
  fi

  local up
  up=$(cut -d. -f1 /proc/uptime)

  # Compact one-line snapshot (easy to grep / diff).
  printf 'src=%s udc=%s bound=%s card=%s tty=%s app_s=%s sound=%s play=%s loop=%s uac_src=%s uac_sink=%s car_bt=%s car_flag=%s car_slc=%s a2dp=%s phone_bt=%s car_off=%s call=%s thr=%s%s temp=%s volts=%s load=%s mem=%s up=%ss' \
    "$src" "$udc_state" "$gadget_bound" "$card" "$tty" "$app_age" "$sound" "$play" "$loop" \
    "$uac_src" "$uac_sink" "$car_bt" "$car_conn" "$car_slc" "${a2dp:-none}" "$phone_bt" \
    "$car_off" "$call" "$thr" "$thr_tags" "$temp" "$volts" "$load" "${mem:-?}" "$up"
}

# Follow relevant kernel messages into the same file.
kern_follow() {
  journalctl -k -f -n 0 --output=short-iso 2>/dev/null \
    | grep --line-buffered -iE "$KERN_RE" \
    | while IFS= read -r line; do
        emit "$(date -Iseconds) [kern] $line"
      done
}

emit "---------- boot $(date -Iseconds) up=$(cut -d. -f1 /proc/uptime)s ver=$(cat /usr/local/share/auxlink/VERSION 2>/dev/null || echo ?) ----------"
emit "$(date -Iseconds) [boot] auxlink-preheat-debug starting (interval=${INTERVAL}s heartbeat=${HEARTBEAT}s) — previous lines above are kept"
emit "$(date -Iseconds) [boot] $(snap)"

kern_follow &
KERN_PID=$!
trap 'kill $KERN_PID 2>/dev/null; emit "$(date -Iseconds) [boot] auxlink-preheat-debug stopping"; exit 0' TERM INT

last=""
last_hb=0
while true; do
  s=$(snap)
  now=$(date +%s)
  if [ "$s" != "$last" ]; then
    emit "$(date -Iseconds) [chg] $s"
    last=$s
    last_hb=$now
  elif [ $((now - last_hb)) -ge "$HEARTBEAT" ]; then
    emit "$(date -Iseconds) [hb]  $s"
    last_hb=$now
  fi
  sleep "$INTERVAL"
done
