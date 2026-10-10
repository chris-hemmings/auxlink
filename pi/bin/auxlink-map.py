#!/usr/bin/env python3
"""Text messages in the car (MESSAGES=1), phase 2: a MAP Message Access
Server for the car.

auxlink-messages (as the audio user) collects the phone's texts into
~/.local/share/auxlink/messages.json. This serves them to the car over
Bluetooth MAP, the way a phone would:
  * the car connects (OBEX, MAS target) and browses telecom/msg/{inbox,...},
  * lists messages (x-bt/MAP-msg-listing) and reads them (x-bt/message,
    bMessage), and marks them read (x-bt/messageStatus),
  * registers for notifications (x-bt/MAP-NotificationRegistration): then we
    connect to the car's own Message Notification Server and send it a
    NewMessage event report for each new text, so it pops up in the car.
Sending texts from the car is not supported yet (phase 3).

Only the paired car is served. Off unless MESSAGES=1 (Settings tab): without
it, no messages service is offered at all - an empty one made a car hang on
"Connecting..." and reset its Bluetooth.
"""
import html
import json
import os
import pwd
import socket
import struct
import sys
import threading
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

sys.path.insert(0, "/usr/local/lib/auxlink")
import auxconf  # noqa: E402

CONF = auxconf.load()
CAR = CONF.get("CAR", "").upper()
CAR_ADAPTER = CONF.get("CAR_ADAPTER", "").upper()
USER = CONF.get("AUDIO_USER", "chris")
try:
    STORE = os.path.join(pwd.getpwnam(USER).pw_dir, ".local/share/auxlink/messages.json")
except KeyError:
    STORE = ""
READ_STATE = "/var/lib/auxlink/map-read.json"   # handles the car marked read

MAS_UUID16 = "00001132-0000-1000-8000-00805f9b34fb"
MAS_TARGET = bytes.fromhex("bb582b40420c11dbb0de0800200c9a66")
MNS_TARGET = bytes.fromhex("bb582b41420c11dbb0de0800200c9a66")
PROFILE_PATH = "/auxlink/map"
FOLDERS = {"": ["telecom"], "telecom": ["msg"],
           "telecom/msg": ["inbox", "outbox", "sent", "deleted", "draft"]}

# OBEX
OK, CONTINUE = 0xA0, 0x90
BAD_REQUEST, FORBIDDEN, NOT_FOUND, NOT_IMPLEMENTED = 0xC0, 0xC3, 0xC4, 0xD1
H_NAME, H_TYPE, H_LENGTH, H_TARGET = 0x01, 0x42, 0xC3, 0x46
H_BODY, H_EOB, H_WHO, H_CONNID, H_APP = 0x48, 0x49, 0x4A, 0xCB, 0x4C
# MAP application parameters
AP_MAXLISTCOUNT, AP_STARTOFFSET, AP_FILTER_READ = 0x01, 0x02, 0x06
AP_PARAMMASK, AP_SUBJECTLEN, AP_NEWMESSAGE, AP_NOTIFSTATUS = 0x10, 0x13, 0x0D, 0x0E
AP_MASINSTANCE, AP_LISTINGSIZE, AP_STATUSIND, AP_STATUSVAL, AP_MSETIME = 0x0F, 0x12, 0x17, 0x18, 0x19
AP_CHARSET = 0x14


def log(msg):
    print(msg, flush=True)


def event(msg):
    log(msg)
    try:
        auxconf.event(msg)
    except OSError:
        pass


# ---------- OBEX encoding ----------
def hdr(hid, value):
    kind = hid & 0xC0
    if kind == 0x00:     # unicode text, null terminated, UTF-16BE
        data = (value + "\0").encode("utf-16-be") if value is not None else b""
        return bytes([hid]) + struct.pack(">H", 3 + len(data)) + data
    if kind == 0x40:     # byte sequence
        return bytes([hid]) + struct.pack(">H", 3 + len(value)) + value
    if kind == 0x80:
        return bytes([hid, value])
    return bytes([hid]) + struct.pack(">I", value)


