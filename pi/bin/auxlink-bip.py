#!/usr/bin/env python3
"""Fetch album art from a Bluetooth music source (AVRCP 1.6 cover art).

Runs as the audio user (obexd lives on that user's session bus), started and
driven by auxlink-media over stdin/stdout, one JSON object per line:

  {"cmd": "connect", "dest": MAC, "source": adapter MAC, "psm": n}
      -> {"connected": true} / {"error": "..."}
     Opens the cover-art (BIP) session. The source only gives out image
     handles while this session is open, so it stays open.
  {"cmd": "get", "handle": "1234567", "file": path}
      -> {"got": handle, "file": path} / {"error": "...", "handle": ...}
     Downloads that image (the JPEG closest to 200x200 the source offers,
     else its native image) to path.
  {"cmd": "close"}
"""
import json
import sys
import time

import dbus

OBEX = "org.bluez.obex"
session = None


def reply(**kw):
    print(json.dumps(kw), flush=True)


def connect(bus, msg):
    global session
    close(bus)
    client = dbus.Interface(bus.get_object(OBEX, "/org/bluez/obex"), "org.bluez.obex.Client1")
    session = client.CreateSession(msg["dest"], dbus.Dictionary({
        "Target": "bip-avrcp",
        "Source": msg["source"],
        "PSM": dbus.UInt16(int(msg["psm"])),
    }, signature="sv"))
    reply(connected=True)


def close(bus):
    global session
    if session is None:
        return
    try:
        dbus.Interface(bus.get_object(OBEX, "/org/bluez/obex"),
                       "org.bluez.obex.Client1").RemoveSession(session)
    except dbus.DBusException:
        pass
    session = None


def best_description(image, handle):
    """The JPEG variant nearest 200x200 with a fixed size, else native ({})."""
    try:
        props = image.Properties(handle)
    except dbus.DBusException:
        return {}
    best, best_d = {}, None
    for p in props:
        if str(p.get("encoding", "")).upper() != "JPEG":
            continue
        pixel = str(p.get("pixel", ""))
        if "-" in pixel or "*" not in pixel:
            continue
        try:
            w, h = (int(x) for x in pixel.split("*"))
        except ValueError:
            continue
        d = abs(max(w, h) - 200)
        if best_d is None or d < best_d:
            best_d = d
            best = {} if str(p.get("type")) == "native" else {"encoding": "JPEG", "pixel": pixel}
    return best


def get(bus, msg):
    if session is None:
        raise RuntimeError("not connected")
    image = dbus.Interface(bus.get_object(OBEX, session), "org.bluez.obex.Image1")
    desc = best_description(image, msg["handle"])
    transfer, _ = image.Get(msg["file"], msg["handle"], dbus.Dictionary(desc, signature="sv"))
    props = dbus.Interface(bus.get_object(OBEX, transfer), "org.freedesktop.DBus.Properties")
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            status = str(props.Get("org.bluez.obex.Transfer1", "Status"))
        except dbus.DBusException:
            status = "complete"      # the object goes away once it is done
        if status == "complete":
            reply(got=msg["handle"], file=msg["file"])
            return
        if status == "error":
            raise RuntimeError("transfer failed")
        time.sleep(0.1)
    raise RuntimeError("transfer timed out")


def main():
    bus = dbus.SessionBus()
    for line in sys.stdin:
        msg, cmd = {}, None
        try:
            msg = json.loads(line)
            cmd = msg.get("cmd")
            if cmd == "connect":
                connect(bus, msg)
            elif cmd == "get":
                get(bus, msg)
            elif cmd == "close":
                close(bus)
        except (dbus.DBusException, RuntimeError, ValueError, KeyError) as e:
            err = e.get_dbus_message() if isinstance(e, dbus.DBusException) else str(e)
            reply(error=err, cmd=cmd, handle=msg.get("handle") if isinstance(msg, dict) else None)
    close(bus)


if __name__ == "__main__":
    main()
