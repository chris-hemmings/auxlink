#!/usr/bin/env python3
"""Media bridge between the Tesla and the SMO.

* Registers a media player with BlueZ on the car adapter. The car shows its
  track info and sends steering-wheel buttons to it.
* Track info and play state come from the SMO app as JSON lines on the serial
  port (via the XIAO); buttons go back to the SMO as single bytes P / N / B.
* Calls: when hfp-relay reports a call ringing or active, the SMO is paused
  straight away; when the call ends it is resumed quickly - but only if it was
  this script that paused it.
* Phone media (notifications, the Oppo's own music) never pauses the SMO; it
  simply mixes into the car's audio.
* Car mic for the SMO (voice search): the XIAO sends 0x01 'M' '1' while the
  SMO has its USB mic open (0x01 'M' '0' when closed). That is passed to
  hfp-relay, which streams the car's cabin mic back here; it goes out to the
  XIAO as G.711 mu-law frames (0x01, len, data) between the key bytes.
"""
import array
import base64
import json
import socket
import os
import sys
import termios
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib


def load_conf(path="/etc/teslabridge.conf"):
    conf = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            conf[k.strip()] = v.strip().strip('"')
    return conf


CONF = load_conf()
CAR_ADAPTER = CONF.get("CAR_ADAPTER", "").upper()
# Where track info comes from and where the wheel buttons go, per source:
#   wired     - the XIAO's serial link (JSON lines in, key bytes out)
#   usbc      - the USB-C gadget's serial port (JSON in) + HID media keys out
#   bluetooth - the source's own AVRCP player (no serial at all)
MUSIC_SOURCE = CONF.get("MUSIC_SOURCE", "wired")
SOURCE = CONF.get("SOURCE", "").upper()
PORT = {"usbc": "/dev/ttyGS0", "bluetooth": None}.get(MUSIC_SOURCE, CONF.get("SERIAL_PORT", "/dev/serial0"))
HID_DEV = "/dev/hidg0"
KEY_USAGE = {b"P": 0x00CD, b"N": 0x00B5, b"B": 0x00B6, b"S": 0x00B7, b"+": 0x00E9, b"-": 0x00EA}
PATH = "/teslabridge/player"
IFACE = "org.mpris.MediaPlayer2.Player"
BLUEZ = "org.bluez"
CALL_STATE_FILE = "/run/teslabridge/call"   # written by hfp-relay
CALL_POLL_MS = 250                          # how quickly a call is noticed
RESUME_AFTER_CALL = float(CONF.get("CALL_RESUME_DELAY", "1.5"))  # s after the call ends
PAUSE_FOR_CALLS = CONF.get("PAUSE_FOR_CALLS", "1") == "1"
COVER_CURRENT = "/run/teslabridge/cover/current"  # 7-digit handle of the art to show
# "1" while the SMO plays, "0" when paused/stopped: tesla-audio runs the car's
# music stream only while this says 1, so the car sees a pause and a fresh
# start like it does with a phone. Written after the car is told the status.
PLAY_FILE = "/run/teslabridge/play"


def _present_file():
    """tesla-audio (running as the audio user) writes "1" here while sound is
    arriving from the XIAO - true for any app, including ones that never
    report a play state to the SMO app (YouTube)."""
    import pwd
    try:
        uid = pwd.getpwnam(CONF.get("AUDIO_USER", "chris")).pw_uid
    except KeyError:
        return ""
    return f"/run/user/{uid}/tb-audio-present"


PRESENT_FILE = _present_file()
MIC_REQUEST_FILE = "/run/teslabridge/mic"   # read by hfp-relay
MIC_SOCKET = "/run/teslabridge/mic.sock"    # hfp-relay sends the car's mic here
MIC_TIMEOUT = 1.5    # s without a "mic on" refresh from the XIAO = closed
OUT_LIMIT = 2048     # bytes queued for the XIAO before mic audio is dropped


def _ulaw(s):
    """16-bit linear -> G.711 mu-law (the XIAO decodes it)."""
    sign = 0x80 if s < 0 else 0
    s = min(-s if s < 0 else s, 32635) + 0x84
    exp, mask = 7, 0x4000
    while exp and not s & mask:
        exp -= 1
        mask >>= 1
    return ~(sign | exp << 4 | (s >> (exp + 3)) & 0x0F) & 0xFF


