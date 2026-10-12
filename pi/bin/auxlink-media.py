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
  With the USB-C source the Pi's own USB sound card has the mic instead: the
  source starting to record on it is the request, and the car's mic is
  played into it. (A Bluetooth source is handled by hfp-relay alone.)
"""
import array
import base64
import glob
import json
import pwd
import socket
import re
import os
import struct
import subprocess
import threading
import sys
import termios
import time
import zlib

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib


def load_conf(path="/etc/auxlink.conf"):
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
# MUSIC_SOURCE=bluetooth: the now-playing app (if installed on the source)
# connects to this Bluetooth serial service and sends the same JSON lines it
# sends over USB - which adds album art. While it is connected its info is
# used instead of the source's own AVRCP track info.
APP_UUID = "7e5b1a20-3c4d-4f8e-9a6b-74657362726b"
APP_PATH = "/auxlink/app_link"
APP_LINK = {"sock": None, "watch": None}
AUDIO_USER = CONF.get("AUDIO_USER", "chris")
KEY_USAGE = {b"P": 0x00CD, b"N": 0x00B5, b"B": 0x00B6, b"S": 0x00B7, b"+": 0x00E9, b"-": 0x00EA,
             b">": 0x00B0, b"|": 0x00B1}   # Play, Pause (USB-C: no toggling into the wrong state)
PATH = "/auxlink/player"
IFACE = "org.mpris.MediaPlayer2.Player"
BLUEZ = "org.bluez"
CALL_STATE_FILE = "/run/auxlink/call"   # written by hfp-relay
KICK_FILE = "/run/auxlink/audio-kick"
SETUP_WIFI_FILE = "/run/auxlink/setup-wifi"   # the app's Setup button; auxlink-web turns the Wi-Fi on
# "1" while hfp-relay has the car's mic borrowed for the source's voice search
MIC_ACTIVE_FILE = "/run/auxlink/mic-active"
VOICE_GRACE = 4.0     # s after voice search in which the car's play/pause is ignored   # read by auxlink-audio (play pressed in the car)
CALL_POLL_MS = 250                          # how quickly a call is noticed
# s after the call ends; at least 3 - the car plays no music sooner than
# that after a call (auxlink-audio waits the same), so none is missed.
RESUME_AFTER_CALL = max(3.0, float(CONF.get("CALL_RESUME_DELAY", "3") or 3))
PAUSE_FOR_CALLS = CONF.get("PAUSE_FOR_CALLS", "1") == "1"
COVER_CURRENT = "/run/auxlink/cover/current"  # 7-digit handle of the art to show
# "1" while the SMO plays, "0" when paused/stopped: auxlink-audio runs the car's
# music stream only while this says 1, so the car sees a pause and a fresh
# start like it does with a phone. Written after the car is told the status.
PLAY_FILE = "/run/auxlink/play"


def _present_file():
    """auxlink-audio (running as the audio user) writes "1" here while sound is
    arriving from the XIAO - true for any app, including ones that never
    report a play state to the SMO app (YouTube)."""
    import pwd
    try:
        uid = pwd.getpwnam(CONF.get("AUDIO_USER", "chris")).pw_uid
    except KeyError:
        return ""
    return f"/run/user/{uid}/auxlink-audio-present"


PRESENT_FILE = _present_file()
# auxlink-audio touches this once the car's stream is running again after a
# call; until then a "play" is held back (see set_playing), so the music
# source doesn't play into a stream the car isn't playing yet.
STREAM_READY_FILE = os.path.join(os.path.dirname(PRESENT_FILE), "auxlink-stream-ready") if PRESENT_FILE else ""
POST_CALL_WINDOW = 30.0   # s after a call in which a play waits for the stream
PLAY_WAIT_MAX = 8.0       # s: play anyway if the stream isn't ready by then
MIC_REQUEST_FILE = "/run/auxlink/mic"   # read by hfp-relay
MIC_SOCKET = "/run/auxlink/mic.sock"    # hfp-relay sends the car's mic here
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
    if len(OUT) > 65536:      # nothing reading the port: drop rather than grow
        del OUT[:]
    OUT.extend(data)
    if OUT_WATCH is None:
        flush_out()


BT_KEYS = None     # set in main() for MUSIC_SOURCE=bluetooth

# The SMO (Android) starts each USB connection with its media volume low
# (~23%); the car's own volume is what people use. So on a new connection the
# XIAO presses "volume up" until it is at the top. SMO_VOLUME_MAX=0 turns it off.
VOLUME = {"last_line": 0.0, "last_max": 0.0}


def smo_volume_max(why):
    if CONF.get("SMO_VOLUME_MAX", "1") != "1" or MUSIC_SOURCE not in ("wired", "usbc"):
        return
    if time.time() - VOLUME["last_max"] < 60:
        return
    VOLUME["last_max"] = time.time()
    if MUSIC_SOURCE == "usbc" and FD is not None and APP_CTL.get("ver", 0) >= 2:
        # Phone Link sets the volume itself, in one go. (Key presses stop at
        # Android's "safe listening" warning, and confirming it then applies
        # only the one press it was holding back; a direct "maximum" is
        # applied in full once it's confirmed.)
        log(f"SMO volume to 100% ({why}, by Phone Link)")
        app_send({"volume": "max"})
        return
    log(f"SMO volume to 100% ({why})")
    left = [30]                     # more presses than any Android volume scale has steps

    def press():
        if MUSIC_SOURCE == "usbc":
            hid_key(b"+")
        else:
            send(b"+")
        left[0] -= 1
        return left[0] > 0
    GLib.timeout_add(100, press)


def usbc_repair():
    """Quick pause-play in the car with the USB-C source: re-plug or rebuild
    the USB-C connection if it is broken (never a working one), in the
    background; its result goes to this log."""
    def run():
        try:
            r = subprocess.run(["/usr/local/bin/auxlink-usb-gadget.sh", "repair"],
                               capture_output=True, text=True, timeout=90)
            for line in (r.stdout or "").splitlines():
                log("USB-C check: " + line.strip())
        except (OSError, subprocess.TimeoutExpired) as e:
            log(f"USB-C check failed: {e}")
    threading.Thread(target=run, daemon=True).start()


class UsbcReplug:
    """MUSIC_SOURCE=usbc: Android hands the app the Pi's USB-C side (its
    "Always" choice) only when it sees it being plugged in. Connected while
    the music device boots, the app never gets its link and no line arrives
    (the app sends at least every 5 s once it has it). So if nothing has
    arrived 15 s after the host configured the gadget, switch the gadget off
    and on: Android sees a fresh plug-in. Again after 30 s if that came too
    early in its boot; three tries per connection at most."""
    GADGET_UDC = "/sys/kernel/config/usb_gadget/auxlink/UDC"

    def __init__(self):
        self.up_since = None
        self.tries = 0

    @staticmethod
    def udc():
        try:
            return os.listdir("/sys/class/udc")[0]
        except (OSError, IndexError):
            return None

    def tick(self):
        try:
            self.check()
        except Exception as e:   # never stop the timer
            log(f"USB-C replug check failed: {e}")
        return True

    def check(self):
        name = self.udc()
        try:
            state = open(f"/sys/class/udc/{name}/state").read().strip() if name else ""
        except OSError:
            state = ""
        now = time.time()
        if state != "configured":
            if self.up_since is not None and state in ("not attached", ""):
                self.tries = 0          # unplugged: a new connection starts over
            self.up_since = None
            return
        if self.up_since is None:
            self.up_since = now
        if VOLUME["last_line"] >= self.up_since or self.tries >= 3:
            return
        wait = 15 if self.tries == 0 else 30
        if now - self.up_since < wait:
            return
        self.tries += 1
        log(f"USB-C: no word from the AuxLink app; replugging so Android gives it the link "
            f"(try {self.tries} of 3)")
        with open(self.GADGET_UDC, "w") as f:
            f.write("\n")
        time.sleep(0.8)
        with open(self.GADGET_UDC, "w") as f:
            f.write(name)
        self.up_since = None


class UsbcMic:
    """MUSIC_SOURCE=usbc: the mic on the Pi's USB sound card (gadget).

    The gadget's "Playback Rate" control reads the rate while the USB host
    (the source) is recording from it and 0 when it is not - that is the
    "SMO opened the mic" signal. The car's mic is played into the gadget's
    output through PipeWire (which owns the card), as the audio user."""

    def __init__(self):
        self.proc = None
        self.note = ""
        try:
            pw = pwd.getpwnam(AUDIO_USER)
            self.env = ["env", f"XDG_RUNTIME_DIR=/run/user/{pw.pw_uid}"]
        except KeyError:
            self.env = None

    def say(self, msg):
        if msg != self.note:
            log(msg)
            self.note = msg

    @staticmethod
    def card():
        try:
            for line in open("/proc/asound/cards"):
                if "UAC1" in line and "[" in line:
                    return line.split("[", 1)[0].strip()
        except OSError:
            pass
        return None

    def host_recording(self):
        card = self.card()
        if card is None:
            return False
        try:
            out = subprocess.run(["amixer", "-c", card, "cget", "name=Playback Rate"],
                                 capture_output=True, text=True, timeout=2).stdout
        except (OSError, subprocess.SubprocessError):
            return False
        for line in out.splitlines():
            if ": values=" in line:
                return line.split("=", 1)[1].strip() not in ("", "0")
        self.say("USB-C mic: the kernel has no 'Playback Rate' control; can't tell when the source records")
        return False

    def as_user(self, *cmd):
        return ["runuser", "-u", AUDIO_USER, "--"] + self.env + list(cmd)

    def sink(self):
        try:
            out = subprocess.run(self.as_user("pactl", "list", "sinks"),
                                 capture_output=True, text=True, timeout=5).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        name = None
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("Name: "):
                name = line[6:]
            elif line.startswith("alsa.card_name") and "UAC1" in line:
                return name
        return None

    def start(self):
        if self.proc or self.env is None:
            return
        sink = self.sink()
        if not sink:
            self.say("USB-C mic: the Pi's USB sound card output isn't in PipeWire yet")
            return
        try:
            self.proc = subprocess.Popen(
                self.as_user("pacat", "--playback", "--raw", "--format=s16le", "--rate=8000",
                             "--channels=1", f"--device={sink}", "--latency-msec=60",
                             "--client-name=auxlink", "--stream-name=car mic"),
                stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
            os.set_blocking(self.proc.stdin.fileno(), False)
            self.say(f"USB-C mic: car mic -> {sink}")
        except OSError as e:
            self.say(f"USB-C mic: cannot start pacat: {e}")
            self.proc = None

    def write(self, pcm):
        if not self.proc:
            return
        try:
            self.proc.stdin.write(pcm)
            self.proc.stdin.flush()
        except BlockingIOError:
            pass                          # behind: drop this bit rather than stall
        except (BrokenPipeError, OSError, ValueError):
            self.stop()

    def stop(self):
        if self.proc:
            try:
                self.proc.stdin.close()
            except OSError:
                pass
            self.proc.terminate()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.kill()
            self.proc = None


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


# USB-C: the AuxLink app (v1.0.24+) says "ctl" in its lines when it can
# control the player itself. Then the car's commands go to it as they are
# (play, pause, next...), like a phone's Bluetooth does; keys are the fallback.
APP_CTL = {"seen": 0.0}
APP_MEDIA = {b">": "play", b"|": "pause", b"P": "playpause", b"N": "next", b"B": "previous", b"S": "stop"}


def smo_key(cmd, why):
    if (MUSIC_SOURCE == "usbc" and cmd in APP_MEDIA and FD is not None
            and time.time() - APP_CTL["seen"] < 15):
        app_send({"media": APP_MEDIA[cmd]})
        log(f"Source {why} -> app: {APP_MEDIA[cmd]}")
        return
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
        self.status = "Paused"
        self.track = 1
        self.ignore_smo_until = 0.0   # after we press a key, ignore stale reports briefly
        self.in_call = False
        self.paused_for_call = False
        self.resume_timer = None
        self.call_ended_at = 0.0      # monotonic time the last call ended
        self.paused_at = 0.0          # monotonic time of the last pause sent to the source
        self.pending_play = 0.0       # wall time a held-back play was asked for (0 = none)
        self.voice_until = 0.0        # car's play/pause ignored until then (voice search)
        self.img_handle = ""          # cover art served by auxlink-cover (AVRCP 1.6)
        # What the car is told combines two things: the state the SMO app
        # reports, and whether sound is actually arriving (apps like YouTube
        # never report one, and the car mutes while it thinks we're paused).
        # Start from Paused: after a restart the car must not show Playing
        # until the source says so or sound actually arrives.
        self.app_state = "Paused"
        self.app_seen = (0.0, "")   # (monotonic time, state) of the app's latest report
        self.key_seq = 0            # play/pause keys sent so far
        self.present = False
        self.ignore_sound_until = 0.0  # after the car pauses: the tail of the sound doesn't count
        self.art_id = None            # artwork id last received from the SMO app
        # Image handles must not repeat across restarts: the car caches
        # pictures by handle, so a reused one shows the OLD picture (and a
        # new file under the current handle isn't even announced). Start
        # from the clock, which moves on between restarts.
        self.art_seq = int(time.time()) % 899999

    # ---------- what the car sees ----------
    def metadata(self):
        md = {
            "mpris:trackid": dbus.ObjectPath(f"/auxlink/track/{self.track}"),
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
        if MUSIC_SOURCE == "bluetooth":
            return self.app_state    # follows the source's own stream already
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

    def boot_fix(self):
        """First play after the Pi starts: the car often takes that first
        stream silently. Do what the setup page's Check and fix does (repair
        USB-C if broken, then restart the car's stream with music in it),
        once, a few seconds into the music."""
        if self.status != "Playing" or self.in_call:
            self.boot_fix_done = False      # try again on the next play
            return False

        try:
            open("/run/auxlink/boot-fix-done", "w").close()   # once per boot (/run is emptied at boot)
        except OSError:
            pass

        def run():
            log("First play since the Pi started: running Check and fix")
            if MUSIC_SOURCE == "usbc":
                try:
                    r = subprocess.run(["/usr/local/bin/auxlink-usb-gadget.sh", "repair"],
                                       capture_output=True, text=True, timeout=60)
                    for line in (r.stdout or "").splitlines()[-3:]:
                        log("  USB-C: " + line.strip())
                except (OSError, subprocess.TimeoutExpired) as e:
                    log(f"  USB-C check failed: {e}")
            try:
                pw = pwd.getpwnam(AUDIO_USER)
                r = subprocess.run(["/usr/sbin/runuser", "-u", AUDIO_USER, "--", "env",
                                    f"XDG_RUNTIME_DIR=/run/user/{pw.pw_uid}",
                                    os.path.join(pw.pw_dir, ".local/bin/audio-check.sh"), "--fix"],
                                   capture_output=True, text=True, timeout=90)
                out = re.sub(r"\x1b\[[0-9;]*m", "", (r.stdout or "") + (r.stderr or ""))
                for line in out.strip().splitlines()[-3:]:
                    log("  audio: " + line.strip())
            except (OSError, KeyError, subprocess.TimeoutExpired) as e:
                log(f"  Audio check failed: {e}")
        threading.Thread(target=run, daemon=True).start()
        return False

    def write_play_state(self):
        want = "1" if self.status == "Playing" else "0"
        if (want == "1" and not getattr(self, "boot_fix_done", False)
                and not os.path.exists("/run/auxlink/boot-fix-done")):
            self.boot_fix_done = True
            GLib.timeout_add(5000, self.boot_fix)
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
        image handle for auxlink-cover; cover_check() then tells the car."""
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
    def check_played(self, pressed, seq):
        """After a boot the music app may have no active session, so the
        app's "play" reaches nothing. If nothing is playing 3 s later (and
        nothing else was pressed since), press the USB Play key: Android
        sends that to the last music app even when it isn't running."""
        seen_at, seen = self.app_seen
        if seq == self.key_seq and not self.present and not (seen == "Playing" and seen_at > pressed):
            log("The music app didn't start from the app's play; pressing the Play key instead")
            hid_key(b">")
        return False

    def check_paused(self, pressed, seq):
        """USB-C: some players ignore a plain Pause. If the app still reports
        Playing well after it (and nothing asked to play since), use the
        play/pause toggle once."""
        seen_at, seen = self.app_seen
        if seq == self.key_seq and seen == "Playing" and seen_at > pressed + 1.0:
            log("The music device ignored Pause; sending play/pause instead")
            smo_key(b"P", "pause (toggle)")
        return False

    def smo_update(self, info):
        new = (str(info.get("title") or ""), str(info.get("artist") or ""),
               str(info.get("album") or ""), int(info.get("dur") or 0) * 1000)
        track_changed = new != (self.title, self.artist, self.album, self.length_us)
        self.title, self.artist, self.album, self.length_us = new
        if "pos" in info:
            self.position_us = int(info.get("pos") or 0) * 1000
        state = info.get("state")
        status_changed = False
        if state in ("Playing", "Paused", "Stopped"):
            self.app_seen = (time.monotonic(), state)
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
        if self.pending_play and not want_playing:
            # Paused again before the held-back play happened: the source
            # never started, so just cancel it.
            self.pending_play = 0.0
            self.status = self.app_state = "Paused"
            self.publish()
            log(f"Source {why}: cancelled the held-back play")
            return True
        if (want_playing and self.status != "Playing" and self.call_ended_at
                and time.monotonic() - self.call_ended_at < POST_CALL_WINDOW and STREAM_READY_FILE
                and MUSIC_SOURCE != "bluetooth"):
            # (Not for a Bluetooth source: it only streams while it plays, so
            # its stream can't be ready before it plays.)
            # Right after a call: tell the car "Playing" now (auxlink-audio
            # starts the stream), but play the source only once that stream
            # is really running - nothing of the song is missed.
            self.pending_play = time.time()
            self.status = self.app_state = "Playing"
            self.ignore_smo_until = time.monotonic() + PLAY_WAIT_MAX + 3
            self.publish()
            log(f"Source {why}: waiting for the car's stream before playing")
            return True
        if self.status == want and MUSIC_SOURCE != "usbc":
            self.publish()   # make sure the car agrees (it mutes while it thinks we're paused)
            return False
        # USB-C (the Pi's own media keys): a plain Play or Pause, so a source
        # already in that state stays there - and so it is always sent: if
        # this side's idea of the state is wrong, the key still does what the
        # car asked. The XIAO only has the toggle.
        self.key_seq += 1
        if MUSIC_SOURCE == "usbc":
            if time.time() - APP_CTL["seen"] < 15:
                # The app plays/pauses the player itself: exactly what was asked.
                smo_key(b">" if want_playing else b"|", why)
                if want_playing:
                    GLib.timeout_add(3000, self.check_played, time.monotonic(), self.key_seq)
            elif not want_playing and self.app_seen[1] == "Playing":
                # The SMO's player ignores a plain Pause (it only takes the
                # toggle), and the app says it's playing: the toggle pauses.
                smo_key(b"P", why)
            else:
                smo_key(b">" if want_playing else b"|", why)
            if not want_playing:
                GLib.timeout_add(3000, self.check_paused, time.monotonic(), self.key_seq)
        else:
            smo_key(b"P", why)
        if not want_playing:
            self.paused_at = time.monotonic()
        self.status = want
        self.app_state = want          # until the app reports otherwise
        self.ignore_smo_until = time.monotonic() + 2.5
        if not want_playing:
            # The sound lingers a few seconds in auxlink-audio's "present"
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
            elif time.monotonic() - self.paused_at < 3:
                # The car paused it just before the call (it does that itself):
                # still ours to resume, at once, rather than waiting for the
                # car's own "play" a few seconds after the call.
                self.paused_for_call = True
        else:
            log("Call ended")
            self.call_ended_at = time.monotonic()
            if self.paused_for_call:
                # A Bluetooth source takes a few seconds to reopen its audio
                # stream: resume it at once, so that overlaps the car's own
                # post-call settle time (auxlink-audio still starts the car's
                # stream no sooner than 3 s after the call).
                delay = 0.5 if MUSIC_SOURCE == "bluetooth" else RESUME_AFTER_CALL
                self.resume_timer = GLib.timeout_add(int(delay * 1000), self.resume_after_call)

    def check_pending_play(self):
        if not self.pending_play:
            return
        try:
            ready = os.stat(STREAM_READY_FILE).st_mtime >= self.pending_play - 0.5
        except OSError:
            ready = False
        late = time.time() - self.pending_play > PLAY_WAIT_MAX
        if ready or late:
            self.pending_play = 0.0
            smo_key(b"P", "play (car stream ready)" if ready else "play (stream not ready after 8 s)")
            self.ignore_smo_until = time.monotonic() + 2.5

    def resume_after_call(self):
        self.resume_timer = None
        if not self.in_call and self.paused_for_call:
            self.paused_for_call = False
            self.set_playing(True, "resume (call ended)")
        return False

    # ---------- steering-wheel buttons ----------
    @staticmethod
    def kick_audio():
        """Play pressed in the car: auxlink-audio restarts the car's stream once,
        so a stream the car took silently plays (what "Check and fix" does)."""
        try:
            os.makedirs(os.path.dirname(KICK_FILE), exist_ok=True)
            with open(KICK_FILE, "w") as f:
                f.write(str(time.time()))
        except OSError as e:
            log(f"Cannot write {KICK_FILE}: {e}")

    def voice_check(self):
        """Called a few times a second: is voice search using the car's mic?"""
        try:
            if open(MIC_ACTIVE_FILE).read().strip() == "1":
                self.voice_until = time.monotonic() + VOICE_GRACE
        except OSError:
            pass

    def wheel(self, what):
        if what in ("play", "pause", "toggle") and time.monotonic() < self.voice_until:
            # The car pauses and plays by itself around the "call" that voice
            # search is shown as; the source's voice app handles its own
            # music. Passing those on (a toggle for the XIAO) fought it.
            log(f"Car {what} ignored: voice search")
            return
        # Play while we already say "Playing" = the person hears nothing: have
        # the stream restarted. Play after a pause is an ordinary resume -
        # unless the pause was only seconds ago: the music's tail still
        # counts as sound, so the car's stream never stopped and play would
        # bring no fresh start.
        quick = (MUSIC_SOURCE != "bluetooth"
                 and time.monotonic() - getattr(self, "car_paused_at", -99) < 8)
        if what == "play" and (self.status == "Playing" or quick) and not self.in_call:
            if MUSIC_SOURCE == "usbc" and quick:
                usbc_repair()
            self.kick_audio()
        if what == "play":
            self.paused_for_call = False if not self.in_call else self.paused_for_call
            self.set_playing(True, "play")
        elif what == "pause":
            if self.in_call:
                return  # the car pausing for the call; we already handle that
            self.paused_for_call = False
            self.car_paused_at = time.monotonic()
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