def parse_headers(data):
    out, i = {}, 0
    while i < len(data):
        hid = data[i]
        kind = hid & 0xC0
        if kind in (0x00, 0x40):
            n = struct.unpack(">H", data[i + 1:i + 3])[0]
            raw = data[i + 3:i + n]
            if kind == 0x00:
                val = raw.decode("utf-16-be", "replace").rstrip("\0")
            else:
                val = raw
            i += n
        elif kind == 0x80:
            val, i = data[i + 1], i + 2
        else:
            val, i = struct.unpack(">I", data[i + 1:i + 5])[0], i + 5
        if hid in (H_BODY, H_EOB) and hid in out:
            out[hid] += val
        else:
            out[hid] = val
    return out


def app_params(raw):
    out, i = {}, 0
    while i + 2 <= len(raw):
        tag, n = raw[i], raw[i + 1]
        out[tag] = raw[i + 2:i + 2 + n]
        i += 2 + n
    return out


def ap(tag, value):
    return bytes([tag, len(value)]) + value


def recv_packet(sock, buf):
    while len(buf) < 3:
        d = sock.recv(65536)
        if not d:
            raise ConnectionError("closed")
        buf += d
    n = struct.unpack(">H", buf[1:3])[0]
    while len(buf) < n:
        d = sock.recv(65536)
        if not d:
            raise ConnectionError("closed")
        buf += d
    return bytes(buf[:n]), buf[n:]


def packet(code, body=b"", extra=b""):
    return bytes([code]) + struct.pack(">H", 3 + len(extra) + len(body)) + extra + body


# ---------- messages ----------
_read_lock = threading.Lock()


def load_read():
    try:
        return set(json.load(open(READ_STATE)))
    except (OSError, ValueError):
        return set()


def save_read(handles):
    with _read_lock:
        os.makedirs(os.path.dirname(READ_STATE), exist_ok=True)
        with open(READ_STATE + ".tmp", "w") as f:
            json.dump(sorted(handles)[-500:], f)
        os.replace(READ_STATE + ".tmp", READ_STATE)


def map_handle(handle):
    """The phone's handle (decimal, from obexd) as a MAP handle (hex)."""
    try:
        return "%X" % int(handle)
    except (TypeError, ValueError):
        return str(handle)


def load_messages():
    try:
        msgs = json.load(open(STORE))
    except (OSError, ValueError):
        return []
    read = load_read()
    for m in msgs:
        m["map"] = map_handle(m.get("handle"))
        if m["map"] in read:
            m["read"] = True
    msgs.sort(key=lambda m: m.get("time", ""), reverse=True)
    return msgs


def mse_time():
    return time.strftime("%Y%m%dT%H%M%S%z")


def dt(value):
    v = "".join(c for c in str(value) if c.isdigit() or c == "T")
    return v[:15] if len(v) >= 15 else time.strftime("%Y%m%dT%H%M%S")


def listing_xml(msgs, subject_len):
    rows = []
    for m in msgs:
        text = (m.get("text") or "").replace("\n", " ")
        subj = text[:subject_len] if subject_len else text[:256]
        e = lambda s: html.escape(str(s or ""), quote=True)  # noqa: E731
        rows.append(
            f'<msg handle="{e(m["map"])}" subject="{e(subj)}" datetime="{dt(m.get("time"))}" '
            f'sender_name="{e(m.get("sender"))}" sender_addressing="{e(m.get("number"))}" '
            f'recipient_addressing="" type="SMS_GSM" size="{len(text.encode())}" text="yes" '
            f'reception_status="complete" attachment_size="0" priority="no" '
            f'read="{"yes" if m.get("read") else "no"}" sent="no" protected="no"/>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<MAP-msg-listing version="1.0">\n'
            + "\n".join(rows) + "\n</MAP-msg-listing>\n").encode()


def folder_xml(names):
    rows = "".join(f'<folder name="{n}"/>' for n in names)
    return ('<?xml version="1.0"?>\n<!DOCTYPE folder-listing SYSTEM "obex-folder-listing.dtd">\n'
            f'<folder-listing version="1.0">{rows}</folder-listing>\n').encode()


def bmessage(m):
    name = (m.get("sender") or m.get("number") or "").replace("\r", " ").replace("\n", " ")
    text = (m.get("text") or "").replace("\r\n", "\n").replace("\n", "\r\n")
    msg = f"BEGIN:MSG\r\n{text}\r\nEND:MSG\r\n"
    return ("BEGIN:BMSG\r\nVERSION:1.0\r\n"
            f"STATUS:{'READ' if m.get('read') else 'UNREAD'}\r\nTYPE:SMS_GSM\r\nFOLDER:TELECOM/MSG/INBOX\r\n"
            f"BEGIN:VCARD\r\nVERSION:2.1\r\nN:{name}\r\nFN:{name}\r\nTEL:{m.get('number') or ''}\r\nEND:VCARD\r\n"
            "BEGIN:BENV\r\nBEGIN:BBODY\r\nCHARSET:UTF-8\r\n"
            f"LENGTH:{len(msg.encode())}\r\n{msg}"
            "END:BBODY\r\nEND:BENV\r\nEND:BMSG\r\n").encode()