ULAW = bytes(_ulaw(i - 65536 if i > 32767 else i) for i in range(65536))


def log(msg):
    print(msg, flush=True)


def open_port():
    """The serial port, or None if this source has none or it isn't there
    yet (the USB-C gadget's port appears only once the gadget is up)."""
    if not PORT:
        return None
    try:
        fd = os.open(PORT, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    except OSError as e:
        log(f"Serial port {PORT} not available yet ({e.strerror}); will retry")
        return None
    a = termios.tcgetattr(fd)
    a[0] = 0
    a[1] = 0
    a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    a[3] = 0
    a[4] = a[5] = termios.B115200
    a[6][termios.VMIN] = 0
    a[6][termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, a)
    return fd


FD = open_port()


OUT = bytearray()     # bytes waiting for the serial port (keys + mic frames)
OUT_WATCH = None


def flush_out(*_):
    """Write what the port takes; keep the rest. Frames are queued whole, so a
    key byte can never land inside a mic frame."""
    global OUT_WATCH
    if FD is None:
        OUT.clear()
        return False
    try:
        n = os.write(FD, OUT)
        del OUT[:n]
    except BlockingIOError:
        pass
    except OSError as e:
        log(f"Serial write failed: {e}")
        OUT.clear()
    if OUT and OUT_WATCH is None:
        OUT_WATCH = GLib.io_add_watch(FD, GLib.IO_OUT, flush_out)
    if not OUT and OUT_WATCH is not None:
        GLib.source_remove(OUT_WATCH)
        OUT_WATCH = None
        return False
    return bool(OUT)


def send(data):
    OUT.extend(data)
    if OUT_WATCH is None:
        flush_out()


BT_KEYS = None     # set in main() for MUSIC_SOURCE=bluetooth


def hid_key(cmd):
    """USB-C source: press and release a media key on the gadget keyboard."""
    usage = KEY_USAGE.get(cmd)
    if usage is None:
        return

    def write(report):
        try:
            fd = os.open(HID_DEV, os.O_WRONLY | os.O_NONBLOCK)
            try:
                os.write(fd, report)
            finally:
                os.close(fd)
        except OSError as e:
            log(f"Media key failed ({HID_DEV}: {e.strerror})")
        return False
    write(usage.to_bytes(2, "little"))
    GLib.timeout_add(30, write, b"\0\0")


def smo_key(cmd, why):
    if MUSIC_SOURCE == "usbc":
        hid_key(cmd)
    elif MUSIC_SOURCE == "bluetooth":
        if BT_KEYS:
            BT_KEYS(cmd)
    else:
        send(cmd)
    log(f"Source {why} -> {cmd.decode()}")


class Player(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, PATH)
        self.title = "Screenmate"
        self.artist = "SMO"
        self.album = ""
        self.length_us = 0
        self.position_us = 0
        self.status = "Playing"
        self.track = 1
        self.ignore_smo_until = 0.0   # after we press a key, ignore stale reports briefly
        self.in_call = False
        self.paused_for_call = False
        self.resume_timer = None
        self.img_handle = ""          # cover art served by tb-cover (AVRCP 1.6)
        # What the car is told combines two things: the state the SMO app
        # reports, and whether sound is actually arriving (apps like YouTube
        # never report one, and the car mutes while it thinks we're paused).
        self.app_state = "Playing"
        self.present = False
        self.ignore_sound_until = 0.0  # after the car pauses: the tail of the sound doesn't count
        self.art_id = None            # artwork id last received from the SMO app
        self.art_seq = 0

    # ---------- what the car sees ----------
    def metadata(self):
        md = {
            "mpris:trackid": dbus.ObjectPath(f"/teslabridge/track/{self.track}"),
            "xesam:title": self.title or " ",
            "xesam:artist": dbus.Array([self.artist or " "], signature="s"),
            "xesam:album": self.album or " ",
        }
        if self.length_us > 0:
            md["mpris:length"] = dbus.Int64(self.length_us)
        if self.img_handle:
            # Only the patched bluetoothd knows this key; stock BlueZ ignores it.
            md["bluez:ImgHandle"] = self.img_handle
        return dbus.Dictionary(md, signature="sv")

    def cover_check(self):
        """Follow COVER_CURRENT: a new handle means new art, so announce a
        track change and the car fetches it."""
        try:
            h = open(COVER_CURRENT).read().strip()
        except OSError:
            h = ""
        if not (len(h) == 7 and h.isdigit()):
            h = ""
        if h != self.img_handle:
            self.img_handle = h
            log(f"Cover art: {'image ' + h if h else 'none'}")
            self.publish(track_changed=True)
        return True

    def props(self):
        return dbus.Dictionary({
            "PlaybackStatus": self.status,
            "LoopStatus": "None",
            "Rate": dbus.Double(1.0),
            "Shuffle": dbus.Boolean(False),
            "Metadata": self.metadata(),
            "Volume": dbus.Double(1.0),
            "Position": dbus.Int64(self.position_us),
            "MinimumRate": dbus.Double(1.0),
            "MaximumRate": dbus.Double(1.0),
            "CanGoNext": dbus.Boolean(True),
            "CanGoPrevious": dbus.Boolean(True),
            "CanPlay": dbus.Boolean(True),
            "CanPause": dbus.Boolean(True),
            "CanSeek": dbus.Boolean(False),
            "CanControl": dbus.Boolean(True),
        }, signature="sv")

    def publish(self, track_changed=False):
        if track_changed:
            self.track += 1  # new trackid -> BlueZ sends AVRCP TRACK_CHANGED
        self.PropertiesChanged(IFACE, dbus.Dictionary({
            "Metadata": self.metadata(),
            "PlaybackStatus": self.status,
            "Position": dbus.Int64(self.position_us),
        }, signature="sv"), [])
        self.write_play_state()

    def effective_state(self):
        sound = self.present and time.monotonic() >= self.ignore_sound_until
        return "Playing" if (self.app_state == "Playing" or sound) else self.app_state

    def sound_check(self, present):
        """Called a few times a second with whether sound is arriving."""
        changed = present != self.present
        self.present = present
        if self.in_call or time.monotonic() < self.ignore_smo_until:
            return
        want = self.effective_state()
        if want != self.status:
            self.status = want
            why = "sound arriving" if present else "sound stopped"
            log(f"[SMO] {why}: {want}")
            self.publish()
        elif changed:
            log("[SMO] sound " + ("arriving" if present else "stopped"))

    def write_play_state(self):
        want = "1" if self.status == "Playing" else "0"
        if getattr(self, "_play_written", None) == want:
            return
        try:
            os.makedirs(os.path.dirname(PLAY_FILE), exist_ok=True)
            with open(PLAY_FILE, "w") as f:
                f.write(want)
            self._play_written = want
        except OSError as e:
            log(f"Cannot write {PLAY_FILE}: {e}")

    # ---------- album art (its own JSON line from the app) ----------
    def smo_art(self, info):
        """{"art": base64 200x200 JPEG or "", "art_id": id}: store it as a new
        image handle for tb-cover; cover_check() then tells the car."""
        art_id = str(info.get("art_id") or "")
        if art_id == self.art_id:
            return
        cover_dir = os.path.dirname(COVER_CURRENT)
        old = self.img_handle
        data = info.get("art") or ""
        try:
            os.makedirs(cover_dir, exist_ok=True)
            if not data:
                if os.path.exists(COVER_CURRENT):
                    os.remove(COVER_CURRENT)
                log("Album art: none for this track")
            else:
                jpeg = base64.b64decode(data)
                if jpeg[:2] != b"\xff\xd8":
                    log("Album art from the SMO is not a JPEG; ignored")
                    return
                self.art_seq = self.art_seq % 899999 + 1
                handle = f"{1000001 + self.art_seq:07d}"   # 1000001 is the test picture
                path = os.path.join(cover_dir, handle + ".jpg")
                with open(path + ".tmp", "wb") as f:
                    f.write(jpeg)
                os.replace(path + ".tmp", path)
                with open(COVER_CURRENT + ".tmp", "w") as f:
                    f.write(handle)
                os.replace(COVER_CURRENT + ".tmp", COVER_CURRENT)
                log(f"Album art: {len(jpeg) // 1024} KB as image {handle}")
            self.art_id = art_id
        except (OSError, ValueError) as e:
            log(f"Could not store album art: {e}")
            return
        # Keep the one the car may still be fetching; drop anything older.
        for f in os.listdir(cover_dir):
            if f.endswith(".jpg") and f[:-4] not in (old, self.current_handle_file()):
                try:
                    os.remove(os.path.join(cover_dir, f))
                except OSError:
                    pass
        self.cover_check()

    @staticmethod
    def current_handle_file():
        try:
            return open(COVER_CURRENT).read().strip()
        except OSError:
            return ""

    # ---------- SMO state (JSON lines from the app) ----------
    def smo_update(self, info):
        new = (str(info.get("title") or ""), str(info.get("artist") or ""),
               str(info.get("album") or ""), int(info.get("dur") or 0) * 1000)
        track_changed = new != (self.title, self.artist, self.album, self.length_us)
        self.title, self.artist, self.album, self.length_us = new
        if "pos" in info:
            self.position_us = int(info.get("pos") or 0) * 1000
        state = info.get("state")
        status_changed = False
        if state in ("Playing", "Paused", "Stopped") and time.monotonic() >= self.ignore_smo_until:
            self.app_state = state
            want = self.effective_state()
            if want != self.status:
                status_changed = True
                self.status = want
        if track_changed or status_changed:
            self.publish(track_changed)
            log(f"[SMO] {self.title} - {self.artist} [{self.status}]")

    # ---------- playing / pausing the SMO ----------
    def set_playing(self, want_playing, why):
        """Press the SMO's play/pause key only if it is in the other state."""
        want = "Playing" if want_playing else "Paused"
        if self.status == want:
            self.publish()   # make sure the car agrees (it mutes while it thinks we're paused)
            return False
        smo_key(b"P", why)
        self.status = want
        self.app_state = want          # until the app reports otherwise
        self.ignore_smo_until = time.monotonic() + 2.5
        if not want_playing:
            # The sound lingers a few seconds in tesla-audio's "present"
            # flag: don't let it flip the car straight back to Playing.
            self.ignore_sound_until = time.monotonic() + 8
        self.publish()
        return True

    # ---------- calls ----------
    def call_state(self, busy):
        if busy == self.in_call:
            return
        self.in_call = busy
        if self.resume_timer:
            GLib.source_remove(self.resume_timer)
            self.resume_timer = None
        if busy:
            log("Call started")
            if PAUSE_FOR_CALLS and self.status == "Playing":
                self.set_playing(False, "pause (call)")
                self.paused_for_call = True
        else:
            log("Call ended")
            if self.paused_for_call:
                self.resume_timer = GLib.timeout_add(int(RESUME_AFTER_CALL * 1000), self.resume_after_call)

    def resume_after_call(self):
        self.resume_timer = None
        if not self.in_call and self.paused_for_call:
            self.paused_for_call = False
            self.set_playing(True, "resume (call ended)")
        return False

    # ---------- steering-wheel buttons ----------
    def wheel(self, what):
        if what == "play":
            self.paused_for_call = False if not self.in_call else self.paused_for_call
            self.set_playing(True, "play")
        elif what == "pause":
            if self.in_call:
                return  # the car pausing for the call; we already handle that
            self.paused_for_call = False
            self.set_playing(False, "pause")
        elif what == "toggle":
            self.set_playing(self.status != "Playing", "play/pause")
        elif what == "next":
            smo_key(b"N", "next")
        elif what == "prev":
            smo_key(b"B", "previous")

    @dbus.service.method(IFACE)
    def Play(self):
        self.wheel("play")

    @dbus.service.method(IFACE)
    def Pause(self):
        self.wheel("pause")

    @dbus.service.method(IFACE)
    def PlayPause(self):
        self.wheel("toggle")

    @dbus.service.method(IFACE)
    def Stop(self):
        self.wheel("pause")

    @dbus.service.method(IFACE)
    def Next(self):
        self.wheel("next")

    @dbus.service.method(IFACE)
    def Previous(self):
        self.wheel("prev")

    @dbus.service.method(dbus.PROPERTIES_IFACE, in_signature="ss", out_signature="v")
    def Get(self, iface, prop):
        return self.props()[prop]

    @dbus.service.method(dbus.PROPERTIES_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, iface):
        return self.props()

    @dbus.service.method(dbus.PROPERTIES_IFACE, in_signature="ssv")
    def Set(self, iface, prop, value):
        pass

    @dbus.service.signal(dbus.PROPERTIES_IFACE, signature="sa{sv}as")
    def PropertiesChanged(self, iface, changed, invalidated):
        pass


# ---------------------------------------------------------- Bluetooth source
BT_STATUS = {"playing": "Playing", "forward-seek": "Playing", "reverse-seek": "Playing",
             "paused": "Paused", "stopped": "Stopped", "error": "Stopped"}


def setup_bt_source(bus, om, player):
    """MUSIC_SOURCE=bluetooth: the source's AVRCP player (org.bluez.MediaPlayer1
    under its device object) gives track info and play state, and receives
    the wheel buttons - the same jobs the SMO app and the XIAO do when wired."""
    global BT_KEYS
    dev_part = "/dev_" + SOURCE.replace(":", "_")

    def find_player():
        for path, ifaces in om.GetManagedObjects().items():
            if dev_part in str(path) and "org.bluez.MediaPlayer1" in ifaces:
                return str(path), ifaces["org.bluez.MediaPlayer1"]
        return None, None

    def report(props):
        track = props.get("Track", {}) or {}
        player.smo_update({
            "title": str(track.get("Title", "")),
            "artist": str(track.get("Artist", "")),
            "album": str(track.get("Album", "")),
            "dur": int(track.get("Duration", 0) or 0),
            "pos": int(props.get("Position", 0) or 0),
            "state": BT_STATUS.get(str(props.get("Status", "")), "Stopped"),
        })

    def poll():
        if not SOURCE:
            return True
        try:
            path, props = find_player()
        except dbus.DBusException:
            return True
        if path:
            report(props)
        return True

    def changed(iface, changes, invalidated, path=None):
        if iface == "org.bluez.MediaPlayer1" and dev_part in str(path):
            poll()

    def keys(cmd):
        path, props = find_player()
        if not path:
            log("Bluetooth source has no media player connected; key ignored")
            return
        ctl = dbus.Interface(bus.get_object(BLUEZ, path), "org.bluez.MediaPlayer1")
        method = {b"N": "Next", b"B": "Previous", b"S": "Stop"}.get(cmd)
        if cmd == b"P":
            method = "Pause" if str(props.get("Status", "")) == "playing" else "Play"
        if method:
            try:
                getattr(ctl, method)()
            except dbus.DBusException as e:
                log(f"Bluetooth source {method} failed: {e.get_dbus_message()}")

    BT_KEYS = keys
    bus.add_signal_receiver(changed, signal_name="PropertiesChanged",
                            dbus_interface=dbus.PROPERTIES_IFACE, bus_name=BLUEZ,
                            path_keyword="path")
    poll()
    GLib.timeout_add_seconds(3, poll)
    log(f"Music source: Bluetooth ({SOURCE or 'not paired yet'})")


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    player = Player(bus)
    om = dbus.Interface(bus.get_object(BLUEZ, "/"), "org.freedesktop.DBus.ObjectManager")
    registered = set()

    def try_register(path, ifaces):
        a = ifaces.get("org.bluez.Adapter1")
        if path in registered or "org.bluez.Media1" not in ifaces or not a:
            return
        if str(a.get("Address", "")).upper() != CAR_ADAPTER:
            return
        dbus.Interface(bus.get_object(BLUEZ, path), "org.bluez.Media1").RegisterPlayer(
            dbus.ObjectPath(PATH), player.props())
        registered.add(path)
        log(f"Registered player on {path} (car adapter {CAR_ADAPTER})")

    for path, ifaces in om.GetManagedObjects().items():
        try_register(path, ifaces)

    def added(path, ifaces):
        # Media1 appears separately from Adapter1; look at the whole object.
        try_register(path, om.GetManagedObjects().get(path, {}))

    def removed(path, ifaces):
        if path in registered and ("org.bluez.Media1" in ifaces or "org.bluez.Adapter1" in ifaces):
            registered.discard(path)
            log(f"Car adapter {path} went away; will re-register when it returns")

    om.connect_to_signal("InterfacesAdded", added)
    om.connect_to_signal("InterfacesRemoved", removed)
    if not registered:
        log(f"Car adapter {CAR_ADAPTER} not present yet; waiting for it")

    buf = bytearray()
    mic = {"on": False, "seen": 0.0}

    def set_mic(on):
        if on:
            mic["seen"] = time.time()
        if on == mic["on"]:
            return
        mic["on"] = on
        log("SMO mic " + ("opened: borrowing the car's mic" if on else "closed"))
        try:
            with open(MIC_REQUEST_FILE, "w") as f:
                f.write("1" if on else "0")
        except OSError as e:
            log(f"Cannot write {MIC_REQUEST_FILE}: {e}")

    set_mic(True)   # make sure the file starts out matching...
    set_mic(False)  # ...the closed state

    def mic_watchdog():
        if mic["on"] and time.time() - mic["seen"] > MIC_TIMEOUT:
            log("No mic refresh from the XIAO")
            set_mic(False)
        return True

    GLib.timeout_add(250, mic_watchdog)

    def readable(fd, cond):
        nonlocal buf
        try:
            buf += os.read(fd, 1024)
        except BlockingIOError:
            return True
        except OSError as e:
            log(f"Serial read error: {e}")
            return True
        # Mic tokens from the XIAO can land anywhere, even inside a JSON
        # line (0x01 never appears in the text itself).
        while True:
            i = buf.find(b"\x01")
            if i < 0:
                break
            if len(buf) - i < 3:
                break                     # token still arriving
            if buf[i + 1:i + 2] == b"M":
                set_mic(buf[i + 2:i + 3] == b"1")
            del buf[i:i + 3]
        while b"\n" in buf:
            line, _, rest = bytes(buf).partition(b"\n")
            buf = bytearray(rest)
            line = line.strip()
            if not line:
                continue
            try:
                info = json.loads(line.decode("utf-8", "replace"))
                if "art" in info:
                    player.smo_art(info)
                else:
                    player.smo_update(info)
            except (ValueError, TypeError) as e:
                log(f"Bad line from SMO: {line[:80]!r} ({e})")
        if len(buf) > 131072:      # an album-art line is ~20 KB; this is far beyond
            buf = bytearray()
        return True

    def watch_port():
        global FD
        if FD is None:
            FD = open_port()
            if FD is None:
                return True               # try again shortly
        GLib.io_add_watch(FD, GLib.IO_IN | GLib.IO_HUP | GLib.IO_ERR, port_event)
        log(f"Reading track info from {PORT}")
        return False

    def port_event(fd, cond):
        global FD
        if cond & (GLib.IO_HUP | GLib.IO_ERR) and not cond & GLib.IO_IN:
            # The USB-C host went away (or the port vanished): reopen later.
            try:
                os.close(FD)
            except OSError:
                pass
            FD = None
            GLib.timeout_add_seconds(3, watch_port)
            return False
        return readable(fd, cond)

    if PORT:
        if watch_port():
            GLib.timeout_add_seconds(3, watch_port)

    if MUSIC_SOURCE == "bluetooth":
        setup_bt_source(bus, om, player)

    # The car's mic from hfp-relay (8 kHz s16le) -> mu-law frames to the XIAO.
    try:
        os.unlink(MIC_SOCKET)
    except OSError:
        pass
    msock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    msock.bind(MIC_SOCKET)
    msock.setblocking(False)

    def mic_audio(fd, cond):
        try:
            data = msock.recv(4096)
        except OSError:
            return True
        if not mic["on"] or len(OUT) > OUT_LIMIT:
            return True                   # SMO not listening, or port backed up
        pcm = array.array("h")
        pcm.frombytes(data[:len(data) & ~1])
        if sys.byteorder != "little":
            pcm.byteswap()
        u = bytes(ULAW[x & 0xFFFF] for x in pcm)
        for k in range(0, len(u), 255):
            chunk = u[k:k + 255]
            send(bytes([0x01, len(chunk)]) + chunk)
        return True

    GLib.io_add_watch(msock.fileno(), GLib.IO_IN, mic_audio)

    def poll_call():
        try:
            busy = open(CALL_STATE_FILE).read().strip() == "1"
        except OSError:
            busy = False
        player.call_state(busy)
        try:
            present = open(PRESENT_FILE).read().strip() == "1"
        except OSError:
            present = False
        player.sound_check(present)
        return True

    GLib.timeout_add(CALL_POLL_MS, poll_call)
    player.write_play_state()
    player.cover_check()
    GLib.timeout_add(1000, player.cover_check)
    GLib.MainLoop().run()


if __name__ == "__main__":
    sys.exit(main())
