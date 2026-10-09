#!/bin/bash
# USB-C music source (MUSIC_SOURCE=usbc): make the Pi's USB-C port a USB
# device for the music source (phone/SMO), with
#   * a USB sound card (UAC1, 48 kHz stereo) - the music comes in here,
#     with a mic (mono) carrying the car's cabin mic for voice search,
#   * a serial port (ACM)  - the now-playing app sends track info / art,
#   * a media-key keyboard (HID consumer control) - wheel buttons go back.
# The USB-C port is also the Pi's power input: in this mode the Pi must be
# POWERED EXTERNALLY (5 V GPIO pins, or a USB-C power/data splitter).
# Needs dtoverlay=dwc2,dr_mode=peripheral in config.txt (set by the setup
# page when USB-C is chosen) and a reboot.
#   auxlink-usb-gadget.sh          set up (or tear down if another source is selected)
#   auxlink-usb-gadget.sh stop     tear down
G=/sys/kernel/config/usb_gadget/auxlink

teardown() {
  [ -d "$G" ] || return 0
  echo "" > "$G/UDC" 2>/dev/null
  rm -f "$G"/configs/c.1/*.usb0
  rmdir "$G"/configs/c.1/strings/0x409 "$G"/configs/c.1 2>/dev/null
  rmdir "$G"/functions/* "$G"/strings/0x409 2>/dev/null
  rmdir "$G" 2>/dev/null && echo "USB-C gadget removed"
}

. /etc/auxlink.conf
if [ "$1" = stop ] || [ "${MUSIC_SOURCE:-wired}" != usbc ]; then
  teardown; exit 0
fi

modprobe libcomposite 2>/dev/null
mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config
UDC=$(ls /sys/class/udc 2>/dev/null | head -1)
if [ -z "$UDC" ]; then
  echo "No USB device controller: dtoverlay=dwc2,dr_mode=peripheral is missing or the Pi has not been rebooted since"
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