# ---------- the car's notification server (MNS) ----------
def sdp_rfcomm_channel(adapter, addr, uuid16):
    """RFCOMM channel of a service on the device, from its SDP record."""
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    try:
        s.settimeout(5)
        s.bind((adapter, 0))
        s.connect((addr, 1))
        pattern = b"\x35\x03\x19" + struct.pack(">H", uuid16)
        attrs = b"\x35\x03\x09\x00\x04"         # Protocol Descriptor List
        data, cont, tid = b"", b"\x00", 1
        while True:
            params = pattern + b"\xff\xff" + attrs + cont
            s.send(struct.pack(">BHH", 0x06, tid, len(params)) + params)
            r = s.recv(4096)
            if len(r) < 7 or r[0] != 0x07:
                return None
            n = struct.unpack(">H", r[5:7])[0]
            data += r[7:7 + n]
            cont = r[7 + n:]
            if not cont or cont[0] == 0:
                break
            cont, tid = cont[:1 + cont[0]], tid + 1
    finally:
        s.close()
    # The channel follows the RFCOMM UUID (0x0003) as a uint8.
    for i in range(len(data) - 4):
        if data[i:i + 3] == b"\x19\x00\x03" and data[i + 3] == 0x08:
            return data[i + 4]
    return None


class Mns:
    def __init__(self):
        self.sock = None
        self.connid = None
        self.lock = threading.Lock()

    def connect(self):
        ch = sdp_rfcomm_channel(CAR_ADAPTER, CAR, 0x1133)
        if not ch:
            raise OSError("the car offers no message notification service")
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.settimeout(15)
        s.bind((CAR_ADAPTER, 0))
        s.connect((CAR, ch))
        s.send(packet(0x80, hdr(H_TARGET, MNS_TARGET), struct.pack(">BBH", 0x10, 0, 4096)))
        resp, _ = recv_packet(s, bytearray())
        if resp[0] != OK:
            s.close()
            raise OSError(f"the car refused the notification connection (0x{resp[0]:02X})")
        self.connid = parse_headers(resp[7:]).get(H_CONNID)
        self.sock = s
        log(f"Connected to the car's message notification service (channel {ch})")

    def close(self):
        with self.lock:
            if self.sock:
                try:
                    self.sock.send(packet(0x81, hdr(H_CONNID, self.connid) if self.connid is not None else b""))
                except OSError:
                    pass
                self.sock.close()
            self.sock = None

    def new_message(self, handle):
        return self.send_event("NewMessage", handle, "TELECOM/MSG/INBOX")

    def send_event(self, kind, handle, folder):
        body = ('<MAP-event-report version="1.0">\n'
                f'<event type="{kind}" handle="{handle}" folder="{folder}" msg_type="SMS_GSM"/>\n'
                '</MAP-event-report>\n').encode()
        with self.lock:
            for attempt in (1, 2):
                try:
                    if not self.sock:
                        self.connect()
                    h = (hdr(H_CONNID, self.connid) if self.connid is not None else b"") + \
                        hdr(H_TYPE, b"x-bt/MAP-event-report\0") + hdr(H_APP, ap(AP_MASINSTANCE, b"\x00")) + \
                        hdr(H_EOB, body)
                    self.sock.send(packet(0x82, h))
                    resp, _ = recv_packet(self.sock, bytearray())
                    return resp[0] == OK
                except (OSError, ConnectionError) as e:
                    log(f"Notification to the car failed ({e}); {'retrying' if attempt == 1 else 'giving up'}")
                    try:
                        self.sock and self.sock.close()
                    except OSError:
                        pass
                    self.sock = None
        return False


MNS = Mns()
NOTIFY = {"on": False}

# Replies from the car go to the phone through auxlink-messages (it holds
# the Bluetooth session to the phone, on the audio user's session bus):
#   <id>.bmsg + <id>.json written here, <id>.done ("ok" / "error: ...") there.
OUTBOX = "/run/auxlink/outbox"