def sdp_bip_psm(adapter, addr):
    """The L2CAP PSM of the source's cover-art (BIP) server, from its AVRCP
    target SDP record (Additional Protocol Descriptor List). None if it
    doesn't offer cover art. Raises OSError if it can't be asked."""
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    try:
        s.settimeout(5)
        s.bind((adapter, 0))
        s.connect((addr, 1))                     # SDP
        pattern = b"\x35\x03\x19\x11\x0c"        # { UUID16 AV Remote Control Target }
        attrs = b"\x35\x03\x09\x00\x0d"          # { attribute 0x000D }
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

    def parse(b, i):
        """One SDP data element at b[i] -> ((type, value), next index)."""
        t, z = b[i] >> 3, b[i] & 7
        i += 1
        if z < 5:
            n = 0 if t == 0 else (1, 2, 4, 8, 16)[z]
        else:
            k = (1, 2, 4)[z - 5]
            n = int.from_bytes(b[i:i + k], "big")
            i += k
        raw = b[i:i + n]
        if t in (6, 7):
            items, j = [], 0
            while j < n:
                v, j = parse(raw, j)
                items.append(v)
            return ("seq", items), i + n
        if t in (1, 3):
            return ("uint" if t == 1 else "uuid", int.from_bytes(raw, "big")), i + n
        return ("other", raw), i + n

    def find(node):
        kind, val = node
        if kind != "seq":
            return None
        first = val[0] if val else None
        if (first and first[0] == "seq" and len(first[1]) >= 2 and first[1][0] == ("uuid", 0x0100)
                and first[1][1][0] == "uint" and ("seq", [("uuid", 0x0008)]) in val[1:]):
            return first[1][1][1]                # (L2CAP, psm), (OBEX)
        for v in val:
            p = find(v)
            if p:
                return p
        return None

    try:
        tree, _ = parse(data, 0)
    except (IndexError, ValueError):
        return None
    return find(tree)


