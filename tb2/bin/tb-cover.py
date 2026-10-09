#!/usr/bin/env python3
"""AVRCP cover art for the car: a BIP "Cover Art" responder over OBEX/L2CAP.

The patched bluetoothd (extras/build-bluetoothd-cover.sh) advertises cover art
on PSM COVER_PSM in its AVRCP target record, and passes the current track's
image handle (set by teslabridge-keys as "bluez:ImgHandle") to the car. The
car then connects here and fetches the image for that handle:

  GET x-bt/img-thm         GetLinkedThumbnail: 200x200 JPEG (what cars show)
  GET x-bt/img-img         GetImage: the image (we only ever have the JPEG)
  GET x-bt/img-properties  GetImageProperties: XML describing it

Images live in /run/teslabridge/cover/<7-digit handle>.jpg.
"""
import os
import socket
import struct
import sys
import threading

sys.path.insert(0, "/usr/local/lib/teslabridge")
import tbconf  # noqa: E402

COVER_PSM = 0x1025
COVER_DIR = "/run/teslabridge/cover"
# BIP Cover Art target UUID (AVRCP 1.6)
COVER_ART_UUID = bytes.fromhex("7163DD544A7E11E2B47C0050C2490048")

# OBEX opcodes / response codes
CONNECT, DISCONNECT, GET, GET_FINAL, ABORT = 0x80, 0x81, 0x03, 0x83, 0xFF
CONTINUE, SUCCESS = 0x90, 0xA0
BAD_REQUEST, FORBIDDEN, NOT_FOUND, NOT_ACCEPTABLE = 0xC0, 0xC3, 0xC4, 0xC6
NOT_IMPLEMENTED, SERVICE_UNAVAILABLE = 0xD1, 0xD3
# OBEX header ids
H_TYPE, H_TARGET, H_BODY, H_EOB, H_WHO, H_CONN_ID = 0x42, 0x46, 0x48, 0x49, 0x4A, 0xCB
H_LENGTH, H_IMG_HANDLE, H_IMG_DESC = 0xC3, 0x30, 0x71

# Linux L2CAP socket options (struct l2cap_options) and ERTM mode
SOL_L2CAP, L2CAP_OPTIONS, L2CAP_MODE_ERTM = 6, 0x01, 3
OUR_MTU = 8192


def log(msg):
    print(msg, flush=True)


# ------------------------------------------------------------------ OBEX
def parse_headers(data):
    """OBEX header bytes -> {id: value} (str for unicode, bytes, or int)."""
    out, i = {}, 0
    while i < len(data):
        hi = data[i]
        kind = hi & 0xC0
        if kind in (0x00, 0x40):           # length-prefixed
            if i + 3 > len(data):
                break
            ln = struct.unpack(">H", data[i + 1:i + 3])[0]
            val = data[i + 3:i + ln]
            if kind == 0x00:
                val = val.decode("utf-16-be", "replace").rstrip("\x00")
            i += max(ln, 3)
        elif kind == 0x80:                 # 1 byte
            val = data[i + 1] if i + 1 < len(data) else 0
            i += 2
        else:                              # 4 bytes
            val = struct.unpack(">I", data[i + 1:i + 5])[0] if i + 5 <= len(data) else 0
            i += 5
        out[hi] = val
    return out


def hdr_bytes(hi, value):
    return bytes([hi]) + struct.pack(">H", len(value) + 3) + value


def hdr_u32(hi, value):
    return bytes([hi]) + struct.pack(">I", value)


def packet(code, payload=b""):
    return bytes([code]) + struct.pack(">H", len(payload) + 3) + payload


def image_path(handle):
    if not (isinstance(handle, str) and len(handle) == 7 and handle.isdigit()):
        return None
    p = os.path.join(COVER_DIR, handle + ".jpg")
    return p if os.path.isfile(p) else None


def jpeg_size(data):
    """(width, height) from a JPEG's SOF marker, or (200, 200) if not found."""
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        ln = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 2 + ln
    return 200, 200