def bmsg_summary(body):
    """(recipient number, text) of a pushed bMessage, for the log."""
    text = body.decode("utf-8", "replace")
    number, lines, in_env, in_msg = "", [], False, False
    for line in text.replace("\r", "").split("\n"):
        u = line.upper()
        if u == "BEGIN:BENV":
            in_env = True
        elif in_env and u.startswith("TEL") and ":" in line and not number:
            number = line.split(":", 1)[1].strip()
        elif u == "BEGIN:MSG":
            in_msg = True
        elif u == "END:MSG":
            in_msg = False
        elif in_msg:
            lines.append(line)
    return number, " ".join(lines).strip()


def queue_reply(body, charset):
    os.makedirs(OUTBOX, exist_ok=True)
    rid = "%X" % (0x2000000000000 + int(time.time() * 1000) % 0xFFFFFFFFFFFF)
    number, text = bmsg_summary(body)
    with open(os.path.join(OUTBOX, rid + ".bmsg"), "wb") as f:
        f.write(body)
    with open(os.path.join(OUTBOX, rid + ".json.tmp"), "w") as f:
        json.dump({"charset": charset, "to": number, "text": text}, f)
    os.replace(os.path.join(OUTBOX, rid + ".json.tmp"), os.path.join(OUTBOX, rid + ".json"))
    log(f"Car replied to {number or '?'}: {text[:60]} (sending through the phone)")
    return rid


def watch_outbox():
    """Report each reply's result to the car and the setup page."""
    while True:
        time.sleep(1)
        try:
            names = os.listdir(OUTBOX)
        except OSError:
            continue
        now = time.time()
        for n in names:
            rid, ext = os.path.splitext(n)
            p = os.path.join(OUTBOX, n)
            if ext == ".json":
                try:
                    if now - os.stat(p).st_mtime > 120 and not os.path.exists(os.path.join(OUTBOX, rid + ".done")):
                        open(os.path.join(OUTBOX, rid + ".done"), "w").write("error: the phone's messages weren't reachable")
                except OSError:
                    pass
                continue
            if ext != ".done":
                continue
            result, meta = "error", {}
            try:
                result = open(p).read().strip() or "error"
                meta = json.load(open(os.path.join(OUTBOX, rid + ".json")))
            except (OSError, ValueError):
                pass
            ok = result == "ok"
            who = meta.get("to") or "?"
            event(f"Reply to {who}: {'sent' if ok else 'NOT sent (' + result + ')'}")
            if NOTIFY["on"]:
                MNS.send_event("SendingSuccess" if ok else "SendingFailure", rid,
                               "TELECOM/MSG/SENT" if ok else "TELECOM/MSG/OUTBOX")
            for ext2 in (".bmsg", ".json", ".done"):
                try:
                    os.remove(os.path.join(OUTBOX, rid + ext2))
                except OSError:
                    pass


def watch_store():
    """Tell the car (if it asked) about each text that arrives."""
    seen = {m["map"] for m in load_messages()}
    last = 0.0
    while True:
        time.sleep(2)
        try:
            mtime = os.stat(STORE).st_mtime
        except OSError:
            continue
        if mtime == last:
            continue
        last = mtime
        msgs = load_messages()
        for m in reversed(msgs):
            if m["map"] in seen:
                continue
            seen.add(m["map"])
            if NOTIFY["on"]:
                ok = MNS.new_message(m["map"])
                log(f"Told the car about a new text ({m['map']}): {'ok' if ok else 'failed'}")


