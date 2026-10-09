#!/usr/bin/env python3
"""Hands-free relay: Tesla <-> Pi <-> Oppo.

The Pi is a car kit (HFP Hands-Free) to the Oppo through the phone dongle, and
a phone (HFP Audio Gateway) to the Tesla through the car dongle.

  Control:  the service-level connection (SLC) is set up separately on each
            side, using the Oppo's own features and indicator list for the
            car. After that, AT commands from the car are passed to the Oppo
            and the Oppo's responses and events (RING, +CLIP, +CIEV...) are
            passed back, with indicator numbers mapped by name.
  Audio:    codec negotiation is switched off on both sides, so both calls
            use narrowband CVSD and the SCO audio packets can be copied
            byte-for-byte between the two links in both directions.

Requires PipeWire's own HFP/HSP roles to be disabled, so this script owns HFP.
Every AT line is logged with its direction for debugging.
"""
import os
import re
import socket
import subprocess
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
CAR = CONF.get("CAR", "").upper()
CAR_ADAPTER = CONF.get("CAR_ADAPTER", "").upper()
PHONE = CONF.get("PHONE", "").upper()
PHONE_ADAPTER = CONF.get("PHONE_ADAPTER", "").upper()
PHONE_ENABLED = CONF.get("PHONE_ENABLED", "1") == "1" and bool(PHONE) and bool(PHONE_ADAPTER)


def reload_devices():
    """Pick up a newly paired car/phone without restarting (a restart would
    drop the very connection the new device is trying to make)."""
    global CONF, CAR, PHONE, PHONE_ENABLED
    CONF = load_conf()
    CAR = CONF.get("CAR", "").upper()
    PHONE = CONF.get("PHONE", "").upper()
    PHONE_ENABLED = (CONF.get("PHONE_ENABLED", "1") == "1" and bool(PHONE)
                     and CONF.get("PHONE_ADAPTER", "").upper() == PHONE_ADAPTER and bool(PHONE_ADAPTER))

HFP_HF_UUID = "0000111e-0000-1000-8000-00805f9b34fb"
HFP_AG_UUID = "0000111f-0000-1000-8000-00805f9b34fb"
BLUEZ = "org.bluez"

# What we tell the Oppo we are (HF features, AT+BRSF): 3-way, CLI,
# voice recognition, remote volume, enhanced call status and control.
# Bit 7 (codec negotiation) and bit 8 (HF indicators) deliberately off.
HF_FEATURES = (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 6)
# AG features to strip before passing the Oppo's to the car:
# bit 9 codec negotiation, bit 10 HF indicators, bit 11 eSCO S4.
AG_STRIP = (1 << 9) | (1 << 10) | (1 << 11)
# Used for the car only until the Oppo has told us its own.
DEFAULT_AG_FEATURES = 1 | 4 | 8 | 32 | 64 | 128 | 256
DEFAULT_CIND = ('("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),'
                '("signal",(0-5)),("roam",(0,1)),("battchg",(0-5)),("callheld",(0-2))')
DEFAULT_CHLD = "(0,1,2,3)"

# After a call the car often leaves its music stream "playing" but silent.
# Pausing and resuming the stream (AVDTP suspend/start) wakes it up again.
AUDIO_USER = CONF.get("AUDIO_USER", "chris")
RESUME_MUSIC_DELAY = 1  # seconds after the call ends
CALL_STATE_FILE = "/run/teslabridge/call"  # "1" during a call, "0" otherwise
CAR_RE = CAR.replace(":", "[:_]")
RESUME_MUSIC_CMD = (
    f'S=$(pactl list sinks short | grep -E "bluez_output\\.{CAR_RE}" | cut -f1); '
    '[ -n "$S" ] && pactl suspend-sink "$S" 1 && sleep 0.5 && pactl suspend-sink "$S" 0'
)

# Car commands we can safely answer "OK" to while no phone is bridged.
OK_WHEN_ALONE = ("AT+CLIP", "AT+CCWA", "AT+CMEE", "AT+NREC", "AT+VGS", "AT+VGM",
                 "AT+BIA", "AT+COPS=", "AT+XAPL", "AT+IPHONEACCEV", "AT+BTRH?",
                 "AT+CSRSF", "AT+CSR", "AT+XEVENT", "AT+APLSIRI")


def log(msg):
    print(msg, flush=True)


def names_of(cind_list):
    return re.findall(r'\("([^"]+)"', cind_list)


def addr_of(dev_path):
    return dev_path.rsplit("dev_", 1)[-1].replace("_", ":").upper()