class BtArt:
    """MUSIC_SOURCE=bluetooth: album art straight from the source over
    Bluetooth (AVRCP 1.6 cover art), so no app is needed for it. The image
    is fetched by auxlink-bip.py (running as the audio user, where obexd is) and
    then shown in the car exactly like the app's."""

    def __init__(self, player):
        self.player = player
        self.proc = None
        self.connected = False
        self.unsupported = False    # the source has no cover art: don't keep asking
        self.next_try = 0.0
        self.pending = None         # handle being fetched
        self.done = None            # handle last shown
        self.fails = {}             # handle -> failed fetches
        self.handle = ""            # the source's current image handle
        self.track_key = None
        self.renew_at = 0.0         # last time the cover-art session was reopened
        self.note = ""
        try:
            self.uid = pwd.getpwnam(AUDIO_USER).pw_uid
        except KeyError:
            self.uid = None
        self.file = f"/run/user/{self.uid}/auxlink-bt-art.jpg"

    def say(self, msg):
        if msg != self.note:
            log(msg)
            self.note = msg

    def send(self, **msg):
        if self.proc is None:
            if self.uid is None:
                return
            try:
                self.proc = subprocess.Popen(
                    ["runuser", "-u", AUDIO_USER, "--", "env", f"XDG_RUNTIME_DIR=/run/user/{self.uid}",
                     f"DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{self.uid}/bus",
                     "python3", "/usr/local/bin/auxlink-bip.py"],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
            except OSError as e:
                self.say(f"Bluetooth album art: cannot start auxlink-bip.py: {e}")
                return
            GLib.io_add_watch(self.proc.stdout.fileno(), GLib.IO_IN | GLib.IO_HUP, self.output)
        try:
            self.proc.stdin.write(json.dumps(msg) + "\n")
            self.proc.stdin.flush()
        except OSError:
            self.reset()

    def reset(self):
        if self.proc:
            try:
                self.proc.kill()
            except OSError:
                pass
        self.proc, self.connected, self.pending = None, False, None

    def output(self, fd, cond):
        line = self.proc.stdout.readline() if self.proc else ""
        if not line:
            self.reset()
            return False
        try:
            msg = json.loads(line)
        except ValueError:
            return True
        if msg.get("connected"):
            self.connected = True
            self.say("Bluetooth album art: connected to the source's cover-art service")
        elif msg.get("got"):
            self.pending = None
            try:
                jpeg = open(msg["file"], "rb").read()
            except OSError as e:
                self.say(f"Bluetooth album art: cannot read the image: {e}")
                return True
            self.done = msg["got"]
            # The id includes the picture itself: a reused handle with new
            # art must still count as new.
            art_id = f'bt-{msg["got"]}-{zlib.crc32(jpeg):08x}'
            self.player.smo_art({"art": base64.b64encode(jpeg).decode(), "art_id": art_id})
        elif msg.get("error"):
            self.say(f"Bluetooth album art: {msg.get('cmd')} failed: {msg['error']}")
            if msg.get("cmd") == "connect":
                self.connected = False
            elif msg.get("cmd") == "get":
                # Usually the cover-art session died (the source drops it
                # around calls and voice search): reopen it and try again,
                # but give up on a handle that keeps failing.
                h = msg.get("handle")
                self.pending = None
                self.fails[h] = self.fails.get(h, 0) + 1
                if self.fails[h] >= 3:
                    self.done = h
                self.renew()
        return True

    def check_missing(self, key):
        if (key == self.track_key and not self.handle and self.connected
                and APP_LINK["sock"] is None and time.time() - self.renew_at > 20):
            self.say("Bluetooth album art: no picture for this song; reopening the cover-art session")
            self.renew()
        return False

    def renew(self):
        """Reopen the cover-art session on the next update."""
        self.connected = False
        self.next_try = time.time() + 2
        self.renew_at = time.time()

    def update(self, have_player, handle, track_key=""):
        """Called with the source's current state every few seconds and on
        changes. track_key identifies the song: some devices reuse the same
        image handle for a new song's art, so a new song refetches anyway."""
        if track_key != self.track_key:
            self.track_key = track_key
            had = self.done
            if self.done not in (None, "none"):
                self.done = None          # same handle, new song: fetch again
            if had is not None:
                # A new song that still has no picture 4 s on: the source
                # gives handles only while the cover-art session is open, so
                # it has most likely closed (after a call or voice search).
                GLib.timeout_add(4000, self.check_missing, track_key)
        self.handle = handle
        if APP_LINK["sock"] is not None:
            return                                # the app is sending art itself
        if not have_player:
            if self.connected:
                self.send(cmd="close")
            self.connected, self.unsupported, self.pending = False, False, None
            return
        if self.unsupported:
            return
        if not self.connected:
            if time.time() < self.next_try:
                return
            self.next_try = time.time() + 30
            try:
                psm = sdp_bip_psm(CONF.get("SOURCE_ADAPTER", "").upper(), SOURCE)
            except OSError as e:
                self.say(f"Bluetooth album art: can't read the source's services yet ({e})")
                return
            if not psm:
                self.unsupported = True
                self.say("Bluetooth album art: the source doesn't offer album art over Bluetooth "
                         "(the now-playing app can send it instead)")
                return
            self.send(cmd="connect", dest=SOURCE, source=CONF.get("SOURCE_ADAPTER", "").upper(), psm=psm)
            return
        if handle and handle not in (self.done, self.pending):
            self.pending = handle
            self.send(cmd="get", handle=handle, file=self.file)
        elif not handle and self.done not in (None, "none") and self.pending is None:
            self.done = "none"
            self.player.smo_art({"art": "", "art_id": "bt-none"})


def setup_bt_source(bus, om, player):
    """MUSIC_SOURCE=bluetooth: the source's AVRCP player (org.bluez.MediaPlayer1
    under its device object) gives track info and play state, and receives
    the wheel buttons - the same jobs the SMO app and the XIAO do when wired."""
    global BT_KEYS
    dev_part = "/dev_" + SOURCE.replace(":", "_")
    art = BtArt(player)

    def find_player():
        for path, ifaces in om.GetManagedObjects().items():
            if dev_part in str(path) and "org.bluez.MediaPlayer1" in ifaces:
                return str(path), ifaces["org.bluez.MediaPlayer1"]
        return None, None

    # Play state from the source's audio stream (A2DP transport): it starts
    # on play and goes idle on pause, reliably - unlike the AVRCP status,
    # which some devices (the SMO) leave on "playing" after a pause. The
    # car must see "Paused" then "Playing", or it ignores the new stream.
    stream = {"active_at": 0.0, "recheck": None,
              "avrcp": None, "avrcp_at": 0.0, "tstate": None, "t_at": 0.0}
    IDLE_GRACE = 1.0    # s of idle before "Paused" (track changes blip)

    def stream_state(avrcp_status=None):
        """Whichever changed last wins: the source's AVRCP status (instant,
        when the device keeps it up to date) or its audio stream (Android
        may hold the stream open a few s after a pause; the SMO left its
        status on "playing")."""
        state = None
        for path, ifaces in om.GetManagedObjects().items():
            t = ifaces.get("org.bluez.MediaTransport1")
            if t and dev_part in str(path):
                state = str(t.get("State", ""))
                break
        now = time.monotonic()
        if avrcp_status is not None and avrcp_status != stream["avrcp"]:
            stream["avrcp"], stream["avrcp_at"] = avrcp_status, now
        if state != stream["tstate"]:
            stream["tstate"], stream["t_at"] = state, now
        if stream["avrcp"] in ("paused", "stopped") and stream["avrcp_at"] >= stream["t_at"]:
            return "Paused"            # the device said pause after the stream last changed
        if state in ("active", "pending"):
            stream["active_at"] = now
            return "Playing"
        if now - stream["active_at"] < IDLE_GRACE:
            # Look again right when the grace ends, not at the next 3 s poll.
            if stream["recheck"] is None:
                def again():
                    stream["recheck"] = None
                    poll()
                    return False
                stream["recheck"] = GLib.timeout_add(int(IDLE_GRACE * 1000) + 100, again)
            return "Playing"
        return "Paused"

    def report(props):
        track = props.get("Track", {}) or {}
        art.update(True, str(track.get("ImgHandle", "") or ""),
                   f'{track.get("Title", "")}|{track.get("Artist", "")}|{track.get("Album", "")}')
        if APP_LINK["sock"] is not None:
            return                     # the app is sending richer info itself
        player.smo_update({
            "title": str(track.get("Title", "")),
            "artist": str(track.get("Artist", "")),
            "album": str(track.get("Album", "")),
            "dur": int(track.get("Duration", 0) or 0),
            "pos": int(props.get("Position", 0) or 0),
            "state": stream_state(str(props.get("Status", ""))),
        })

    def poll():
        # Never let one error end the polling (GLib drops a timeout whose
        # callback raises): the car's play state would freeze.
        try:
            poll_once()
        except Exception as e:      # noqa: BLE001
            log(f"Bluetooth source check failed: {e!r}")
        return True

    def poll_once():
        nonlocal dev_part
        global SOURCE
        if not SOURCE:
            # Paired after this started: pick it up without a restart.
            SOURCE = load_conf().get("SOURCE", "").upper()
            if not SOURCE:
                return True
            dev_part = "/dev_" + SOURCE.replace(":", "_")
            log(f"Music source paired: {SOURCE}")
        try:
            path, props = find_player()
        except dbus.DBusException:
            return True
        if path:
            report(props)
        else:
            art.update(False, "")
        return True

    def changed(iface, changes, invalidated, path=None):
        if iface in ("org.bluez.MediaPlayer1", "org.bluez.MediaTransport1") and dev_part in str(path):
            poll()      # (logs and swallows errors itself)

    def keys(cmd):
        path, props = find_player()
        if not path:
            log("Bluetooth source has no media player connected; key ignored")
            return
        ctl = dbus.Interface(bus.get_object(BLUEZ, path), "org.bluez.MediaPlayer1")
        method = {b"N": "Next", b"B": "Previous", b"S": "Stop"}.get(cmd)
        if cmd == b"P":
            method = "Pause" if stream_state() == "Playing" else "Play"
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


# ---------- calls and texts on the music device (the AuxLink app) ----------
CALL_INFO_FILE = "/run/auxlink/call-info.json"   # written by hfp-relay
CALL_CMD_FILE = "/run/auxlink/call-cmd"          # read by hfp-relay


def app_send(obj):
    """A line for the AuxLink app. Only links that carry data back to it:
    USB-C (its serial port) and Bluetooth. Not the XIAO: bytes to it are
    key presses."""
    line = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
    if MUSIC_SOURCE == "usbc" and FD is not None:
        send(line)
    elif APP_LINK["sock"] is not None:
        try:
            APP_LINK["sock"].sendall(line)
        except OSError as e:
            log(f"App link write failed: {e}")


class Notify:
    """Incoming calls (with the caller's name from the synced contacts) and
    new texts, passed to the app as notifications (SMO_NOTIFY=1)."""

    def __init__(self):
        try:
            home = pwd.getpwnam(AUDIO_USER).pw_dir
        except KeyError:
            home = "/nonexistent"
        self.pb = os.path.join(home, "phonebook/telecom/pb.vcf")
        self.store = os.path.join(home, ".local/share/auxlink/messages.json")
        self.names, self.pb_mtime = {}, None
        self.call_mtime = self.store_mtime = None
        self.seen = {m.get("handle") for m in self.messages()}
        self.last_call = None

    @staticmethod
    def key(number):
        d = "".join(c for c in str(number) if c.isdigit())
        return d[-9:] if len(d) >= 6 else d

    @staticmethod
    def phone_number(s):
        """A real phone number, not an app's caller ID."""
        return bool(re.fullmatch(r"\+?[\d\s()./-]{3,20}", s.strip())) and \
            len(re.sub(r"\D", "", s)) <= 15

    def name_of(self, number):
        try:
            t = os.stat(self.pb).st_mtime
        except OSError:
            return ""
        if t != self.pb_mtime:
            self.pb_mtime, self.names, fn = t, {}, ""
            try:
                for line in open(self.pb, encoding="utf-8", errors="replace"):
                    u = line.upper()
                    if u.startswith("BEGIN:VCARD"):
                        fn = ""
                    elif u.startswith("FN") and ":" in line:
                        fn = line.split(":", 1)[1].strip()
                    elif u.startswith("TEL") and ":" in line and fn:
                        self.names.setdefault(self.key(line.split(":", 1)[1]), fn)
            except OSError:
                pass
        return self.names.get(self.key(number), "")

    def messages(self):
        try:
            return json.load(open(self.store))
        except (OSError, ValueError):
            return []

    def tick(self):
        if CONF.get("SMO_NOTIFY", "1") != "1":
            return True
        try:
            self.check_call()
            self.check_texts()
        except Exception as e:   # never stop the timer
            log(f"Notify: {e}")
        return True

    def check_call(self):
        try:
            t = os.stat(CALL_INFO_FILE).st_mtime
        except OSError:
            return
        if t == self.call_mtime:
            return
        self.call_mtime = t
        try:
            info = json.load(open(CALL_INFO_FILE))
        except (OSError, ValueError):
            return
        state, number = info.get("state", "idle"), info.get("number", "")
        name = info.get("name", "")
        if (state, number, name) == self.last_call:
            return
        self.last_call = (state, number, name)
        if number and not self.phone_number(number):
            # Messenger, WhatsApp etc. calls: the phone sends the app's own
            # ID (often a long code) where the number goes. Not worth showing.
            number, name = "", name or "Internet call"
        app_send({"call": state, "number": number,
                  "name": (self.name_of(number) if number else "") or name})

    def check_texts(self):
        try:
            t = os.stat(self.store).st_mtime
        except OSError:
            return
        if t == self.store_mtime:
            return
        self.store_mtime = t
        for m in reversed(self.messages()):
            h = m.get("handle")
            if h in self.seen:
                continue
            self.seen.add(h)
            sender = m.get("sender") or self.name_of(m.get("number", "")) or m.get("number", "")
            app_send({"text": {"from": sender, "number": m.get("number", ""), "body": m.get("text", "")}})


class PhoneLink:
    """The app's Phone and Messages screens (USB-C), using the phone through
    this Pi: contacts and recent calls (synced from the phone by pbap-sync),
    texts (auxlink-messages), dialing (hfp-relay) and sending texts (queued
    in the outbox auxlink-messages sends from, like the car's replies)."""
    OUTBOX = "/run/auxlink/outbox"
    SENT = "/var/lib/auxlink/app-sent.json"
    CHUNK = 6000          # bytes of items per line (the serial link and its buffer)

    def __init__(self):
        try:
            home = pwd.getpwnam(AUDIO_USER).pw_dir
        except KeyError:
            home = "/nonexistent"
        self.book = os.path.join(home, "phonebook/telecom")
        self.store = os.path.join(home, ".local/share/auxlink/messages.json")
        self.queue = []          # lines waiting to go to the app, paced
        self.pending = {}        # outbox id -> app's id for each text being sent
        GLib.timeout_add(100, self.pump)
        GLib.timeout_add(1000, self.check_sent)

    # ---- requests from the app ----
    def handle(self, info):
        try:
            if info.get("req") == "phone":
                self.send_list("contacts", self.contacts())
                self.send_list("favourites", self.contacts("fav.vcf"))
                self.send_list("recents", self.recents())
            elif info.get("req") == "messages":
                self.send_messages()
            elif "dial" in info:
                self.dial(str(info["dial"]))
            elif "sms" in info:
                self.sms(info["sms"])
        except Exception as e:      # never break the app link
            log(f"Phone screen request failed: {e}")

    def dial(self, number):
        number = re.sub(r"[^0-9+*#]", "", number)
        if not number:
            return
        log(f"App: call {number}")
        try:
            os.makedirs(os.path.dirname(CALL_CMD_FILE), exist_ok=True)
            with open(CALL_CMD_FILE, "w") as f:
                f.write("dial " + number)
        except OSError as e:
            log(f"Cannot write {CALL_CMD_FILE}: {e}")

    def sms(self, m):
        to = re.sub(r"[^0-9+*#]", "", str(m.get("to") or ""))
        text = str(m.get("text") or "").strip()
        app_id = str(m.get("id") or "")
        if not to or not text:
            app_send({"sms_result": {"id": app_id, "ok": False, "error": "no number or text"}})
            return
        if CONF.get("MESSAGES", "0") != "1":
            app_send({"sms_result": {"id": app_id, "ok": False,
                                     "error": "Text messages are off in the Pi's settings"}})
            return
        rid = "A%X" % int(time.time() * 1000)
        body = self.bmessage(to, text)
        try:
            os.makedirs(self.OUTBOX, exist_ok=True)
            with open(os.path.join(self.OUTBOX, rid + ".bmsg"), "wb") as f:
                f.write(body)
            with open(os.path.join(self.OUTBOX, rid + ".json.tmp"), "w") as f:
                json.dump({"charset": "utf8", "to": to, "text": text, "from": "app"}, f)
            os.replace(os.path.join(self.OUTBOX, rid + ".json.tmp"), os.path.join(self.OUTBOX, rid + ".json"))
        except OSError as e:
            app_send({"sms_result": {"id": app_id, "ok": False, "error": str(e)}})
            return
        self.pending[rid] = app_id
        self.remember({"id": app_id, "to": to, "text": text,
                       "time": time.strftime("%Y%m%dT%H%M%S"), "ts": time.time(), "status": "sending"})
        log(f"App: text to {to}: {text[:60]}")

    @staticmethod
    def bmessage(to, text):
        """An outgoing text in MAP's bMessage format (the phone sends it)."""
        msg = "BEGIN:MSG\r\n" + text.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\nEND:MSG\r\n"
        return ("BEGIN:BMSG\r\nVERSION:1.0\r\nSTATUS:READ\r\nTYPE:SMS_GSM\r\n"
                "FOLDER:TELECOM/MSG/OUTBOX\r\n"
                "BEGIN:VCARD\r\nVERSION:2.1\r\nN:\r\nTEL:\r\nEND:VCARD\r\n"
                "BEGIN:BENV\r\n"
                f"BEGIN:VCARD\r\nVERSION:2.1\r\nN:\r\nTEL:{to}\r\nEND:VCARD\r\n"
                "BEGIN:BBODY\r\nCHARSET:UTF-8\r\n"
                f"LENGTH:{len(msg.encode())}\r\n{msg}"
                "END:BBODY\r\nEND:BENV\r\nEND:BMSG\r\n").encode()

    def check_sent(self):
        """Result of each text the app sent (auxlink-messages writes .done)."""
        for rid, app_id in list(self.pending.items()):
            done = os.path.join(self.OUTBOX, rid + ".done")
            try:
                result = open(done).read().strip() or "error"
            except OSError:
                continue
            ok = result == "ok"
            err = "" if ok else result.replace("error: ", "", 1)
            app_send({"sms_result": {"id": app_id, "ok": ok, "error": err}})
            self.remember({"id": app_id, "status": "sent" if ok else "failed", "error": err})
            log(f"App text {'sent' if ok else 'NOT sent: ' + err}")
            for ext in (".bmsg", ".json", ".done"):
                try:
                    os.remove(os.path.join(self.OUTBOX, rid + ext))
                except OSError:
                    pass
            del self.pending[rid]
        return True

    def remember(self, item):
        """Texts sent from the app, so conversations show both sides."""
        try:
            sent = json.load(open(self.SENT))
        except (OSError, ValueError):
            sent = []
        for old in sent:
            if old.get("id") == item["id"]:
                old.update(item)
                break
        else:
            sent.append(item)
        sent = sent[-100:]
        try:
            os.makedirs(os.path.dirname(self.SENT), exist_ok=True)
            with open(self.SENT + ".tmp", "w") as f:
                json.dump(sent, f, ensure_ascii=False)
            os.replace(self.SENT + ".tmp", self.SENT)
        except OSError as e:
            log(f"Cannot save sent texts: {e}")

    # ---- data for the app ----
    @staticmethod
    def vcards(path):
        """vCards from a PBAP file as lists of (name;params, value) lines."""
        try:
            raw = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            return []
        raw = re.sub(r"\r?\n[ \t]", "", raw)                 # unfold
        raw = re.sub(r"=\r?\n", "", raw)                      # quoted-printable soft breaks
        cards, cur = [], None
        for line in raw.splitlines():
            u = line.upper()
            if u.startswith("BEGIN:VCARD"):
                cur = []
            elif u.startswith("END:VCARD"):
                if cur is not None:
                    cards.append(cur)
                cur = None
            elif cur is not None and ":" in line and not u.startswith("PHOTO"):
                k, v = line.split(":", 1)
                if "QUOTED-PRINTABLE" in k.upper():
                    import quopri
                    v = quopri.decodestring(v.encode()).decode("utf-8", "replace")
                cur.append((k, v.strip()))
        return cards

    @staticmethod
    def card_name(card):
        for k, v in card:
            if k.upper().split(";")[0] == "FN" and v:
                return v
        for k, v in card:
            if k.upper().split(";")[0] == "N" and v.strip(";"):
                return " ".join(p for p in v.split(";")[1::-1] if p).strip()
        return ""

    def contacts(self, book="pb.vcf"):
        out = []
        for card in self.vcards(os.path.join(self.book, book)):
            name = self.card_name(card)
            tels = []
            for k, v in card:
                ku = k.upper()
                if ku.split(";")[0] == "TEL" and v:
                    kind = ("mobile" if "CELL" in ku else "home" if "HOME" in ku
                            else "work" if "WORK" in ku else "")
                    if [kind, v] not in tels:
                        tels.append([kind, v])
            if tels and name:
                out.append({"n": name, "t": tels})
        out.sort(key=lambda c: c["n"].lower())
        return out

    def recents(self):
        out = []
        for card in self.vcards(os.path.join(self.book, "cch.vcf")):
            num = kind = when = ""
            for k, v in card:
                ku = k.upper()
                if ku.split(";")[0] == "TEL" and not num:
                    num = v
                elif ku.startswith("X-IRMC-CALL-DATETIME"):
                    when = v
                    kind = ("missed" if "MISSED" in ku else "in" if "RECEIVED" in ku
                            else "out" if "DIALED" in ku else "")
            if num or self.card_name(card):
                out.append({"n": self.card_name(card), "num": num, "k": kind, "at": when})
        return out[:60]

    def send_messages(self):
        try:
            inbox = json.load(open(self.store))
        except (OSError, ValueError):
            inbox = []
        try:
            sent = json.load(open(self.SENT))
        except (OSError, ValueError):
            sent = []
        def ts(m):
            # The moment (seconds since 1970): what the app shows in its own
            # time zone. Older records only have the Pi's local time.
            if m.get("ts"):
                return m["ts"]
            try:
                return time.mktime(time.strptime(m.get("time", "")[:15], "%Y%m%dT%H%M%S"))
            except ValueError:
                return 0
        try:      # your sent texts from the phone's Sent folder (auxlink-messages)
            phone_sent = json.load(open(self.store.replace("messages.json", "messages-sent.json")))
        except (OSError, ValueError):
            phone_sent = []

        def key(num, text):
            d = re.sub(r"\D", "", num or "")
            return (d[-9:], (text or "").strip())
        items = [{"in": 1, "who": m.get("sender") or "", "num": m.get("number") or "",
                  "text": m.get("text") or "", "at": m.get("time") or "", "ts": ts(m)} for m in inbox]
        items += [{"in": 0, "num": m.get("number") or "", "text": m.get("text") or "",
                   "at": m.get("time") or "", "ts": ts(m)} for m in phone_sent]
        on_phone = {key(m.get("number"), m.get("text")) for m in phone_sent}
        # Sent from Phone Link: shown until the phone's Sent folder has them
        # (and always when they failed, with why).
        items += [{"in": 0, "num": m.get("to") or "", "text": m.get("text") or "",
                   "at": m.get("time") or "", "ts": ts(m), "st": m.get("status") or ""} for m in sent
                  if m.get("status") != "sent" or key(m.get("to"), m.get("text")) not in on_phone]
        self.send_list("messages", items, {"enabled": CONF.get("MESSAGES", "0") == "1"})

    def send_list(self, kind, items, extra=None):
        """Big lists go as several lines ({kind: [...], part, parts})."""
        chunks, cur, size = [], [], 0
        for it in items:
            n = len(json.dumps(it, ensure_ascii=False).encode())
            if cur and size + n > self.CHUNK:
                chunks.append(cur)
                cur, size = [], 0
            cur.append(it)
            size += n
        chunks.append(cur)
        for i, c in enumerate(chunks):
            line = {kind: c, "part": i, "parts": len(chunks)}
            if extra:
                line.update(extra)
            self.queue.append(line)

    def pump(self):
        # Pace big lists so the serial buffer (which drops past 64 KB) keeps up.
        while self.queue and len(OUT) < 16384:
            app_send(self.queue.pop(0))
        return True


PHONE = {"link": None}


def set_clock(c):
    """The music device's time zone and clock (from its network or GPS):
    the Pi has no clock of its own and usually no internet in the car, and
    it starts in UTC. Message times (for the car too) and logs then match."""
    def run():
        tz = str(c.get("tz") or "")
        if re.fullmatch(r"[A-Za-z]+(/[A-Za-z0-9_+-]+)+", tz) and os.path.exists("/usr/share/zoneinfo/" + tz):
            try:
                cur = os.path.realpath("/etc/localtime").split("/zoneinfo/", 1)[-1]
            except OSError:
                cur = ""
            if cur != tz:
                r = subprocess.run(["timedatectl", "set-timezone", tz], capture_output=True, text=True)
                if r.returncode == 0:
                    log(f"Time zone set to {tz} (from the music device)")
                    os.environ.pop("TZ", None)
                    time.tzset()
                    # The texts service stamps times in local time: restart it
                    # so new texts use the new zone (it's not the car's service).
                    subprocess.run(["/usr/sbin/runuser", "-u", AUDIO_USER, "--", "env",
                                    f"XDG_RUNTIME_DIR=/run/user/{pwd.getpwnam(AUDIO_USER).pw_uid}",
                                    "systemctl", "--user", "restart", "auxlink-messages"],
                                   capture_output=True)
        try:
            now = float(c.get("now") or 0) / 1000
        except (TypeError, ValueError):
            now = 0
        if now > 1.7e9:
            synced = subprocess.run(["timedatectl", "show", "-p", "NTPSynchronized", "--value"],
                                    capture_output=True, text=True).stdout.strip() == "yes"
            if not synced and abs(now - time.time()) > 120:
                if subprocess.run(["date", "-s", "@%d" % int(now)], capture_output=True).returncode == 0:
                    log("Clock set from the music device (no internet time here)")
    threading.Thread(target=run, daemon=True).start()


def web_api(path, body=None, timeout=10):
    """The setup page's own API (pairing, forget, connect, check): the app's
    Bluetooth and audio controls use the same code as the page."""
    import urllib.request
    import urllib.error
    req = urllib.request.Request("http://127.0.0.1/api/" + path,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    pw = load_conf().get("WEB_PASSWORD", "")
    if pw:
        req.add_header("Authorization", "Basic " + base64.b64encode(f"app:{pw}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return {"error": json.loads(e.read()).get("error") or str(e)}
        except ValueError:
            return {"error": str(e)}
    except (OSError, ValueError) as e:
        return {"error": f"setup page not reachable: {e}"}


def load_conf():
    conf = {}
    try:
        for line in open("/etc/auxlink.conf"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                conf[k.strip()] = v.strip().strip('"')
    except OSError:
        pass
    return conf


def bt_status():
    """Car / phone / music source and the pairing window, for the app."""
    st = web_api("state", timeout=8)
    if "error" in st:
        return {"error": st["error"]}
    conf = st.get("conf", {})
    out = {"window": st.get("window"), "source_kind": conf.get("MUSIC_SOURCE", "")}
    for role in ("car", "phone", "source"):
        d = st.get(role) or {}
        out[role] = {"name": d.get("name", ""), "address": d.get("address", ""),
                     "paired": bool(d.get("paired")), "connected": bool(d.get("connected"))}
    return out


def audio_flow(player):
    """The music's path right now, as INPUT / PROCESSING / OUTPUT steps, each
    ok / warn / bad / idle with a detail line (like the setup page's check,
    but from what is known live)."""
    def item(sec, title, detail, st):
        return {"sec": sec, "title": title, "detail": detail, "st": st}
    out = []
    playing = player.status == "Playing"
    # ---- INPUT: the music device ----
    app_ok = time.time() - VOLUME["last_line"] < 15
    track = " - ".join(x for x in (player.title, player.artist) if x and x not in ("Screenmate", "SMO"))
    out.append(item("input", "Music device (Phone Link)",
                    ("Connected" if app_ok else "No word from the app") + (f"  ·  {track}" if track else ""),
                    "ok" if app_ok else "warn"))
    if MUSIC_SOURCE == "usbc":
        state = ""
        for u in glob.glob("/sys/class/udc/*/state"):
            try:
                state = open(u).read().strip()
            except OSError:
                pass
        try:
            card = "UAC1" in open("/proc/asound/cards").read().replace("_", "")
        except OSError:
            card = False
        out.append(item("input", "USB-C to the Pi",
                        ("Connected" if state == "configured" else (state or "no USB-C port")) +
                        ("  ·  48 kHz 16-bit stereo sound card" if card else "  ·  sound card missing"),
                        "ok" if state == "configured" and card else "bad"))
    sound = player.present
    out.append(item("input", "Sound from the music device",
                    "Arriving" if sound else ("Silent (paused)" if not playing else "Silent, but it should be playing"),
                    "ok" if sound else ("idle" if not playing else "bad")))
    # ---- PROCESSING: the Pi ----
    try:
        loop = subprocess.run(["pgrep", "-f", "pw-loopback.*to_tesla"], capture_output=True).returncode == 0
    except OSError:
        loop = False
    out.append(item("processing", "Pi audio link",
                    "Streaming to the car" if loop else ("Stopped (music paused)" if not playing else "Not running"),
                    "ok" if loop else ("idle" if not playing else "bad")))
    # ---- OUTPUT: Bluetooth to the car ----
    car = CONF.get("CAR", "").upper()
    connected, tstate, codec = False, "", ""
    try:
        om = dbus.Interface(dbus.SystemBus().get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
        dev = car.replace(":", "_")
        for path, ifaces in om.GetManagedObjects().items():
            if path.endswith("dev_" + dev) and "org.bluez.Device1" in ifaces:
                connected = connected or bool(ifaces["org.bluez.Device1"].get("Connected"))
            t = ifaces.get("org.bluez.MediaTransport1")
            if t and ("dev_" + dev) in path:
                tstate = str(t.get("State", ""))
                codec = {0: "SBC", 1: "MP3", 2: "AAC", 255: "aptX"}.get(int(t.get("Codec", 0)), "")
    except dbus.DBusException:
        pass
    spec = ""
    try:
        pw = pwd.getpwnam(AUDIO_USER)
        r = subprocess.run(["/usr/sbin/runuser", "-u", AUDIO_USER, "--", "env",
                            f"XDG_RUNTIME_DIR=/run/user/{pw.pw_uid}", "pactl", "list", "sinks"],
                           capture_output=True, text=True, timeout=5)
        block = ""
        for b in r.stdout.split("Sink #"):
            if "bluez_output." + car.replace(":", "_") in b:
                block = b
        m = re.search(r"Sample Specification: \S+ (\d+)ch (\d+)Hz", block)
        if m:
            spec = f"{int(m.group(2)) // 1000} kHz {'stereo' if m.group(1) == '2' else m.group(1) + ' ch'}"
        m = re.search(r'api\.bluez5\.codec = "([^"]+)"', block)
        if m:
            codec = m.group(1).upper().replace("_", "-")
    except (OSError, KeyError, subprocess.TimeoutExpired):
        pass
    out.append(item("output", "Bluetooth to the car",
                    "Not connected" if not connected else "  ·  ".join(x for x in (
                        {"active": "Streaming", "pending": "Starting", "idle": "Idle"}.get(tstate, "Connected"),
                        codec, spec) if x),
                    "bad" if not connected else ("ok" if tstate == "active" else ("idle" if not playing else "warn"))))
    # Playing with sound, or paused and quiet, is consistent; anything else is worth a look.
    out.append(item("output", "Car is told", player.status,
                    "ok" if playing == bool(sound) else "warn"))
    return out


def pi_status():
    """The Pi's network and basics, for the app's AuxLink tab."""
    def nm(*args):
        try:
            return subprocess.run(["nmcli", *args], capture_output=True, text=True, timeout=3).stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            return ""
    st = {"wifi": "off", "ssid": "", "signal": None, "ip": "", "setup_wifi": False, "eth_ip": ""}
    for line in nm("-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device").splitlines():
        parts = line.split(":")
        if len(parts) < 4:
            continue
        dev, kind, state, con = parts[0], parts[1], parts[2], ":".join(parts[3:])
        if dev == "wlan0":
            if con == "auxlink-setup" and state == "connected":
                st["setup_wifi"] = True
                st["wifi"] = "setup"
                st["ip"] = "10.42.0.1"
            elif state == "connected":
                st["wifi"] = "connected"
                st["ssid"] = nm("-g", "802-11-wireless.ssid", "connection", "show", con) or con
                st["ip"] = nm("-g", "IP4.ADDRESS", "device", "show", "wlan0").split("|")[0].split("/")[0]
                for w in nm("-t", "-f", "ACTIVE,SIGNAL", "device", "wifi").splitlines():
                    if w.startswith("yes:"):
                        try:
                            st["signal"] = int(w.split(":")[1])
                        except ValueError:
                            pass
            elif state.startswith("connecting"):
                st["wifi"] = "connecting"
            elif state in ("disconnected", "unavailable"):
                st["wifi"] = "disconnected" if state == "disconnected" else "off"
        elif kind == "ethernet" and state == "connected":
            st["eth_ip"] = nm("-g", "IP4.ADDRESS", "device", "show", dev).split("|")[0].split("/")[0]
    try:
        st["version"] = open("/usr/local/share/auxlink/VERSION").read().strip()
    except OSError:
        st["version"] = ""
    try:
        st["uptime"] = int(float(open("/proc/uptime").read().split()[0]))
    except (OSError, ValueError):
        st["uptime"] = 0
    st["host"] = socket.gethostname()
    return st


def app_command(player, cmd):
    """A button in the AuxLink app (over USB via the XIAO, or Bluetooth)."""
    if cmd == "ping":
        return                      # the app saying it is connected (nothing playing)
    if cmd in ("answer", "decline"):
        log(f"App: {cmd} the call")
        try:
            os.makedirs(os.path.dirname(CALL_CMD_FILE), exist_ok=True)
            with open(CALL_CMD_FILE, "w") as f:
                f.write(cmd)
        except OSError as e:
            log(f"Cannot write {CALL_CMD_FILE}: {e}")
        return
    if cmd == "fix":
        log("App: fix sound - restarting the car's stream")
        player.kick_audio()
    elif cmd == "reboot":
        log("App: restart the Pi")
        try:
            with open("/run/auxlink/events.log", "a") as f:
                f.write(time.strftime("%H:%M:%S ") + "Restarting the Pi (from the Phone Link app)\n")
        except OSError:
            pass
        # A moment's delay so the app's command finishes cleanly first.
        subprocess.Popen(["sh", "-c", "sleep 2; systemctl reboot"], start_new_session=True)
    elif cmd == "setup":
        log("App: turn on the setup Wi-Fi")
        try:
            os.makedirs(os.path.dirname(SETUP_WIFI_FILE), exist_ok=True)
            with open(SETUP_WIFI_FILE, "w") as f:
                f.write(str(time.time()))
        except OSError as e:
            log(f"Cannot write {SETUP_WIFI_FILE}: {e}")
    else:
        log(f"App: unknown command {cmd!r}")


class AppLink(dbus.service.Object):
    """Bluetooth serial service for the now-playing app (Bluetooth source)."""

    def __init__(self, bus, on_data):
        super().__init__(bus, APP_PATH)
        self.on_data = on_data

    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        pass

    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        addr = str(device).rsplit("dev_", 1)[-1].replace("_", ":").upper()
        sock = socket.socket(fileno=fd.take())
        if addr != SOURCE:
            log(f"Now-playing app link from {addr} refused (not the music source)")
            sock.close()
            return
        self.drop()
        sock.setblocking(False)
        APP_LINK["sock"] = sock
        APP_LINK["watch"] = GLib.io_add_watch(sock.fileno(), GLib.IO_IN | GLib.IO_HUP | GLib.IO_ERR,
                                              self.event)
        log("Now-playing app connected over Bluetooth (track info + album art)")

    def event(self, fd, cond):
        data = b""
        if cond & GLib.IO_IN:
            try:
                data = APP_LINK["sock"].recv(4096)
            except BlockingIOError:
                return True
            except OSError:
                data = b""
        if not data:
            APP_LINK["watch"] = None
            self.drop()
            log("Now-playing app disconnected; using the source's own track info")
            return False
        self.on_data(data)
        return True

    @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
    def RequestDisconnection(self, device):
        self.drop()

    @staticmethod
    def drop():
        if APP_LINK["watch"]:
            GLib.source_remove(APP_LINK["watch"])
        if APP_LINK["sock"] is not None:
            try:
                APP_LINK["sock"].close()
            except OSError:
                pass
        APP_LINK["sock"] = APP_LINK["watch"] = None


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
    usbc_mic = UsbcMic() if MUSIC_SOURCE == "usbc" else None

    def set_mic(on):
        if on:
            mic["seen"] = time.time()
        if on == mic["on"]:
            return
        mic["on"] = on
        log("SMO mic " + ("opened: borrowing the car's mic" if on else "closed"))
        if usbc_mic:
            usbc_mic.start() if on else usbc_mic.stop()
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

    if MUSIC_SOURCE == "usbc":
        GLib.timeout_add(1000, UsbcReplug().tick)

    if usbc_mic:
        def usbc_mic_poll():
            if usbc_mic.host_recording():
                set_mic(True)            # also the refresh the watchdog wants
            elif mic["on"]:
                set_mic(False)
            return True
        GLib.timeout_add(300, usbc_mic_poll)

    def readable(fd, cond):
        try:
            data = os.read(fd, 1024)
        except BlockingIOError:
            return True
        except OSError as e:
            log(f"Serial read error: {e}")
            return True
        return feed(data)

    def feed(data):
        nonlocal buf
        buf += data
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
                # The app sends a line at least every 5 s while connected: the
                # first after a long gap means the SMO has (re)connected.
                now = time.time()
                if info.get("ctl"):
                    APP_CTL["seen"] = now
                    try:
                        APP_CTL["ver"] = int(info["ctl"])
                    except (TypeError, ValueError):
                        pass
                if now - VOLUME["last_line"] > 20:
                    smo_volume_max("the SMO connected")
                VOLUME["last_line"] = now
                if "clock" in info:
                    set_clock(info["clock"])
                elif info.get("req") == "pi":
                    app_send({"pi": pi_status()})

                    def more():
                        bt, flow = bt_status(), audio_flow(player)
                        GLib.idle_add(lambda: (app_send({"bt": bt}), app_send({"flow": flow}), False)[-1])
                    threading.Thread(target=more, daemon=True).start()
                elif "bt_do" in info:
                    def do(a=info["bt_do"]):
                        action = str(a.get("action", ""))
                        if action not in ("pair", "pair/cancel", "forget", "connect"):
                            return
                        r = web_api(action, {"role": a.get("role", ""), "seconds": 120}, timeout=15)
                        log(f"App: Bluetooth {action} {a.get('role', '')}: {r.get('error') or 'ok'}")
                        bt = bt_status()
                        GLib.idle_add(lambda: (app_send({"bt_result": {"action": action, "error": r.get("error", "")}}),
                                               app_send({"bt": bt}), False)[-1])
                    threading.Thread(target=do, daemon=True).start()
                elif info.get("req") == "check":
                    def chk(fix=bool(info.get("fix"))):
                        r = web_api("check", {"fix": fix}, timeout=150)
                        text = r.get("output") or r.get("error") or ""
                        GLib.idle_add(lambda: (app_send({"check": {"fix": fix, "output": text}}), False)[-1])
                    threading.Thread(target=chk, daemon=True).start()
                elif PHONE["link"] and ("req" in info or "dial" in info or "sms" in info):
                    PHONE["link"].handle(info)
                elif "cmd" in info:
                    app_command(player, str(info["cmd"]))
                elif "art" in info:
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
        app_link = AppLink(bus, feed)
        try:
            dbus.Interface(bus.get_object(BLUEZ, "/org/bluez"), "org.bluez.ProfileManager1") \
                .RegisterProfile(APP_PATH, APP_UUID, dbus.Dictionary({
                    "Name": "auxlink now playing",
                    "Role": "server",
                    "RequireAuthentication": dbus.Boolean(True),
                    "RequireAuthorization": dbus.Boolean(False),
                }, signature="sv"))
            log("Now-playing app service registered (Bluetooth)")
        except dbus.DBusException as e:
            log(f"Cannot register the now-playing app service: {e.get_dbus_message()}")

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
        if usbc_mic:
            if mic["on"]:
                if usbc_mic.proc is None:
                    usbc_mic.start()
                usbc_mic.write(data[:len(data) & ~1])
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
        player.check_pending_play()
        player.voice_check()
        return True

    GLib.timeout_add(CALL_POLL_MS, poll_call)
    player.write_play_state()
    player.cover_check()
    GLib.timeout_add(1000, player.cover_check)
    GLib.timeout_add(500, Notify().tick)
    if MUSIC_SOURCE == "usbc":
        PHONE["link"] = PhoneLink()
    GLib.MainLoop().run()


if __name__ == "__main__":
    sys.exit(main())
