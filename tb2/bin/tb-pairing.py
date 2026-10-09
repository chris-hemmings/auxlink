#!/usr/bin/env python3
"""Pairing for teslabridge (release 2): pairing windows instead of hard-coding.

* Pairing agent: answers pairing and service requests with nobody at a keyboard.
  - The configured car / phone are always accepted, each on its own adapter.
  - A NEW device is accepted only while a pairing window is open for that
    role (opened from the setup page or automatically while that role has no
    device yet). When it finishes pairing, its address is saved as the car or
    phone in /etc/teslabridge.conf, it is trusted, the window closes and the
    other services restart to pick it up.
* Discoverability: an adapter is visible + pairable only while its window is
  open (or, with AUTO_PAIRABLE=1, while its known device is missing).
  Otherwise it stays hidden but connectable, so known devices still reconnect.
Window file: /run/teslabridge/pair.json  {"role": "car"|"phone", "until": epoch}
"""
import ctypes
import json
import os
import signal
import socket
import struct
import sys
import time

sys.path.insert(0, "/usr/local/lib/teslabridge")
import tbconf  # noqa: E402

import dbus  # noqa: E402
import dbus.mainloop.glib  # noqa: E402
import dbus.service  # noqa: E402
from gi.repository import GLib  # noqa: E402

BLUEZ = "org.bluez"
AGENT_PATH = "/teslabridge/agent"
WINDOW_FILE = "/run/teslabridge/pair.json"
ROLE_KEYS = {"car": ("CAR", "CAR_ADAPTER"), "phone": ("PHONE", "PHONE_ADAPTER")}


# Kernel Bluetooth management interface (what btmgmt uses), spoken directly.
AF_BLUETOOTH, BTPROTO_HCI = 31, 1
HCI_DEV_NONE, HCI_CHANNEL_CONTROL = 0xFFFF, 3
MGMT_OP_READ_INFO, MGMT_OP_SET_CONNECTABLE, MGMT_OP_SET_DEV_CLASS = 0x0004, 0x0007, 0x000E
MGMT_EV_CMD_COMPLETE, MGMT_EV_CMD_STATUS = 0x0001, 0x0002
MGMT_SETTING_CONNECTABLE = 0x00000002
PHONE_MAJOR, SMARTPHONE_MINOR = 0x02, 0x0C   # class of device: Phone / Smartphone
_libc = ctypes.CDLL(None, use_errno=True)


def mgmt(opcode, index, params=b"", timeout=3):
    """Send one management command; returns (status, reply data).
    Python's bind() can't select the control channel, hence libc."""
    s = socket.socket(AF_BLUETOOTH, socket.SOCK_RAW | socket.SOCK_CLOEXEC, BTPROTO_HCI)
    try:
        sa = struct.pack("=HHH", AF_BLUETOOTH, HCI_DEV_NONE, HCI_CHANNEL_CONTROL)
        if _libc.bind(s.fileno(), sa, len(sa)) < 0:
            err = ctypes.get_errno()
            raise OSError(err, f"bind: {os.strerror(err)}")
        s.settimeout(timeout)
        s.send(struct.pack("<HHH", opcode, index, len(params)) + params)
        deadline = time.time() + timeout
        while True:
            s.settimeout(max(deadline - time.time(), 0.01))
            try:
                pkt = s.recv(1024)
            except socket.timeout:
                raise OSError(f"no reply to mgmt command 0x{opcode:04x}")
            if len(pkt) < 9:
                continue
            ev, idx, plen = struct.unpack_from("<HHH", pkt)
            op, status = struct.unpack_from("<HB", pkt, 6)
            # Other events (settings changes, connections...) arrive too.
            if ev in (MGMT_EV_CMD_COMPLETE, MGMT_EV_CMD_STATUS) and idx == index and op == opcode:
                return status, pkt[9:6 + plen]
    finally:
        s.close()


def log(msg):
    print(msg, flush=True)


class Rejected(dbus.DBusException):
    _dbus_error_name = "org.bluez.Error.Rejected"


def dev_addr(device_path):
    return str(device_path).rpartition("/dev_")[2].replace("_", ":").upper()


