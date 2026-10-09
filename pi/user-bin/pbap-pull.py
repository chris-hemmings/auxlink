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

The per-card files are trimmed to the fields the car uses (name, numbers,
call time). The dummy backend copies each card through a fixed 1024-byte
buffer and silently cuts off anything longer - including the END:VCARD
line - so a contact with a photo, addresses or notes ran straight into the
next card, and the car filed the next person's numbers under it.
"""
import os
import sys
import time

import dbus

def load_conf(path="/etc/auxlink.conf"):
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
# Fields kept in the per-card files, and asked of the phone.
KEEP = ("VERSION", "FN", "N", "TEL", "X-IRMC-CALL-DATETIME")
# obexd-dummy re-serialises each card into a 1024-byte buffer; stay well
# under it (non-ASCII text can grow when re-encoded).
CARD_BUDGET = 900


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


def card_cost(lines):
    """Rough worst-case size once obexd-dummy re-encodes the card."""
    return sum(len(l) + 2 * sum(1 for ch in l.encode("utf-8") if ch > 127) + 16
               for l in lines)


def trim(card):
    """One vCard -> just the fields in KEEP, small enough for obexd-dummy.

    Folded lines (continuations start with a space or tab, e.g. a PHOTO's
    base64) are joined first so a dropped field takes all its lines along."""
    props = []
    for line in card.splitlines():
        if line[:1] in (" ", "\t") and props:
            props[-1] += line[1:]
        elif line.strip():
            props.append(line.rstrip("\r\n"))
    body = []
    for p in props:
        head, sep, value = p.partition(":")
        name, *params = head.split(";")
        name = name.rsplit(".", 1)[-1]    # "item1.TEL" grouping prefix
        if not sep or name.upper() not in KEEP:
            continue
        # obexd-dummy's parser (libical) rejects the WHOLE card if a
        # parameter name or value doesn't start with a letter - Android's
        # call history writes "TEL;TYPE=0:...", which dropped every call.
        params = [q for q in params
                  if q and all(w[:1].isalpha() for w in q.split("="))]
        body.append(";".join([name] + params) + ":" + value)
    # A contact with many numbers: drop the last ones until it fits.
    while card_cost(["BEGIN:VCARD", "END:VCARD"] + body) > CARD_BUDGET:
        tels = [i for i, p in enumerate(body) if p.split(":", 1)[0].split(";", 1)[0].upper() == "TEL"]
        if not tels:
            break
        del body[tels[-1]]
    return "BEGIN:VCARD\r\n" + "".join(p + "\r\n" for p in body) + "END:VCARD\r\n"


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
            fh.write(trim(card))
    return len(cards)


def pull(pbap, tmp):
    """PullAll, asking the phone for only the fields we keep (no photos).
    A phone or obexd that rejects the filter gets a plain pull instead;
    split() trims the cards either way."""
    try:
        return pbap.PullAll(tmp, dbus.Dictionary(
            {"Format": "vcard30", "Fields": dbus.Array(KEEP, signature="s")}, signature="sv"))
    except dbus.DBusException as e:
        if "InvalidArguments" not in (e.get_dbus_name() or ""):
            raise
        return pbap.PullAll(tmp, dbus.Dictionary({"Format": "vcard30"}, signature="sv"))


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
                path, _ = pull(pbap, tmp)
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