class Session:
    """One car connection. `send`/`recv` are whole OBEX packets."""

    def __init__(self, send, recv):
        self.send, self.recv = send, recv
        self.max_pkt = 255
        self.connected = False
        self.req = {}        # headers of a GET still arriving
        self.pending = None  # (body, sent) of a GET being answered

    def run(self):
        while True:
            pkt = self.recv()
            if not pkt:
                return
            if not self.handle(pkt):
                return

    def handle(self, pkt):
        op = pkt[0]
        if op == CONNECT:
            if len(pkt) < 7:
                self.send(packet(BAD_REQUEST))
                return True
            peer_max = struct.unpack(">H", pkt[5:7])[0]
            hdrs = parse_headers(pkt[7:])
            if hdrs.get(H_TARGET) != COVER_ART_UUID:
                log("Connect without the cover-art target; refused")
                self.send(packet(SERVICE_UNAVAILABLE))
                return True
            self.max_pkt = max(255, min(peer_max, OUR_MTU))
            self.connected = True
            log(f"Car connected for cover art (packets up to {self.max_pkt} bytes)")
            self.send(self._connect_reply())
            return True
        if op == DISCONNECT:
            self.send(packet(SUCCESS))
            log("Car disconnected cover art")
            return False
        if op == ABORT:
            self.req, self.pending = {}, None
            self.send(packet(SUCCESS))
            return True
        if op in (GET, GET_FINAL):
            if not self.connected:
                self.send(packet(FORBIDDEN))
                return True
            if self.pending is not None:
                return self.continue_get()
            self.req.update(parse_headers(pkt[3:]))
            if op == GET:                      # more request headers to come
                self.send(packet(CONTINUE))
                return True
            req, self.req = self.req, {}
            return self.start_get(req)
        self.send(packet(NOT_IMPLEMENTED))
        return True

    def _connect_reply(self):
        payload = bytes([0x10, 0x00]) + struct.pack(">H", OUR_MTU)
        payload += hdr_u32(H_CONN_ID, 1) + hdr_bytes(H_WHO, COVER_ART_UUID)
        return packet(SUCCESS, payload)

    def start_get(self, req):
        typ = req.get(H_TYPE, b"")
        typ = typ.rstrip(b"\x00").decode("ascii", "replace") if isinstance(typ, bytes) else ""
        handle = req.get(H_IMG_HANDLE, "")
        path = image_path(handle)
        log(f"Car asks {typ or '?'} for image {handle or '?'}" + ("" if path else " (not here)"))
        if typ not in ("x-bt/img-thm", "x-bt/img-img", "x-bt/img-properties"):
            self.send(packet(BAD_REQUEST))
            return True
        if not path:
            self.send(packet(NOT_FOUND))
            return True
        data = open(path, "rb").read()
        if typ == "x-bt/img-properties":
            w, h = jpeg_size(data)
            data = (f'<image-properties version="1.0" handle="{handle}">\r\n'
                    f'<native encoding="JPEG" pixel="{w}*{h}" size="{len(data)}"/>\r\n'
                    f'</image-properties>\r\n').encode()
            self.pending = (data, 0, None)
        else:
            self.pending = (data, 0, len(data))
        return self.continue_get()

    def continue_get(self):
        """Send the next chunk of the answer (one per GET from the car)."""
        data, sent, length = self.pending
        extra = hdr_u32(H_LENGTH, length) if (length is not None and sent == 0) else b""
        room = self.max_pkt - 3 - len(extra) - 3
        chunk = data[sent:sent + room]
        sent += len(chunk)
        if sent >= len(data):
            self.pending = None
            self.send(packet(SUCCESS, extra + hdr_bytes(H_EOB, chunk)))
        else:
            self.pending = (data, sent, length)
            self.send(packet(CONTINUE, extra + hdr_bytes(H_BODY, chunk)))
        return True


# ------------------------------------------------------------------ socket
def ertm_listener(adapter):
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    # struct l2cap_options { u16 omtu, imtu, flush_to; u8 mode, fcs, max_tx; u16 txwin }
    try:
        raw = s.getsockopt(SOL_L2CAP, L2CAP_OPTIONS, 12)
        omtu, _imtu, flush, _mode, fcs, max_tx, txwin = struct.unpack("<HHHBBBxH", raw)
        s.setsockopt(SOL_L2CAP, L2CAP_OPTIONS,
                     struct.pack("<HHHBBBxH", omtu, OUR_MTU, flush, L2CAP_MODE_ERTM, fcs, max_tx, txwin))
    except OSError as e:
        # GOEP over L2CAP wants ERTM; carry on in basic mode rather than not at all.
        log(f"Could not select L2CAP ERTM ({e}); using basic mode")
    s.bind((adapter, COVER_PSM))
    s.listen(1)
    return s


def serve(conn, peer):
    def recv():
        # An OBEX packet can span several L2CAP frames: read until complete.
        try:
            buf = conn.recv(65535)
            while len(buf) >= 3 and len(buf) < struct.unpack(">H", buf[1:3])[0]:
                more = conn.recv(65535)
                if not more:
                    return b""
                buf += more
            return buf
        except OSError:
            return b""

    def send(data):
        conn.sendall(data)

    try:
        Session(send, recv).run()
    except OSError as e:
        log(f"Cover-art connection error: {e}")
    finally:
        conn.close()


def main():
    conf = tbconf.load()
    adapter = conf.get("CAR_ADAPTER", "").upper()
    car = conf.get("CAR", "").upper()
    if not adapter:
        log("No car adapter set up; nothing to do")
        threading.Event().wait()
    os.makedirs(COVER_DIR, exist_ok=True)
    srv = ertm_listener(adapter)
    log(f"Cover-art server on {adapter} PSM 0x{COVER_PSM:04x}")
    while True:
        conn, addr = srv.accept()
        peer = str(addr[0]).upper()
        car = tbconf.load().get("CAR", "").upper()   # a newly paired car counts
        if peer != car:
            log(f"Cover-art connection from {peer} (not the car); refused")
            conn.close()
            continue
        threading.Thread(target=serve, args=(conn, peer), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