class Link:
    """One RFCOMM connection with line framing."""

    def __init__(self, name, sock, on_line, on_close):
        self.name = name
        self.sock = sock
        self.buf = b""
        self.on_line = on_line
        self.on_close = on_close
        sock.setblocking(False)
        self.watch = GLib.io_add_watch(sock.fileno(), GLib.IO_IN | GLib.IO_HUP | GLib.IO_ERR, self._ready)

    def _ready(self, fd, cond):
        if cond & (GLib.IO_HUP | GLib.IO_ERR):
            self.close()
            return False
        try:
            data = self.sock.recv(1024)
        except BlockingIOError:
            return True
        except OSError:
            self.close()
            return False
        if not data:
            self.close()
            return False
        self.buf += data
        # Lines end in \r (commands) or \r\n (responses).
        while True:
            m = re.search(rb"[\r\n]", self.buf)
            if not m:
                break
            line, self.buf = self.buf[:m.start()], self.buf[m.end():]
            line = line.decode("utf-8", "replace").strip()
            if line:
                self.on_line(line)
        return True

    def send(self, raw):
        try:
            self.sock.sendall(raw.encode())
        except OSError as e:
            log(f"{self.name} send failed: {e}")

    def close(self):
        if self.sock is None:
            return
        GLib.source_remove(self.watch)
        try:
            self.sock.close()
        except OSError:
            pass
        self.sock = None
        self.on_close()