class Pairing:
    def __init__(self, bus):
        self.bus = bus
        self.om = dbus.Interface(bus.get_object(BLUEZ, "/"), "org.freedesktop.DBus.ObjectManager")
        self.conf = tbconf.load()
        self.pending = {}   # role -> device address that is pairing right now
        self.state = {}

    # ---------------- config / windows ----------------
    def roles(self):
        """role -> (adapter address, device address or '')"""
        c = self.conf
        out = {}
        for role, (dk, ak) in ROLE_KEYS.items():
            if role == "phone" and c.get("PHONE_ENABLED", "1") != "1":
                continue
            if c.get(ak):
                out[role] = (c[ak].upper(), c.get(dk, "").upper())
        return out

    def window(self):
        """The open pairing window role, or None. A role with no device yet is
        always open (first-time setup)."""
        try:
            w = json.load(open(WINDOW_FILE))
            if w.get("until", 0) > time.time():
                return w.get("role")
        except (OSError, ValueError):
            pass
        return None

    def window_open(self, role):
        r = self.roles().get(role)
        return self.window() == role or (r is not None and not r[1])

    def close_window(self, role):
        if self.window() == role:
            try:
                os.remove(WINDOW_FILE)
            except OSError:
                pass

    def role_of_adapter(self, adapter_path):
        a = self.om.GetManagedObjects().get(dbus.ObjectPath(adapter_path), {}).get("org.bluez.Adapter1")
        addr = str(a["Address"]).upper() if a else None
        for role, (ad, _) in self.roles().items():
            if ad == addr:
                return role
        return None

    # ---------------- decisions ----------------
    def decide(self, device_path, pairing):
        # Re-read the config now: an adapter saved on the setup page a second
        # ago must already count (a stale copy caused "unknown adapter").
        self.conf = tbconf.load()
        adapter_path = str(device_path).rpartition("/dev_")[0]
        role = self.role_of_adapter(adapter_path)
        addr = dev_addr(device_path)
        if role is None:
            return False, addr, None
        known = self.roles()[role][1]
        if addr == known or self.pending.get(role) == addr:
            return True, addr, role
        if not pairing:
            # A service-authorization request (not a pairing step). Only let
            # this through for a device that is ALREADY bonded - otherwise an
            # unpaired phone can open profile connections (e.g. "just
            # connect" on Android) that race with and abort the real pairing
            # handshake, which is what produced "incorrect PIN" on the Oppo.
            if self.window_open(role) and self.is_paired(device_path):
                self.pending[role] = addr
                GLib.idle_add(lambda: (self.paired(device_path), False)[1])
                return True, addr, role
            return False, addr, role
        if self.window_open(role):
            self.pending[role] = addr
            return True, addr, role
        return False, addr, role

    def is_paired(self, device_path):
        try:
            p = dbus.Interface(self.bus.get_object(BLUEZ, str(device_path)), "org.freedesktop.DBus.Properties")
            return bool(p.Get("org.bluez.Device1", "Paired"))
        except dbus.DBusException:
            return False

    def trust(self, device_path):
        try:
            p = dbus.Interface(self.bus.get_object(BLUEZ, device_path), "org.freedesktop.DBus.Properties")
            if not p.Get("org.bluez.Device1", "Trusted"):
                p.Set("org.bluez.Device1", "Trusted", dbus.Boolean(True))
        except dbus.DBusException:
            pass
        return False

    # ---------------- pairing in progress: hands off the adapters ----------
    PAIRING_FLAG = "/run/teslabridge/pairing-active"

    def pairing_started(self):
        """A pairing handshake is under way. Until it finishes (or 60 s pass),
        nothing may touch any adapter: no power/discoverable/pairable changes,
        no btmgmt, no power-offs, and (via the flag file) no reconnect attempts
        from tesla-reconnect / hfp-relay. bluetoothctl pairs reliably because
        it leaves the adapter alone during the handshake; so do we now."""
        self.pairing_until = time.time() + 60
        try:
            os.makedirs(os.path.dirname(self.PAIRING_FLAG), exist_ok=True)
            open(self.PAIRING_FLAG, "w").write(str(int(self.pairing_until)))
        except OSError:
            pass

    def pairing_finished(self):
        self.pairing_until = 0
        try:
            os.remove(self.PAIRING_FLAG)
        except OSError:
            pass

    def pairing_busy(self):
        if getattr(self, "pairing_until", 0) > time.time():
            return True
        if getattr(self, "pairing_until", 0):
            self.pairing_finished()   # timed out
        return False

    def paired(self, device_path):
        """A device finished pairing: if it was a window pairing, adopt it."""
        addr = dev_addr(device_path)
        # Pairings that never asked the agent (e.g. "just works") still count
        # if they happened on an adapter whose window is open.
        role = self.role_of_adapter(str(device_path).rpartition("/dev_")[0])
        if role and role not in self.pending and self.window_open(role) \
                and addr != self.roles()[role][1]:
            self.pending[role] = addr
        for role, a in list(self.pending.items()):
            if a != addr:
                continue
            dk, _ = ROLE_KEYS[role]
            name = self.device_name(device_path)
            tbconf.save({dk: addr})
            self.conf = tbconf.load()
            self.trust(device_path)
            self.close_window(role)
            del self.pending[role]
            msg = f"Paired {name or addr} as the {role}"
            log(msg)
            tbconf.event(msg)
            self.pairing_finished()
            # Don't restart the Bluetooth services: that would cut the car or
            # phone off mid-connection. They pick the new device up by
            # themselves; only the contacts sync needs a nudge for a phone.
            if role == "phone":
                tbconf.restart_user(["pbap-sync"], delay=3)

    def device_name(self, device_path):
        try:
            p = dbus.Interface(self.bus.get_object(BLUEZ, device_path), "org.freedesktop.DBus.Properties")
            return str(p.Get("org.bluez.Device1", "Alias"))
        except dbus.DBusException:
            return ""

    def power_off_unused(self):
        """Adapters not assigned to the car or phone are switched off.

        Re-reads the config right before acting, and only powers off an
        adapter that has been unused for two ticks in a row, so a save that
        is mid-flight (e.g. the car written, the phone write still landing)
        can never cause an adapter to be switched off underneath it."""
        self.conf = tbconf.load()
        used = {ad for ad, _ in self.roles().values()}
        if not used:
            self._unused_streak = {}
            return  # nothing assigned yet (first run): leave everything on
        streak = getattr(self, "_unused_streak", {})
        for path, ifaces in self.om.GetManagedObjects().items():
            a = ifaces.get("org.bluez.Adapter1")
            if not a:
                continue
            addr = str(a["Address"]).upper()
            if addr in used:
                streak.pop(addr, None)
                continue
            if not a.get("Powered"):
                continue
            streak[addr] = streak.get(addr, 0) + 1
            if streak[addr] < 2:
                continue
            try:
                dbus.Interface(self.bus.get_object(BLUEZ, path), "org.freedesktop.DBus.Properties") \
                    .Set("org.bluez.Adapter1", "Powered", dbus.Boolean(False))
                log(f"Unused adapter {addr} switched off")
                streak.pop(addr, None)
            except dbus.DBusException:
                pass
        self._unused_streak = streak

    def ensure_connectable(self, adapter_path, addr, role, force=False):
        """Make sure the controller accepts incoming connections (page scan)
        and, on the car side, presents itself as a smartphone.

        Throttled to once per 10 s per adapter UNLESS force=True, which the
        caller sets the moment an adapter is newly assigned a role or freshly
        powered on - that is exactly when a bad state is most likely and most
        costly: the Tesla (and most phones) only try the initial connect
        once, with no retry of their own, so a connectable gap that survives
        even a few seconds right then causes a hard pairing failure, not
        just a delay.

        Device class: bluetoothd leaves it at "Miscellaneous" (0x7c0000). The
        Tesla then lists the Pi with a generic Bluetooth icon instead of a
        phone and hangs up on it straight after the baseband connect, before
        any authentication, and never auto-connects it. Phone/smartphone fixes
        that. bluetoothd resets the class when it restarts, so it is checked
        every time, like connectable.

        Talks to the kernel's management interface directly: btmgmt hangs
        when run from a service (no terminal) and always timed out here."""
        idx = adapter_path.rsplit("hci", 1)[-1]
        if not idx.isdigit():
            return
        idx = int(idx)
        last = getattr(self, "_conn_checked", {})
        if not force and time.time() - last.get(idx, 0) < 10:
            return
        last[idx] = time.time()
        self._conn_checked = last
        try:
            status, info = mgmt(MGMT_OP_READ_INFO, idx)
            if status or len(info) < 20:
                log(f"hci{idx}: read info failed (status {status})")
                return
            # bdaddr(6, reversed) version(1) manufacturer(2) supported(4)
            # current(4) class(3: minor, major, services)
            got = ":".join(f"{b:02X}" for b in reversed(info[:6]))
            if got != addr:
                log(f"hci{idx} is {got}, not {addr}; skipping connectable check")
                return
            if role == "car" and (info[18], info[17]) != (PHONE_MAJOR, SMARTPHONE_MINOR):
                status, _ = mgmt(MGMT_OP_SET_DEV_CLASS, idx, bytes([PHONE_MAJOR, SMARTPHONE_MINOR]))
                if status:
                    log(f"hci{idx} ({addr}): setting phone device class failed (status {status})")
                else:
                    log(f"hci{idx} ({addr}): device class set to smartphone")
            current = struct.unpack_from("<I", info, 13)[0]
            if current & MGMT_SETTING_CONNECTABLE:
                return
            status, _ = mgmt(MGMT_OP_SET_CONNECTABLE, idx, b"\x01")
        except OSError as e:
            log(f"hci{idx}: management socket failed: {e}")
            return
        if status:
            log(f"hci{idx} ({addr}): connectable on failed (status {status})")
        else:
            log(f"hci{idx} ({addr}): switched connectable back on")

    # ---------------- discoverability ----------------
    def adopt_finished(self, objs):
        """Adopt pending devices that are now paired. Needed because when the
        Pi still holds an old bond for the device, "Paired" was already true
        and no change signal ever arrives for the new pairing."""
        for role, addr in list(self.pending.items()):
            ad = self.roles().get(role, ("", ""))[0]
            for path, ifaces in objs.items():
                d = ifaces.get("org.bluez.Device1")
                if not d or str(d.get("Address", "")).upper() != addr:
                    continue
                if not str(d.get("Adapter", "")) or self.role_of_adapter(str(d["Adapter"])) != role:
                    continue
                if d.get("Paired") and (d.get("Connected") or d.get("Bonded")):
                    self.paired(str(path))

    def tick(self):
        self.conf = tbconf.load()
        if self.pending:
            self.adopt_finished(self.om.GetManagedObjects())
        if self.pairing_busy():
            return True          # hands off every adapter until it completes
        self.power_off_unused()
        auto = self.conf.get("AUTO_PAIRABLE", "0") == "1"
        objs = self.om.GetManagedObjects()
        known_roles = getattr(self, "_known_role_adapters", set())
        current_roles = {ad for ad, _ in self.roles().values()}
        for role, (ad, target) in self.roles().items():
            newly_assigned = ad not in known_roles
            path = next((p for p, i in objs.items()
                         if str(i.get("org.bluez.Adapter1", {}).get("Address", "")).upper() == ad), None)
            if not path:
                continue
            a = objs[path]["org.bluez.Adapter1"]
            dev = objs.get(dbus.ObjectPath(f"{path}/dev_{target.replace(':', '_')}"), {}).get("org.bluez.Device1") if target else None
            connected = bool(dev and dev.get("Connected"))
            visible = self.window_open(role) or (auto and target and not connected)
            props = dbus.Interface(self.bus.get_object(BLUEZ, path), "org.freedesktop.DBus.Properties")
            want_name = self.conf.get("CAR_NAME" if role == "car" else "PHONE_NAME", "")
            try:
                if want_name and str(a.get("Alias", "")) != want_name:
                    props.Set("org.bluez.Adapter1", "Alias", dbus.String(want_name))
                    log(f"{role} adapter renamed to '{want_name}'")
                just_powered_on = False
                if not a.get("Powered"):
                    props.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
                    just_powered_on = True
                if visible:
                    if a.get("DiscoverableTimeout", 1) != 0:
                        props.Set("org.bluez.Adapter1", "DiscoverableTimeout", dbus.UInt32(0))
                    for k in ("Pairable", "Discoverable"):
                        if not a.get(k):
                            props.Set("org.bluez.Adapter1", k, dbus.Boolean(True))
                else:
                    # Hidden from new devices, but stay pairable/bondable (the
                    # agent still refuses strangers) and above all CONNECTABLE,
                    # or the paired car/phone can no longer reach us.
                    if a.get("Discoverable"):
                        props.Set("org.bluez.Adapter1", "Discoverable", dbus.Boolean(False))
                    if not a.get("Pairable"):
                        props.Set("org.bluez.Adapter1", "Pairable", dbus.Boolean(True))
                self.ensure_connectable(str(path), ad, role, force=(newly_assigned or just_powered_on))
                if dev and dev.get("Paired") and not dev.get("Trusted"):
                    self.trust(f"{path}/dev_{target.replace(':', '_')}")
            except dbus.DBusException as e:
                log(f"{role} adapter: {e.get_dbus_message()}")
                continue
            st = "visible" if visible else "hidden"
            if self.state.get(role) != st:
                self.state[role] = st
                log(f"{role} adapter {ad}: {st}")
        self._known_role_adapters = current_roles
        return True


