#!/usr/bin/env python3
"""Text messages from the phone (MESSAGES=1), phase 1: phone -> Pi.

Runs as the audio user (obexd lives on that user's session bus). Opens a
Bluetooth MAP session to the phone (Message Access), registers for new-
message notifications, and keeps the latest messages in
~/.local/share/auxlink/messages.json:
  [{"handle", "folder", "time", "sender", "number", "text", "read"}, ...]
New texts also go to the setup page's Recent events. Nothing is offered to
the car yet (that is phase 2).

obexd needs its MNS plugin for the notifications: auxlink keeps it on only
while MESSAGES=1 (it runs `obexd-dummy -P mas` then, `-P mas,mns` otherwise).
"""
import json
import os
import sys
import time

import dbus
import dbus.mainloop.glib
from gi.repository import GLib


def load_conf(path="/etc/auxlink.conf"):
    conf = {}
    try:
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                conf[k.strip()] = v.strip().strip('"')
    except OSError:
        pass
    return conf


CONF = load_conf()
PHONE = CONF.get("PHONE", "").upper()
PHONE_ADAPTER = CONF.get("PHONE_ADAPTER", "").upper()
OBEX = "org.bluez.obex"
STORE = os.path.expanduser("~/.local/share/auxlink/messages.json")
CACHE = os.path.expanduser("~/.cache/auxlink")
EVENTS = "/run/auxlink/events.log"
OUTBOX = "/run/auxlink/outbox"      # replies from the car (auxlink-map) to send
KEEP = 50


def log(msg):
    print(msg, flush=True)


def event(msg):
    log(msg)
    try:
        with open(EVENTS, "a") as f:
            f.write(time.strftime("%H:%M:%S ") + msg + "\n")
    except OSError:
        pass


def mime_text(body):
    """MMS/RCS messages arrive as an e-mail style document (Date:, From:,
    Content-Type..., often multipart): keep only the readable text."""
    import email
    from email import policy
    head = body.lstrip()[:400].lower()
    if not any(h in head for h in ("content-type:", "date:", "mime-version:", "subject:")):
        return body
    try:
        msg = email.message_from_bytes(body.lstrip().encode("utf-8"), policy=policy.default)

        def content(part):
            # Not get_content(): it turns badly encoded bytes into "�".
            raw = part.get_payload(decode=True) or b""
            cs = (part.get_content_charset() or "").lower().replace("_", "-")
            if cs and cs not in ("utf-8", "utf8", "us-ascii"):
                try:
                    return raw.decode(cs).strip()
                except (LookupError, UnicodeDecodeError):
                    pass
            return decode_text(raw)[0].strip()

        parts = []
        for part in msg.walk():
            if part.get_content_type() == "text/plain" and not part.is_attachment():
                parts.append(content(part))
        if not parts and msg.get_content_type().startswith("text/") and not msg.is_multipart():
            parts.append(content(msg))
        text = "\n".join(p for p in parts if p)
        if not text:
            subject = str(msg.get("Subject", "") or "").strip()
            kinds = {p.get_content_maintype() for p in msg.walk() if not p.is_multipart()}
            text = subject or ("[picture]" if "image" in kinds else "[attachment]")
        return text
    except Exception:
        return body


def decode_text(raw):
    """Phone bytes -> text, without losing characters. Phones don't always
    send plain UTF-8: Android often sends emoji as Java's "modified UTF-8"
    (each emoji as two 3-byte halves), some send UTF-16, a few Latin-1."""
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff") or (len(raw) > 8 and raw.count(b"\0") > len(raw) // 4):
        try:
            return raw.decode("utf-16"), "utf-16"
        except UnicodeDecodeError:
            pass
    try:
        return raw.decode("utf-8"), ""
    except UnicodeDecodeError:
        pass
    # Keep emoji halves (surrogates) and take any other stray byte as cp1252.
    try:
        text = raw.decode("utf-8", errors="surrogatepass")
    except UnicodeDecodeError:
        out, i = [], 0
        while i < len(raw):
            try:
                out.append(raw[i:].decode("utf-8", errors="surrogatepass"))
                break
            except UnicodeDecodeError as e:
                out.append(raw[i:i + e.start].decode("utf-8", errors="surrogatepass"))
                bad = raw[i + e.start:i + e.end]
                out.append(bad.decode("cp1252", errors="ignore") or bad.decode("latin-1"))
                i += e.end
        text = "".join(out)
    # Join the emoji halves back into whole emoji; drop any lone half.
    text = text.encode("utf-16-le", errors="surrogatepass").decode("utf-16-le", errors="ignore")
    return text, "repaired"