class Relay:
    def __init__(self, bus):
        self.bus = bus
        self.car = None          # Link to the Tesla (we are AG)
        self.phone = None        # Link to the Oppo (we are HF)
        self.car_slc = False
        self.phone_slc = False
        self.phone_queue = []    # our own SLC commands still to send
        self.phone_waiting = False
        self.phone_ag_features = None
        self.phone_cind = None   # raw +CIND=? list from the Oppo
        self.phone_names = []
        self.phone_chld = None
        self.values = {}         # indicator name -> value (mirrors the Oppo)
        self.car_names = []      # indicator list as we gave it to the car
        self.sco_listen = None
        self.sco_phone = None
        self.sco_car = None
        self.sco_watches = []
        self.in_call = False
        self.music_timer = None
        try:
            os.makedirs(os.path.dirname(CALL_STATE_FILE), exist_ok=True)
            with open(CALL_STATE_FILE, "w") as f:
                f.write("0")
        except OSError:
            pass

    # ------------------------------------------------------------ car side
    def car_connected(self, sock):
        if self.car:
            self.car.close()
        self.car = Link("car", sock, self.car_line, self.car_closed)
        self.car_slc = False
        self.car_names = []
        log("Car HFP connected")

    def car_closed(self):
        log("Car HFP disconnected")
        self.car = None
        self.car_slc = False
        self.sco_close_car()

    def to_car(self, line):
        if self.car:
            log(f"  -> car   {line}")
            self.car.send(f"\r\n{line}\r\n")

    def car_line(self, line):
        log(f"car   ->   {line}")
        up = line.upper()
        if up.startswith("AT+BRSF="):
            feats = self.phone_ag_features if self.phone_ag_features is not None else DEFAULT_AG_FEATURES
            self.to_car(f"+BRSF: {feats & ~AG_STRIP}")
            self.to_car("OK")
        elif up.startswith("AT+BAC="):
            self.to_car("OK")
        elif up == "AT+CIND=?":
            raw = self.phone_cind or DEFAULT_CIND
            self.car_names = names_of(raw)
            self.to_car(f"+CIND: {raw}")
            self.to_car("OK")
        elif up == "AT+CIND?":
            names = self.car_names or names_of(DEFAULT_CIND)
            vals = ",".join(str(self.values.get(n, 0)) for n in names)
            self.to_car(f"+CIND: {vals}")
            self.to_car("OK")
        elif up.startswith("AT+CMER="):
            self.to_car("OK")
            if not self.phone_ag_features or not (self.phone_ag_features & 1):
                self.car_slc_done()  # no three-way calling: SLC ends here
        elif up == "AT+CHLD=?":
            self.to_car(f"+CHLD: {self.phone_chld or DEFAULT_CHLD}")
            self.to_car("OK")
            self.car_slc_done()
        elif up.startswith("AT+BIND") or up.startswith("AT+BIEV"):
            self.to_car("OK")
        elif self.phone and self.phone_slc:
            self.phone.send(line + "\r")   # pass straight through
            log(f"  -> phone {line}")
        elif any(up.startswith(p) for p in OK_WHEN_ALONE):
            self.to_car("OK")
        elif up == "AT+CLCC":
            self.to_car("OK")              # no calls without a phone
        elif up == "AT+COPS?":
            self.to_car('+COPS: 0,0,"teslabridge"')
            self.to_car("OK")
        else:
            self.to_car("ERROR")

    def car_slc_done(self):
        if not self.car_slc:
            self.car_slc = True
            log("Car SLC complete")

    # ---------------------------------------------------------- phone side
    def phone_connected(self, sock):
        if self.phone:
            self.phone.close()
        self.phone = Link("phone", sock, self.phone_line, self.phone_closed)
        self.phone_slc = False
        self.phone_queue = [f"AT+BRSF={HF_FEATURES}"]  # the rest follows +BRSF
        self.phone_waiting = False
        log("Oppo HFP connected, starting SLC")
        self.phone_next()

    def phone_closed(self):
        log("Oppo HFP disconnected")
        self.phone = None
        self.phone_slc = False
        self.sco_close_phone()
        # Tell the car the phone has no service / no call.
        for name in ("call", "callsetup", "callheld"):
            self.set_value(name, 0)
        self.set_value("service", 0)

    def phone_next(self):
        if self.phone and self.phone_queue and not self.phone_waiting:
            cmd = self.phone_queue.pop(0)
            self.phone_waiting = True
            log(f"  -> phone {cmd}  (ours)")
            self.phone.send(cmd + "\r")
        elif self.phone and not self.phone_queue and not self.phone_waiting and not self.phone_slc:
            self.phone_slc = True
            log("Oppo SLC complete; relaying")

    def phone_line(self, line):
        log(f"phone ->   {line}")
        up = line.upper()
        # Things we always track, whoever asked.
        if up.startswith("+BRSF:"):
            self.phone_ag_features = int(line.split(":", 1)[1].strip())
            # SLC order from the HFP spec, then the extras we want.
            self.phone_queue = ["AT+CIND=?", "AT+CIND?", "AT+CMER=3,0,0,1"]
            if self.phone_ag_features & 1:  # three-way calling
                self.phone_queue.append("AT+CHLD=?")
            self.phone_queue += ["AT+CLIP=1", "AT+CCWA=1", "AT+CMEE=1"]
            return
        if up.startswith("+CIND:") and "(" in line:
            self.phone_cind = line.split(":", 1)[1].strip()
            self.phone_names = names_of(self.phone_cind)
            return
        if up.startswith("+CIND:"):
            vals = [v.strip() for v in line.split(":", 1)[1].split(",")]
            for n, v in zip(self.phone_names, vals):
                self.set_value(n, int(v) if v.isdigit() else 0)
            return
        if up.startswith("+CHLD:"):
            self.phone_chld = line.split(":", 1)[1].strip()
            if not self.phone_slc:
                return
        if up.startswith("+CIEV:"):
            idx, val = [p.strip() for p in line.split(":", 1)[1].split(",")[:2]]
            if idx.isdigit() and 0 < int(idx) <= len(self.phone_names):
                self.set_value(self.phone_names[int(idx) - 1], int(val))
            return  # set_value forwards to the car with the car's numbering
        if up.startswith("+BCS:"):
            return  # codec negotiation is off; never pass this on
        # Responses to our own SLC commands stay here.
        if not self.phone_slc:
            if up in ("OK", "ERROR") or up.startswith("+CME ERROR"):
                self.phone_waiting = False
                self.phone_next()
            return
        # Everything else (RING, +CLIP, +CLCC, +CCWA, +VGS, OK/ERROR for the
        # car's own commands...) goes to the car as-is.
        if self.car_slc:
            self.to_car(line)

    def set_value(self, name, value):
        if self.values.get(name) == value:
            return
        self.values[name] = value
        self.track_call_state()
        if self.car and self.car_slc and name in self.car_names:
            self.to_car(f"+CIEV: {self.car_names.index(name) + 1},{value}")

    def track_call_state(self):
        """Note when a call (or a ringing/dialling attempt) ends, and then
        nudge the car's music stream so it actually plays again."""
        busy = bool(self.values.get("call") or self.values.get("callsetup"))
        if busy != self.in_call:
            try:
                os.makedirs(os.path.dirname(CALL_STATE_FILE), exist_ok=True)
                with open(CALL_STATE_FILE, "w") as f:
                    f.write("1" if busy else "0")
            except OSError as e:
                log(f"Could not write {CALL_STATE_FILE}: {e}")
        if busy and not self.in_call:
            self.in_call = True
            if self.music_timer:
                GLib.source_remove(self.music_timer)
                self.music_timer = None
        elif not busy and self.in_call:
            self.in_call = False
            # Close call audio ourselves rather than waiting on the far end's
            # socket to HUP: a lagging or missing HUP can leave the actual
            # SCO link established on the controller with nothing reading or
            # writing it, which shows up as corrupted packets, then a crash.
            if self.sco_phone or self.sco_car:
                log("Call over; closing call audio")
                self.sco_close_all()
            log(f"Call over; waking the car's music stream in {RESUME_MUSIC_DELAY} s")
            self.music_timer = GLib.timeout_add_seconds(RESUME_MUSIC_DELAY, self.resume_music)

    def resume_music(self):
        self.music_timer = None
        import pwd
        uid = pwd.getpwnam(AUDIO_USER).pw_uid
        env = {"XDG_RUNTIME_DIR": f"/run/user/{uid}", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin"}
        try:
            subprocess.Popen(["/usr/sbin/runuser", "-u", AUDIO_USER, "--", "sh", "-c", RESUME_MUSIC_CMD],
                             env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            log("Music stream paused and resumed")
        except OSError as e:
            log(f"Could not wake the music stream: {e}")
        return False

    # --------------------------------------------------------------- audio
    def sco_start_listening(self):
        """Listen for call audio from the Oppo on the phone adapter. Called
        again by the periodic tick if the adapter was reset."""
        if self.sco_listen is not None or not PHONE_ENABLED:
            return
        try:
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_SCO)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(PHONE_ADAPTER)
            s.listen(1)
        except OSError as e:
            log(f"Cannot listen for call audio yet: {e}")
            return
        self.sco_listen = s
        GLib.io_add_watch(s.fileno(), GLib.IO_IN | GLib.IO_HUP | GLib.IO_ERR, self.sco_incoming)
        log("Listening for call audio from the Oppo")

    def sco_incoming(self, fd, cond):
        if cond & (GLib.IO_HUP | GLib.IO_ERR):
            log("Call-audio listener closed (adapter reset?); will reopen")
            try:
                self.sco_listen.close()
            except OSError:
                pass
            self.sco_listen = None
            return False
        try:
            conn, addr = self.sco_listen.accept()
        except OSError as e:
            log(f"Call audio accept failed: {e}")
            return True
        if str(addr).upper() != PHONE:
            log(f"Rejecting call audio from {addr}")
            conn.close()
            return True
        self.sco_close_phone()
        self.sco_phone = conn
        log("Call audio: Oppo -> Pi open")
        if self.car and self.car_slc:
            try:
                c = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_SCO)
                c.bind(CAR_ADAPTER)
                c.settimeout(5)
                c.connect(CAR)
                self.sco_car = c
                log("Call audio: Pi -> car open")
            except OSError as e:
                log(f"Could not open call audio to the car: {e}")
                self.sco_car = None
        for s in (self.sco_phone, self.sco_car):
            if s:
                s.setblocking(False)
                self.sco_watches.append(GLib.io_add_watch(
                    s.fileno(), GLib.IO_IN | GLib.IO_HUP | GLib.IO_ERR, self.sco_pump))
        return True

    def sco_pump(self, fd, cond):
        src = dst = None
        for a, b in ((self.sco_phone, self.sco_car), (self.sco_car, self.sco_phone)):
            try:
                if a is not None and a.fileno() == fd:
                    src, dst = a, b
                    break
            except OSError:
                pass
        if src is None:
            return False  # socket already closed; drop this watch
        if cond & (GLib.IO_HUP | GLib.IO_ERR):
            log("Call audio closed")
            self.sco_close_all()
            return False
        try:
            data = src.recv(1024)
        except BlockingIOError:
            return True
        except OSError:
            self.sco_close_all()
            return False
        if not data:
            self.sco_close_all()
            return False
        if dst:
            try:
                dst.send(data)
            except (BlockingIOError, OSError):
                pass  # drop a packet rather than stall
        return True

    def sco_close_all(self):
        for w in self.sco_watches:
            GLib.source_remove(w)
        self.sco_watches = []
        for s in (self.sco_phone, self.sco_car):
            if s:
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    s.close()
                except OSError:
                    pass
        self.sco_phone = self.sco_car = None

    def sco_close_phone(self):
        if self.sco_phone:
            self.sco_close_all()

    def sco_close_car(self):
        if self.sco_car:
            self.sco_close_all()