class Agent(dbus.service.Object):
    def __init__(self, bus, pairing):
        super().__init__(bus, AGENT_PATH)
        self.p = pairing

    def check(self, device, what, pairing):
        ok, addr, role = self.p.decide(device, pairing)
        if not ok:
            log(f"Rejected {what} from {addr} ({role or 'unknown adapter'})")
            raise Rejected("Not allowed now")
        log(f"Accepted {what} from {addr} as {role}")
        if pairing:
            self.p.pairing_started()
        else:
            GLib.timeout_add_seconds(1, self.p.trust, str(device))

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Release(self):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        self.check(device, f"service {uuid[4:8]}", pairing=False)

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        self.check(device, "PIN pairing", pairing=True)
        return "0000"

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        self.check(device, "passkey pairing", pairing=True)
        return dbus.UInt32(0)

    @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        self.check(device, f"pairing, code {passkey:06d}", pairing=True)
        tbconf.event(f"Pairing code {passkey:06d}: confirm it on the device")

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        self.check(device, "pairing", pairing=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        log("Pairing cancelled")
        self.p.pairing_finished()


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    pairing = Pairing(bus)
    Agent(bus, pairing)
    mgr = dbus.Interface(bus.get_object(BLUEZ, "/org/bluez"), "org.bluez.AgentManager1")
    # Same capability bluetoothctl uses, so phones and cars negotiate the
    # same pairing method they did in the manual test.
    mgr.RegisterAgent(AGENT_PATH, "KeyboardDisplay")
    mgr.RequestDefaultAgent(AGENT_PATH)
    log("Pairing agent registered")
    pairing.pairing_finished()   # clear any flag left by a previous run

    def dev_changed(iface, changed, invalidated, path=None):
        if changed.get("Paired"):
            pairing.paired(path)

    bus.add_signal_receiver(dev_changed, signal_name="PropertiesChanged",
                            dbus_interface=dbus.PROPERTIES_IFACE, bus_name=BLUEZ,
                            arg0="org.bluez.Device1", path_keyword="path")
    pairing.tick()
    GLib.timeout_add_seconds(2, pairing.tick)
    loop = GLib.MainLoop()

    def stop(*_):
        # Unregister properly instead of vanishing: being killed mid-handshake
        # made BlueZ discard the half-finished pairing (the Oppo's record
        # disappeared when the service was stopped).
        try:
            mgr.UnregisterAgent(AGENT_PATH)
            log("Pairing agent unregistered")
        except dbus.DBusException:
            pass
        pairing.pairing_finished()
        loop.quit()
        return False

    GLib.unix_signal_add(GLib.PRIORITY_HIGH, signal.SIGTERM, stop)
    GLib.unix_signal_add(GLib.PRIORITY_HIGH, signal.SIGINT, stop)
    loop.run()


if __name__ == "__main__":
    sys.exit(main())