def parse_bmessage(text):
    """bMessage (MAP's message format) -> (sender name, number, body)."""
    name = number = ""
    body = []
    in_vcard = in_msg = False
    depth = 0
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        u = line.upper()
        if u == "BEGIN:VCARD" and not body and depth == 0:
            in_vcard = True
            continue
        if u == "END:VCARD":
            in_vcard = False
            continue
        if in_vcard and not (name and number):
            # The first vCard is the originator.
            if u.startswith("FN") and ":" in line and not name:
                name = line.split(":", 1)[1].strip()
            elif u.startswith("N") and ":" in line and not name and not u.startswith("NOTE"):
                name = " ".join(p for p in line.split(":", 1)[1].split(";")[::-1] if p).strip()
            elif u.startswith("TEL") and ":" in line and not number:
                number = line.split(":", 1)[1].strip()
            continue
        if u == "BEGIN:MSG":
            in_msg = True
            continue
        if u == "END:MSG":
            in_msg = False
            continue
        if in_msg:
            body.append(line)
    return name, number, mime_text("\n".join(body).strip())


class Messages:
    def __init__(self, bus):
        self.bus = bus
        self.session = None
        self.messages = self.load()
        self.fetching = set()
        # Listing the inbox creates an object per message too: those are
        # old messages, not new ones (no "New text" for them).
        self.quiet_until = 0.0
        os.makedirs(CACHE, exist_ok=True)
        bus.add_signal_receiver(self.added, signal_name="InterfacesAdded",
                                dbus_interface="org.freedesktop.DBus.ObjectManager",
                                bus_name=OBEX)
        bus.add_signal_receiver(self.removed, signal_name="InterfacesRemoved",
                                dbus_interface="org.freedesktop.DBus.ObjectManager",
                                bus_name=OBEX)

    # ---------- store ----------
    @staticmethod
    def load():
        try:
            return json.load(open(STORE))
        except (OSError, ValueError):
            return []

    def save(self):
        os.makedirs(os.path.dirname(STORE), exist_ok=True)
        self.messages.sort(key=lambda m: m.get("time", ""), reverse=True)
        del self.messages[KEEP:]
        tmp = STORE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.messages, f, ensure_ascii=False, indent=1)
        os.replace(tmp, STORE)

    def known(self, handle):
        return any(m.get("handle") == handle for m in self.messages)

    # ---------- session ----------
    def phone_connected(self):
        try:
            sysbus = dbus.SystemBus()
            om = dbus.Interface(sysbus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
            for path, ifaces in om.GetManagedObjects().items():
                d = ifaces.get("org.bluez.Device1")
                if d and str(d.get("Address", "")).upper() == PHONE and d.get("Connected"):
                    return True
        except dbus.DBusException:
            pass
        return False

    def tick(self):
        try:
            if self.session and not self.alive():
                log("Message session closed")
                self.session = None
            if not self.session and self.phone_connected():
                self.connect()
        except Exception as e:      # never stop the timer
            log(f"Messages: {e}")
        return True

    def alive(self):
        try:
            dbus.Interface(self.bus.get_object(OBEX, self.session),
                           "org.freedesktop.DBus.Properties").Get("org.bluez.obex.Session1", "Destination")
            return True
        except dbus.DBusException:
            return False

    def connect(self):
        client = dbus.Interface(self.bus.get_object(OBEX, "/org/bluez/obex"), "org.bluez.obex.Client1")
        try:
            self.session = str(client.CreateSession(PHONE, dbus.Dictionary(
                {"Target": "map", "Source": PHONE_ADAPTER}, signature="sv")))
        except dbus.DBusException as e:
            log(f"Could not open the phone's messages: {e.get_dbus_message()} "
                "(allow message access for AuxLink-phone on the phone)")
            self.session = None
            return
        log(f"Connected to the phone's messages ({self.session})")
        self.quiet_until = time.monotonic() + 30
        try:
            mas = dbus.Interface(self.bus.get_object(OBEX, self.session), "org.bluez.obex.MessageAccess1")
            mas.SetFolder("/telecom/msg")
            listing = mas.ListMessages("inbox", dbus.Dictionary(
                {"MaxCount": dbus.UInt16(20), "SubjectLength": dbus.Byte(255)}, signature="sv"))
            new = 0
            items = listing.items() if hasattr(listing, "items") else listing
            for path, props in items:
                handle = str(path).rsplit("message", 1)[-1]
                if self.known(handle):
                    continue
                self.messages.append({
                    "handle": handle, "folder": "inbox",
                    "time": str(props.get("Timestamp", "")),
                    "sender": str(props.get("Sender", "")),
                    "number": str(props.get("SenderAddress", "")),
                    "text": str(props.get("Subject", "")),
                    "read": bool(props.get("Read", False)),
                })
                new += 1
            self.save()
            log(f"Inbox: {len(listing)} recent messages ({new} new to the Pi)")
        except dbus.DBusException as e:
            log(f"Could not list the inbox: {e.get_dbus_message()}")
        # Objects from the listing arrive as signals after this returns.
        self.quiet_until = time.monotonic() + 3

    # ---------- new messages ----------
    def added(self, path, ifaces):
        path = str(path)
        if not self.session or not path.startswith(self.session + "/message"):
            return
        if "org.bluez.obex.Message1" not in ifaces:
            return
        props = ifaces["org.bluez.obex.Message1"]
        folder = str(props.get("Folder", ""))
        handle = path.rsplit("message", 1)[-1]
        # Notification-created objects carry only folder and type; listings
        # come with a subject. Fetch the ones that are new and incoming.
        if self.known(handle) or handle in self.fetching:
            return
        if time.monotonic() < self.quiet_until:
            return                  # from the inbox listing: not a new text
        log(f"Message event: {path.rsplit('/', 1)[-1]} in '{folder}'")
        if folder and not folder.lower().rstrip("/").endswith("inbox"):
            return
        GLib.timeout_add(500, self.fetch, path, handle)

    # ---------- replies from the car ----------
    def outbox(self):
        """Send each reply auxlink-map queued, through the phone."""
        try:
            names = sorted(os.listdir(OUTBOX))
        except OSError:
            return True
        for n in names:
            if not n.endswith(".json"):
                continue
            rid = n[:-5]
            done = os.path.join(OUTBOX, rid + ".done")
            if os.path.exists(done):
                continue
            result = self.send_reply(rid)
            try:
                with open(done + ".tmp", "w") as f:
                    f.write(result)
                os.replace(done + ".tmp", done)
            except OSError as e:
                log(f"Cannot report reply {rid}: {e}")
        return True

    def send_reply(self, rid):
        try:
            meta = json.load(open(os.path.join(OUTBOX, rid + ".json")))
        except (OSError, ValueError):
            meta = {}
        if not self.session:
            return "error: the phone isn't connected"
        try:
            mas = dbus.Interface(self.bus.get_object(OBEX, self.session), "org.bluez.obex.MessageAccess1")
            mas.SetFolder("/telecom/msg")
            transfer, _ = mas.PushMessage(os.path.join(OUTBOX, rid + ".bmsg"), "outbox", dbus.Dictionary(
                {"Charset": meta.get("charset", "utf8")}, signature="sv"))
            st = "active"
            for _ in range(120):
                try:
                    st = dbus.Interface(self.bus.get_object(OBEX, transfer),
                                        "org.freedesktop.DBus.Properties").Get("org.bluez.obex.Transfer1", "Status")
                except dbus.DBusException:
                    st = "complete"     # the transfer object goes once it's done
                if st in ("complete", "error"):
                    break
                time.sleep(0.25)
            if st != "complete":
                return f"error: the phone didn't take it ({st})"
            log(f"Reply to {meta.get('to') or '?'} handed to the phone: {(meta.get('text') or '')[:60]}")
            return "ok"
        except dbus.DBusException as e:
            return f"error: {e.get_dbus_message()}"

    def removed(self, path, ifaces):
        if self.session and str(path) == self.session:
            log("Message session closed")
            self.session = None

    def fetch(self, path, handle):
        self.fetching.add(handle)
        target = os.path.join(CACHE, f"msg-{handle}.txt")
        try:
            msg = dbus.Interface(self.bus.get_object(OBEX, path), "org.bluez.obex.Message1")
            transfer, _ = msg.Get(target, False)
            for _ in range(60):
                try:
                    st = dbus.Interface(self.bus.get_object(OBEX, transfer),
                                        "org.freedesktop.DBus.Properties").Get("org.bluez.obex.Transfer1", "Status")
                except dbus.DBusException:
                    st = "complete"
                if st in ("complete", "error"):
                    break
                time.sleep(0.25)
            raw = open(target, "rb").read()
            text, how = decode_text(raw)
            if how:
                try:
                    raw.decode("utf-8")
                    at = 0
                except UnicodeDecodeError as e:
                    at = e.start
                log(f"Message {handle} wasn't plain UTF-8 ({how}); bytes there: {raw[max(0, at - 8):at + 16].hex()}")
            name, number, body = parse_bmessage(text)
            self.messages.append({"handle": handle, "folder": "inbox",
                                  "time": time.strftime("%Y%m%dT%H%M%S"),
                                  "sender": name, "number": number, "text": body, "read": False})
            self.save()
            who = name or number or "unknown"
            preview = body.replace("\n", " ")
            log(f"New text from {who}: {preview[:60]}{'…' if len(preview) > 60 else ''}")
        except (dbus.DBusException, OSError) as e:
            log(f"Could not fetch message {handle}: {e}")
        finally:
            self.fetching.discard(handle)
            try:
                os.remove(target)
            except OSError:
                pass
        return False


OBEX_DROPIN = os.path.expanduser("~/.config/systemd/user/obex.service.d/dummy-phonebook.conf")


def ensure_obex():
    """obexd-dummy: no messages server for the car (-P mas), and the
    notification server (mns) for the phone's new-message events only while
    MESSAGES=1. Restarts obex when that changes."""
    if not os.path.exists("/usr/local/libexec/obexd-dummy") or not os.path.exists(OBEX_DROPIN):
        return
    flags = "mas" if CONF.get("MESSAGES", "0") == "1" else "mas,mns"
    want = f"[Service]\nExecStart=\nExecStart=/usr/local/libexec/obexd-dummy -P {flags}\n"
    try:
        if open(OBEX_DROPIN).read() == want:
            return
        with open(OBEX_DROPIN, "w") as f:
            f.write(want)
    except OSError as e:
        log(f"Cannot update {OBEX_DROPIN}: {e}")
        return
    os.system("systemctl --user daemon-reload; systemctl --user restart obex")
    log(f"Contacts/messages server restarted with -P {flags}")
    time.sleep(3)


def main():
    ensure_obex()
    if CONF.get("MESSAGES", "0") != "1":
        log("Text messages off (Settings tab); idle")
        while True:
            time.sleep(3600)
    if not PHONE or not PHONE_ADAPTER:
        log("No phone set up yet; idle")
        while True:
            time.sleep(3600)
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    m = Messages(dbus.SessionBus())
    m.tick()
    GLib.timeout_add_seconds(30, m.tick)
    GLib.timeout_add_seconds(1, m.outbox)
    GLib.MainLoop().run()


if __name__ == "__main__":
    sys.exit(main())
