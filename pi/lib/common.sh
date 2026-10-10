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
# A pairing handshake is under way (auxlink-pairing writes its deadline here):
# don't touch any adapter until it is done.
pairing_active() {
  local until; until=$(cat /run/auxlink/pairing-active 2>/dev/null) || return 1
  [ -n "$until" ] && [ "$until" -gt "$(date +%s)" ]
}