class Profile(dbus.service.Object):
    def __init__(self, bus, path, relay, side):
        super().__init__(bus, path)
        self.relay = relay
        self.side = side  # "car" or "phone"

    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        log(f"{self.side} profile released")

    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
    def NewConnection(self, device, fd, props):
        reload_devices()
        addr = addr_of(str(device))
        fd = fd.take()
        sock = socket.socket(fileno=fd)
        if self.side == "car" and addr == CAR:
            self.relay.car_connected(sock)
        elif self.side == "phone" and addr == PHONE:
            self.relay.phone_connected(sock)
        else:
            log(f"Rejecting {self.side} HFP connection from {addr}")
            sock.close()

    @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
    def RequestDisconnection(self, device):
        addr = addr_of(str(device))
        if self.side == "car" and self.relay.car and addr == CAR:
            self.relay.car.close()
        elif self.side == "phone" and self.relay.phone and addr == PHONE:
            self.relay.phone.close()


def main():
    import sys
    import traceback

    def hook(t, v, tb):  # log and carry on instead of dying mid-call
        log("Unexpected error: " + "".join(traceback.format_exception(t, v, tb)))
    sys.excepthook = hook
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    relay = Relay(bus)
    mgr = dbus.Interface(bus.get_object(BLUEZ, "/org/bluez"), "org.bluez.ProfileManager1")

    ag = Profile(bus, "/teslabridge/hfp_ag", relay, "car")
    mgr.RegisterProfile("/teslabridge/hfp_ag", HFP_AG_UUID, dbus.Dictionary({
        "Name": "teslabridge Audio Gateway",
        "Channel": dbus.UInt16(13),
        "Version": dbus.UInt16(0x0107),
        "Features": dbus.UInt16(0x000D),   # 3-way, voice recognition, in-band ring
        "RequireAuthorization": dbus.Boolean(False),
        "AutoConnect": dbus.Boolean(True),
    }, signature="sv"))
    hf = Profile(bus, "/teslabridge/hfp_hf", relay, "phone")
    mgr.RegisterProfile("/teslabridge/hfp_hf", HFP_HF_UUID, dbus.Dictionary({
        "Name": "teslabridge Hands-Free",
        "Channel": dbus.UInt16(7),
        "Version": dbus.UInt16(0x0107),
        "Features": dbus.UInt16(0x001E),   # 3-way, CLI, voice recognition, volume
        "RequireAuthorization": dbus.Boolean(False),
        "AutoConnect": dbus.Boolean(True),
    }, signature="sv"))
    log("Registered HFP Audio Gateway (for the car) and Hands-Free (for the Oppo)")
    relay.sco_start_listening()

    om = dbus.Interface(bus.get_object(BLUEZ, "/"), "org.freedesktop.DBus.ObjectManager")

    def device_path(adapter_addr, dev_addr):
        for path, ifaces in om.GetManagedObjects().items():
            a = ifaces.get("org.bluez.Adapter1")
            if a and str(a["Address"]).upper() == adapter_addr:
                return f"{path}/dev_{dev_addr.replace(':', '_')}"
        return None

    connected_since = {}

    def ensure(dev_addr, adapter_addr, remote_uuid, have):
        """If the device is connected but our HFP link to it is not, open it.

        Waits until it has been connected for 20 s: right after connecting,
        the device opens HFP itself, and our connect colliding with that
        made the Tesla drop the whole connection."""
        if have():
            connected_since.pop(dev_addr, None)
            return
        path = device_path(adapter_addr, dev_addr)
        if not path:
            return
        try:
            props = dbus.Interface(bus.get_object(BLUEZ, path), "org.freedesktop.DBus.Properties")
            if not props.Get("org.bluez.Device1", "Connected"):
                connected_since.pop(dev_addr, None)
                return
            if time.time() - connected_since.setdefault(dev_addr, time.time()) < 20:
                return
            dev = dbus.Interface(bus.get_object(BLUEZ, path), "org.bluez.Device1")
            dev.ConnectProfile(remote_uuid, reply_handler=lambda: None,
                               error_handler=lambda e: log(f"HFP connect to {dev_addr}: {e.get_dbus_message()}"))
        except dbus.DBusException:
            pass

    def tick():
        try:
            if int(open("/run/teslabridge/pairing-active").read().strip()) > time.time():
                return True      # a pairing is under way: leave the adapters alone
        except (OSError, ValueError):
            pass
        reload_devices()
        relay.sco_start_listening()
        if PHONE_ENABLED:
            ensure(PHONE, PHONE_ADAPTER, HFP_AG_UUID, lambda: relay.phone is not None)
        if CAR and CAR_ADAPTER:
            ensure(CAR, CAR_ADAPTER, HFP_HF_UUID, lambda: relay.car is not None)
        return True

    GLib.timeout_add_seconds(10, tick)
    GLib.timeout_add_seconds(3, lambda: (tick(), False)[1])
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
