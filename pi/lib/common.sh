# Shared helpers for the auxlink shell scripts.  Source, don't run.
. /etc/auxlink.conf
BT=/usr/local/bin/btdev.py
A2DP_SINK_UUID=0000110b-0000-1000-8000-00805f9b34fb
CARD=bluez_card.${CAR//:/_}
CAR_RE=${CAR//:/[:_]}              # PipeWire names use : or _ in addresses
CALL_STATE_FILE=/run/auxlink/call

# The XIAO I2S input, found by its ALSA card name so it works on any Pi.
i2s_source() {
  timeout 5 pactl list sources 2>/dev/null |
    awk '/^[ \t]*Name: /{n=$2} /alsa.card_name = "xiaoi2s"/{print n; exit}'
}
# The Pi's USB-C port as a USB sound card (MUSIC_SOURCE=usbc): its ALSA card.
usbc_source() {
  timeout 5 pactl list sources 2>/dev/null |
    awk '/^[ \t]*Name: /{n=$2} /alsa.card_name = "(UAC1Gadget|UAC1_Gadget)"/ && n !~ /\.monitor$/ {print n; exit}'
}
# A Bluetooth music source's input node (MUSIC_SOURCE=bluetooth), if streaming
# is set up: bluez_input.<MAC with _>.<n>.
bt_source() {
  [ -n "$SOURCE" ] || return 0
  timeout 5 pactl list sources short 2>/dev/null | awk -v m="bluez_input.${SOURCE//:/_}" 'index($2, m) == 1 {print $2; exit}'
}
# Whichever input MUSIC_SOURCE selects (empty if it is not there right now).
music_input() {
  case "${MUSIC_SOURCE:-wired}" in
    bluetooth) bt_source ;;
    usbc) usbc_source ;;
    *) i2s_source ;;
  esac
}
# "id<TAB>name<TAB>...<TAB>STATE" line for the car's Bluetooth output, if any.
car_sink_line() {
  timeout 5 pactl list sinks short 2>/dev/null | grep -E "bluez_output\.$CAR_RE" | head -1
}
car_transport_state() {
  local tp
  tp=$(timeout 5 bluetoothctl transport.list 2>/dev/null |
       grep -o "/org/bluez/hci[0-9]*/dev_${CAR//:/_}/sep[0-9]*/fd[0-9]*" | head -1)
  [ -n "$tp" ] && timeout 5 bluetoothctl transport.show "$tp" 2>/dev/null | awk '/State:/ {print $2}'
}
in_call() { [ "$(cat "$CALL_STATE_FILE" 2>/dev/null)" = 1 ]; }

# Fast Bluetooth checks for the polling loops. btdev.py starts Python and
# lists every BlueZ object - a few hundred ms of CPU each time, many times a
# minute. busctl reads the one property instead. The adapter's object path
# (hciN can change after a reset or replug) comes from btdev.py once and is
# re-checked by its address on every use. If busctl doesn't work here at
# all, BT_FAST=0 and everything goes through btdev.py as before.
declare -A BT_APATH
BT_FAST=1
bt_apath() {   # sets BT_AP to adapter $1's object path; false if it isn't there
  local want="s \"${1^^}\""
  BT_AP=${BT_APATH[$1]:-}
  if [ -n "$BT_AP" ] && [ "$(timeout 5 busctl get-property org.bluez "$BT_AP" org.bluez.Adapter1 Address 2>/dev/null)" = "$want" ]; then
    return 0
  fi
  BT_AP=$($BT "$1" path 2>/dev/null) || { BT_APATH[$1]=""; BT_AP=""; return 1; }
  BT_APATH[$1]=$BT_AP
  # Just found by btdev.py: if busctl can't read it, busctl is no use here.
  [ "$(timeout 5 busctl get-property org.bluez "$BT_AP" org.bluez.Adapter1 Address 2>/dev/null)" = "$want" ] || BT_FAST=0
  return 0
}
bt_present() {   # adapter $1 exists
  [ "$BT_FAST" = 1 ] || { $BT "$1" present 2>/dev/null; return; }
  bt_apath "$1"
}
bt_power() {   # switch adapter $1 on (only calls btdev.py if it is off)
  if [ "$BT_FAST" = 1 ] && bt_apath "$1" &&
     [ "$(timeout 5 busctl get-property org.bluez "$BT_AP" org.bluez.Adapter1 Powered 2>/dev/null)" = "b true" ]; then
    return 0
  fi
  $BT "$1" power
}
bt_alias() {   # name adapter $1 $2 (only calls btdev.py if it differs)
  if [ "$BT_FAST" = 1 ] && bt_apath "$1" &&
     [ "$(timeout 5 busctl get-property org.bluez "$BT_AP" org.bluez.Adapter1 Alias 2>/dev/null)" = "s \"$2\"" ]; then
    return 0
  fi
  $BT "$1" alias "$2"
}
bt_connected() {   # device $2 is connected through adapter $1
  [ "$BT_FAST" = 1 ] || { $BT "$1" "$2" connected 2>/dev/null; return; }
  local d=${2^^} v
  bt_apath "$1" || return 1
  [ "$BT_FAST" = 1 ] || { $BT "$1" "$2" connected 2>/dev/null; return; }
  # No such device object (never paired on this adapter): not connected.
  v=$(timeout 5 busctl get-property org.bluez "$BT_AP/dev_${d//:/_}" org.bluez.Device1 Connected 2>/dev/null) || return 1
  [ "$v" = "b true" ]
}
# A pairing handshake is under way (auxlink-pairing writes its deadline here):
# don't touch any adapter until it is done.
pairing_active() {
  local until; until=$(cat /run/auxlink/pairing-active 2>/dev/null) || return 1
  [ -n "$until" ] && [ "$until" -gt "$(date +%s)" ]
}