# ---------- the server side (the car connects here) ----------
class Session(threading.Thread):
    def __init__(self, sock, addr):
        super().__init__(daemon=True)
        self.sock, self.addr = sock, addr
        self.path = []                # current folder
        self.maxpkt = 1024
        self.connid = 1

    def send(self, code, headers=b"", extra=b""):
        self.sock.send(packet(code, headers, extra))

    def run(self):
        buf = bytearray()
        log(f"Car opened messages ({self.addr})")
        try:
            while True:
                pkt, buf = recv_packet(self.sock, buf)
                op = pkt[0]
                if op == 0x80:                                   # CONNECT
                    self.maxpkt = max(255, min(struct.unpack(">H", pkt[5:7])[0], 32767))
                    h = parse_headers(pkt[7:])
                    if h.get(H_TARGET) != MAS_TARGET:
                        self.send(NOT_FOUND, extra=struct.pack(">BBH", 0x10, 0, 32767))
                        continue
                    self.send(OK, hdr(H_CONNID, self.connid) + hdr(H_WHO, MAS_TARGET),
                              struct.pack(">BBH", 0x10, 0, 32767))
                elif op == 0x81:                                 # DISCONNECT
                    self.send(OK)
                    break
                elif op == 0x85:                                 # SETPATH
                    self.setpath(pkt[3], parse_headers(pkt[5:]))
                elif op in (0x03, 0x83):                         # GET
                    buf = self.get(pkt, buf)
                elif op in (0x02, 0x82):                         # PUT
                    buf = self.put(pkt, buf)
                elif op == 0xFF:                                 # ABORT
                    self.send(OK)
                else:
                    self.send(NOT_IMPLEMENTED)
        except (OSError, ConnectionError, struct.error) as e:
            log(f"Car messages connection ended ({e})")
        finally:
            self.sock.close()
            NOTIFY["on"] = False         # it registers again when it reconnects
            MNS.close()
            log("Car closed messages")

    def setpath(self, flags, h):
        name = h.get(H_NAME)
        if flags & 0x01:                       # up one level
            if self.path:
                self.path.pop()
            return self.send(OK)
        if not name:                           # root
            self.path = []
            return self.send(OK)
        here = "/".join(self.path)
        if name.lower() in FOLDERS.get(here, []):
            self.path.append(name.lower())
            return self.send(OK)
        self.send(NOT_FOUND)

    def collect(self, first, buf):
        """A request may come in several packets: gather its headers until
        the final one (answering Continue to the others)."""
        pkt, headers = first, b""
        while True:
            headers += pkt[3:]
            if pkt[0] & 0x80:
                return parse_headers(headers), buf
            self.send(CONTINUE)
            pkt, buf = recv_packet(self.sock, buf)

    def respond(self, buf, body, params=b""):
        """Send a GET response, in as many packets as the car's size allows."""
        first = hdr(H_APP, params) if params else b""
        room = self.maxpkt - 3 - 3 - len(first)
        while len(body) > room:
            chunk, body = body[:room], body[room:]
            self.send(CONTINUE, first + hdr(H_BODY, chunk))
            first, room = b"", self.maxpkt - 6
            pkt, buf = recv_packet(self.sock, buf)
            if pkt[0] == 0xFF:
                self.send(OK)
                return buf
        self.send(OK, first + hdr(H_EOB, body))
        return buf

    def get(self, pkt, buf):
        h, buf = self.collect(pkt, buf)
        typ = h.get(H_TYPE, b"").rstrip(b"\0").decode("ascii", "replace")
        params = app_params(h.get(H_APP, b""))
        here = "/".join(self.path)
        if typ == "x-obex/folder-listing":
            return self.respond(buf, folder_xml(FOLDERS.get(here, [])))
        if typ == "x-bt/MAP-msg-listing":
            folder = (h.get(H_NAME) or "").lower().strip("/")
            target = f"{here}/{folder}".strip("/") if folder else here
            msgs = load_messages() if target.endswith("inbox") else []
            rs = params.get(AP_FILTER_READ, b"\x00")[0:1]
            if rs == b"\x01":
                msgs = [m for m in msgs if not m.get("read")]
            elif rs == b"\x02":
                msgs = [m for m in msgs if m.get("read")]
            total = len(msgs)
            count = struct.unpack(">H", params[AP_MAXLISTCOUNT])[0] if AP_MAXLISTCOUNT in params else 1024
            start = struct.unpack(">H", params[AP_STARTOFFSET])[0] if AP_STARTOFFSET in params else 0
            slen = params[AP_SUBJECTLEN][0] if AP_SUBJECTLEN in params else 0
            unread = any(not m.get("read") for m in msgs)
            out = ap(AP_NEWMESSAGE, b"\x01" if unread else b"\x00") + \
                ap(AP_MSETIME, mse_time().encode()) + ap(AP_LISTINGSIZE, struct.pack(">H", total))
            if count == 0:
                self.send(OK, hdr(H_APP, out))
                return buf
            log(f"Car listed {target or '/'}: {min(count, max(0, total - start))} of {total}")
            return self.respond(buf, listing_xml(msgs[start:start + count], slen), out)
        if typ == "x-bt/message":
            handle = (h.get(H_NAME) or "").upper()
            m = next((m for m in load_messages() if m["map"].upper() == handle), None)
            if not m:
                self.send(NOT_FOUND)
                return buf
            log(f"Car read text {handle} from {m.get('sender') or m.get('number')}")
            return self.respond(buf, bmessage(m))
        if typ == "x-bt/MASInstanceInformation":
            return self.respond(buf, b"AuxLink", ap(AP_MASINSTANCE, b"\x00"))
        log(f"Car asked for unsupported '{typ}'")
        self.send(NOT_IMPLEMENTED)
        return buf

    def put(self, pkt, buf):
        h, buf = self.collect(pkt, buf)
        typ = h.get(H_TYPE, b"").rstrip(b"\0").decode("ascii", "replace")
        params = app_params(h.get(H_APP, b""))
        if typ == "x-bt/MAP-NotificationRegistration":
            on = params.get(AP_NOTIFSTATUS, b"\x00")[:1] == b"\x01"
            NOTIFY["on"] = on
            self.send(OK)
            event("Car registered for new-text notifications" if on else "Car stopped new-text notifications")
            if on:
                threading.Thread(target=self.mns_up, daemon=True).start()
            else:
                MNS.close()
            return buf
        if typ == "x-bt/messageStatus":
            handle = (h.get(H_NAME) or "").upper()
            ind = params.get(AP_STATUSIND, b"\x00")[:1]
            val = params.get(AP_STATUSVAL, b"\x00")[:1]
            if ind == b"\x00":                 # read status
                read = load_read()
                (read.add if val == b"\x01" else read.discard)(handle)
                save_read(read)
            self.send(OK)
            return buf
        if typ == "x-bt/MAP-messageUpdate":
            self.send(OK)
            return buf
        if typ == "x-bt/message":
            body = h.get(H_BODY, b"") + h.get(H_EOB, b"")
            folder = (h.get(H_NAME) or "").lower().strip("/")
            target = f"{'/'.join(self.path)}/{folder}".strip("/") if folder else "/".join(self.path)
            if not target.endswith("outbox"):
                log(f"Car tried to put a message in '{target}': only the outbox sends")
                self.send(FORBIDDEN)
                return buf
            charset = params.get(AP_CHARSET, b"\x01")[:1]
            handle = queue_reply(body, "utf8" if charset == b"\x01" else "native")
            self.send(OK, hdr(H_NAME, handle))
            return buf
        log(f"Car sent unsupported '{typ}'")
        self.send(NOT_IMPLEMENTED)
        return buf

    @staticmethod
    def mns_up():
        time.sleep(1)
        with MNS.lock:
            if MNS.sock:
                return
            try:
                MNS.connect()
            except (OSError, ConnectionError) as e:
                log(f"Could not reach the car's notification service yet: {e}")


