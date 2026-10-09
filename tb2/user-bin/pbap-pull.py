#!/usr/bin/env python3
"""Download the Oppo's contacts (and call history) over Bluetooth PBAP.

Runs as your normal user (obexd lives on the user session bus). Writes vCard
files into ~/phonebook/ in the layout BlueZ's own PBAP server reads, so the
car can later be served the same data:

  ~/phonebook/telecom/pb.vcf    contacts
  ~/phonebook/telecom/ich.vcf   incoming calls
  ~/phonebook/telecom/och.vcf   outgoing calls
  ~/phonebook/telecom/mch.vcf   missed calls
  ~/phonebook/telecom/cch.vcf   combined history

Each book is also split into one card per file (telecom/pb/0.vcf, 1.vcf, ...)
because BlueZ's dummy PBAP backend lists folders by reading those files.
"""
import os
import sys
import time

import dbus

def load_conf(path="/etc/teslabridge.conf"):
    conf = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            conf[k.strip()] = v.strip().strip('"')
    return conf


CONF = load_conf()
PHONE = CONF.get("PHONE", "").upper()
PHONE_ADAPTER = CONF.get("PHONE_ADAPTER", "").upper()
OUT = os.path.expanduser("~/phonebook/telecom")
BOOKS = ["pb", "ich", "och", "mch", "cch"]


def wait(bus, path, timeout=120):
    props = dbus.Interface(bus.get_object("org.bluez.obex", path), "org.freedesktop.DBus.Properties")
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            st = props.Get("org.bluez.obex.Transfer1", "Status")
        except dbus.DBusException:
            return "complete"  # transfer object removed once finished
        if st in ("complete", "error"):
            return st
        time.sleep(0.5)
    return "timeout"


def split(book):
    """telecom/<book>.vcf -> telecom/<book>/<n>.vcf, one vCard per file."""
    src = os.path.join(OUT, f"{book}.vcf")
    folder = os.path.join(OUT, book)
    os.makedirs(folder, exist_ok=True)
    for f in os.listdir(folder):
        if f.endswith(".vcf"):
            os.remove(os.path.join(folder, f))
    text = open(src, encoding="utf-8", errors="replace").read()
    cards, cur = [], []
    for line in text.splitlines(keepends=True):
        cur.append(line)
        if line.strip().upper() == "END:VCARD":
            cards.append("".join(cur))
            cur = []
    for i, card in enumerate(cards):
        with open(os.path.join(folder, f"{i}.vcf"), "w", encoding="utf-8") as fh:
            fh.write(card)
    return len(cards)


def main():
    bus = dbus.SessionBus()
    client = dbus.Interface(bus.get_object("org.bluez.obex", "/org/bluez/obex"), "org.bluez.obex.Client1")
    print("Connecting to the Oppo for contacts... accept the prompt on the phone if it asks.")
    session = client.CreateSession(
        PHONE, dbus.Dictionary({"Target": "PBAP", "Source": PHONE_ADAPTER}, signature="sv"))
    pbap = dbus.Interface(bus.get_object("org.bluez.obex", session), "org.bluez.obex.PhonebookAccess1")
    os.makedirs(OUT, exist_ok=True)
    try:
        for book in BOOKS:
            try:
                pbap.Select("int", book)
                tmp = os.path.join(OUT, f".{book}.vcf.part")
                path, _ = pbap.PullAll(tmp, dbus.Dictionary({"Format": "vcard30"}, signature="sv"))
                st = wait(bus, path)
                if st == "complete" and os.path.exists(tmp):
                    os.replace(tmp, os.path.join(OUT, f"{book}.vcf"))
                    n = split(book)
                    print(f"{book}: {n} entries")
                else:
                    print(f"{book}: {st}")
            except dbus.DBusException as e:
                print(f"{book}: {e.get_dbus_message() or e.get_dbus_name()}")
    finally:
        client.RemoveSession(session)
    return 0


if __name__ == "__main__":
    sys.exit(main())
