#!/usr/bin/env python3
"""Talk to one Bluetooth device through one specific adapter (by address).

With two dongles, hci0/hci1 and bluetoothctl's "default" controller can swap
between boots, so plain `bluetoothctl connect` may use the wrong dongle.

  btdev.py ADAPTER DEVICE connected        exit 0 if connected
  btdev.py ADAPTER DEVICE connect [UUID]   connect (optionally one profile)
  btdev.py ADAPTER DEVICE trust            mark trusted
  btdev.py ADAPTER present                 exit 0 if the adapter exists
  btdev.py ADAPTER power                   switch the adapter on
  btdev.py ADAPTER alias NAME              set the adapter's Bluetooth name (if different)
"""
import sys
import dbus

BLUEZ = "org.bluez"


def adapter_path(bus, addr):
    om = dbus.Interface(bus.get_object(BLUEZ, "/"), "org.freedesktop.DBus.ObjectManager")
    for path, ifaces in om.GetManagedObjects().items():
        a = ifaces.get("org.bluez.Adapter1")
        if a and str(a["Address"]).upper() == addr.upper():
            return path
    return None


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    bus = dbus.SystemBus()
    ap = adapter_path(bus, sys.argv[1])
    if sys.argv[2] == "present":
        return 0 if ap else 1
    if sys.argv[2] == "alias" and len(sys.argv) > 3:
        if not ap:
            return 1
        p = dbus.Interface(bus.get_object(BLUEZ, ap), "org.freedesktop.DBus.Properties")
        if str(p.Get("org.bluez.Adapter1", "Alias")) != sys.argv[3]:
            p.Set("org.bluez.Adapter1", "Alias", dbus.String(sys.argv[3]))
            print(f"Renamed {sys.argv[1]} to {sys.argv[3]}")
        return 0
    if sys.argv[2] == "power":
        if not ap:
            return 1
        p = dbus.Interface(bus.get_object(BLUEZ, ap), "org.freedesktop.DBus.Properties")
        p.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
        return 0
    if not ap:
        print(f"adapter {sys.argv[1]} not found", file=sys.stderr)
        return 1
    dev, cmd = sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "connected"
    path = f"{ap}/dev_{dev.upper().replace(':', '_')}"
    try:
        obj = bus.get_object(BLUEZ, path)
        props = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
        if cmd == "connected":
            return 0 if props.Get("org.bluez.Device1", "Connected") else 1
        d = dbus.Interface(obj, "org.bluez.Device1")
        if cmd == "connect":
            if len(sys.argv) > 4:
                d.ConnectProfile(sys.argv[4], timeout=20)
            else:
                d.Connect(timeout=20)
            print("Connection successful")
            return 0
        if cmd == "trust":
            props.Set("org.bluez.Device1", "Trusted", dbus.Boolean(True))
            return 0
    except dbus.DBusException as e:
        print(e.get_dbus_message() or e.get_dbus_name(), file=sys.stderr)
        return 1
    print(f"unknown command {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