class Profile(dbus.service.Object):
    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        pass

    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        addr = str(device).rsplit("dev_", 1)[-1].replace("_", ":").upper()
        sock = socket.socket(fileno=fd.take())   # RFCOMM or L2CAP, as the car chose
        if addr != CAR:
            log(f"Messages connection from {addr} refused (not the car)")
            sock.close()
            return
        sock.setblocking(True)
        Session(sock, addr).start()

    @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
    def RequestDisconnection(self, device):
        pass


def main():
    if CONF.get("MESSAGES", "0") != "1":
        log("Text messages off (Settings tab): no messages service for the car")
        while True:
            time.sleep(3600)
    if not CAR or not CAR_ADAPTER or not STORE:
        log("No car paired yet; idle")
        while True:
            time.sleep(3600)
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    Profile(bus, PROFILE_PATH)
    dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1").RegisterProfile(
        PROFILE_PATH, MAS_UUID16, dbus.Dictionary({
            "Name": "AuxLink messages",
            "Role": "server",
            "RequireAuthentication": dbus.Boolean(True),
            "RequireAuthorization": dbus.Boolean(False),
        }, signature="sv"))
    log("Messages service for the car registered")
    threading.Thread(target=watch_store, daemon=True).start()
    os.makedirs(OUTBOX, exist_ok=True)
    os.chmod(OUTBOX, 0o1777)        # auxlink-messages (the audio user) writes results here
    threading.Thread(target=watch_outbox, daemon=True).start()
    GLib.MainLoop().run()


if __name__ == "__main__":
    sys.exit(main())
