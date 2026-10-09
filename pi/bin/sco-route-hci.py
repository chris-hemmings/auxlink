#!/usr/bin/env python3
"""Route call audio (SCO) of the Pi's built-in Bluetooth over HCI.

The Pi's Broadcom/Cypress Bluetooth chip sends call audio to its PCM pins by
default, so Linux never sees it. This vendor command (Write_SCO_PCM_Int_Param,
opcode 0xFC1C) tells it to carry SCO over the normal HCI link instead, which
is what hfp-relay needs. It has to be sent after every power-up of the chip.
"""
import glob
import os
import socket
import sys

OPCODE = (0x3F << 10) | 0x01C            # vendor-specific 0xFC1C
PARAMS = bytes([0x01, 0x02, 0x00, 0x01, 0x01])  # routing=HCI, 2 MHz, short frame, slave, clock


def builtin_hci():
    """The built-in chip hangs off the UART, not USB."""
    for path in sorted(glob.glob("/sys/class/bluetooth/hci*")):
        if "serial" in os.path.realpath(os.path.join(path, "device")):
            return int(os.path.basename(path)[3:])
    return None


def main():
    dev = builtin_hci()
    if dev is None:
        print("Built-in Bluetooth not found (is dtoverlay=disable-bt still set?)", file=sys.stderr)
        return 1
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
    s.bind((dev,))
    pkt = bytes([0x01, OPCODE & 0xFF, OPCODE >> 8, len(PARAMS)]) + PARAMS
    s.send(pkt)
    s.close()
    print(f"hci{dev}: SCO routed over HCI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
