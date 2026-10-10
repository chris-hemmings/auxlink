#!/bin/bash
# USB-C music source (MUSIC_SOURCE=usbc): make the Pi's USB-C port a USB
# device for the music source (phone/SMO), with
#   * a USB sound card (UAC1, 48 kHz stereo) - the music comes in here,
#     with a mic (mono) carrying the car's cabin mic for voice search,
#   * a serial port (ACM)  - the now-playing app sends track info / art,
#   * a media-key keyboard (HID consumer control) - wheel buttons go back.
# The USB-C port is also the Pi's power input: in this mode the Pi should be
# powered externally for reliable use (5 V GPIO pins, or a USB-C power/data
# splitter); powered by the music device alone it may drop out or restart.
# Needs dtoverlay=dwc2,dr_mode=peripheral in config.txt (set by the setup
# page when USB-C is chosen) and a reboot.
#   auxlink-usb-gadget.sh          set up (or tear down if another source is selected)
#   auxlink-usb-gadget.sh stop     tear down
G=/sys/kernel/config/usb_gadget/auxlink
BOOT_CONFIG=/boot/firmware/config.txt
OVERLAY="dtoverlay=dwc2,dr_mode=peripheral"

event() {   # also shown in the setup page's Recent events
  mkdir -p /run/auxlink && echo "$(date +%H:%M:%S) $1" >> /run/auxlink/events.log
}

# Let go of the gadget's sound card before taking it down: removing it while
# PipeWire still has it open (the music loop streaming from it) hangs in the
# kernel, and the gadget then can't be removed or re-made until a reboot. So
# stop the music loop and suspend the card's inputs/outputs (this closes the
# device; unlike switching its profile off, nothing is remembered).
asaudio() {
  local user=${AUDIO_USER:-chris} uid
  uid=$(id -u "$user" 2>/dev/null) || return 1
  runuser -u "$user" -- env XDG_RUNTIME_DIR=/run/user/$uid "$@"
}
release_card() {
  local udc n
  udc=$(cat "$G/UDC" 2>/dev/null); [ -n "$udc" ] || return 0
  asaudio timeout 10 systemctl --user stop auxlink-audio 2>/dev/null
  for n in $(asaudio timeout 5 pactl list sources short 2>/dev/null | awk -v u="$udc" 'index($2, u) && $2 !~ /\.monitor$/ {print $2}'); do
    asaudio timeout 5 pactl suspend-source "$n" 1 2>/dev/null
  done
  for n in $(asaudio timeout 5 pactl list sinks short 2>/dev/null | awk -v u="$udc" 'index($2, u) {print $2}'); do
    asaudio timeout 5 pactl suspend-sink "$n" 1 2>/dev/null
  done
  sleep 1
  RESTART_AUDIO=1
}

teardown() {
  [ -d "$G" ] || return 0
  release_card
  echo "" > "$G/UDC" 2>/dev/null
  rm -f "$G"/configs/c.1/*.usb0
  rmdir "$G"/configs/c.1/strings/0x409 "$G"/configs/c.1 2>/dev/null
  rmdir "$G"/functions/* "$G"/strings/0x409 2>/dev/null
  rmdir "$G" 2>/dev/null && echo "USB-C gadget removed"
  [ "${RESTART_AUDIO:-0}" = 1 ] && asaudio systemctl --user start auxlink-audio 2>/dev/null
  RESTART_AUDIO=0
}

. /etc/auxlink.conf
if [ "$1" = stop ] || [ "${MUSIC_SOURCE:-wired}" != usbc ]; then
  teardown; exit 0
fi

modprobe libcomposite 2>/dev/null
mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config
UDC=$(ls /sys/class/udc 2>/dev/null | head -1)
if [ -z "$UDC" ]; then
  # The USB-C port only becomes a device with this line in config.txt (the
  # setup page adds it when USB-C is chosen; make sure, whatever happened).
  if ! grep -qxF "$OVERLAY" "$BOOT_CONFIG" 2>/dev/null; then
    printf '\n[all]\n# auxlink: USB-C music source (Pi as a USB sound card)\n%s\n' "$OVERLAY" >> "$BOOT_CONFIG"
    sync
    echo "Added $OVERLAY to $BOOT_CONFIG: reboot once to finish setting up USB-C"
    event "USB-C mode set up: reboot once to finish"
  else
    echo "No USB device controller yet: reboot once to finish setting up USB-C"
  fi
  exit 0
fi
# Already up and bound (e.g. after an update): leave it alone, so the music
# device stays connected.
if [ -n "$(cat "$G/UDC" 2>/dev/null)" ]; then
  echo "USB-C gadget already up on $(cat "$G/UDC"); left as it is"
  exit 0
fi
teardown
mkdir -p "$G" && cd "$G" || exit 1
echo 0x1209 > idVendor          # pid.codes test VID, as the XIAO
echo 0x0002 > idProduct         # the XIAO is 0x0001
echo 0x0200 > bcdUSB
mkdir -p strings/0x409
echo "AuxLink" > strings/0x409/manufacturer
echo "AuxLink USB audio" > strings/0x409/product
cat /proc/device-tree/serial-number 2>/dev/null | tr -d '\0' > strings/0x409/serialnumber
mkdir -p configs/c.1/strings/0x409
echo "music in" > configs/c.1/strings/0x409/configuration
echo 0xC0 > configs/c.1/bmAttributes   # self-powered: the Pi has its own supply
echo 2 > configs/c.1/MaxPower

# Sound card: the host plays into it (gadget capture), and records from its
# mic (gadget playback, mono) - auxlink-media plays the car's cabin mic
# into that while the host records, for its voice search.
mkdir -p functions/uac1.usb0
echo 3 > functions/uac1.usb0/c_chmask
echo 48000 > functions/uac1.usb0/c_srate
echo 2 > functions/uac1.usb0/c_ssize
echo 1 > functions/uac1.usb0/p_chmask
echo 48000 > functions/uac1.usb0/p_srate
echo 2 > functions/uac1.usb0/p_ssize

# Serial port for the now-playing app (shows up as /dev/ttyGS0 here).
mkdir -p functions/acm.usb0

# Media keys: the same HID consumer-control report as the XIAO.
mkdir -p functions/hid.usb0
echo 0 > functions/hid.usb0/protocol
echo 0 > functions/hid.usb0/subclass
echo 2 > functions/hid.usb0/report_length
printf '\x05\x0c\x09\x01\xa1\x01\x15\x00\x26\xff\x03\x19\x00\x2a\xff\x03\x75\x10\x95\x01\x81\x00\xc0' \
  > functions/hid.usb0/report_desc

for f in uac1.usb0 acm.usb0 hid.usb0; do ln -s "functions/$f" configs/c.1/; done
echo "$UDC" > UDC && echo "USB-C gadget up on $UDC: sound card + serial + media keys"
