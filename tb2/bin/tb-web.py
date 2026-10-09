#!/usr/bin/env python3
"""teslabridge setup page + setup Wi-Fi.

Web page (port 80): status, pairing windows for the car and phone, adapter
assignment, feature switches, audio check/fix, logs, update from a zip.
Setup Wi-Fi: a hotspot (NetworkManager) that is on only when needed:
  * nothing paired yet, or
  * the first SETUP_WIFI_MINUTES after boot, or
  * the setup button (GPIO to GND) was held 3 s (on for 15 minutes),
and never while the Pi is already on a normal Wi-Fi network (then use
http://teslabridge.local on that network instead). It stays on while the page
is being used.
"""
import base64
import glob
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, "/usr/local/lib/teslabridge")
import tbconf  # noqa: E402

import dbus  # noqa: E402

INDEX = "/usr/local/share/teslabridge/index.html"
WINDOW_FILE = "/run/teslabridge/pair.json"
CALL_FILE = "/run/teslabridge/call"
AP_NAME = "tb-setup"
SYSTEM_UNITS = tbconf.SYSTEM_UNITS + ["tb-web"]
USER_UNITS = tbconf.USER_UNITS + ["obex", "pipewire", "wireplumber"]
EDITABLE = {
    "CAR_NAME": r"[\w .\-]{1,30}", "PHONE_NAME": r"[\w .\-]{1,30}",
    "PHONE_ENABLED": r"[01]", "AUTO_PAIRABLE": r"[01]", "PAUSE_FOR_CALLS": r"[01]",
    "CONTACTS_SYNC": r"[01]", "CALL_RESUME_DELAY": r"\d{1,2}(\.\d)?",
    "SETUP_WIFI_SSID": r"[\w\-]{1,32}", "SETUP_WIFI_PASS": r"[^\s'\"]{8,63}",
    "SETUP_WIFI_MINUTES": r"\d{1,3}", "WEB_PASSWORD": r"[^\s'\"]{0,63}",
    "SETUP_BUTTON_GPIO": r"\d{0,2}",
}
BOOT = time.monotonic()
STATE = {"ap": False, "button_until": 0.0, "last_hit": 0.0, "connect_after": None}
HOME_PREFIX = "home-"   # NetworkManager profiles created from the page


def sh(*args, timeout=15):
    try:
        r = subprocess.run(list(args), capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout.strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)


def uptime():
    try:
        return float(open("/proc/uptime").read().split()[0])
    except OSError:
        return time.monotonic() - BOOT


# ------------------------------------------------------------- Bluetooth
def bluez():
    bus = dbus.SystemBus()
    om = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
    return bus, om.GetManagedObjects()


def adapters(objs):
    """Every adapter BlueZ knows (powered or not: tb-pairing switches unused
    ones off, and they must stay choosable on the setup page)."""
    out = []
    for path, ifaces in objs.items():
        a = ifaces.get("org.bluez.Adapter1")
        if not a:
            continue
        hci = str(path).rsplit("/", 1)[-1]
        dev = os.path.realpath(f"/sys/class/bluetooth/{hci}/device")
        out.append({"path": str(path), "hci": hci, "address": str(a["Address"]).upper(),
                    "name": str(a.get("Alias", "")), "powered": bool(a.get("Powered")),
                    "discoverable": bool(a.get("Discoverable")),
                    "bus": "built-in" if "serial" in dev else "USB"})
    # A powered-off adapter can still briefly be missing from BlueZ's object
    # list right after power-on/off; fall back to what the kernel sees so it
    # never silently drops out of the dropdowns.
    seen = {a["address"] for a in out}
    for d in glob.glob("/sys/class/bluetooth/hci*"):
        hci = os.path.basename(d)
        if hci in {a["hci"] for a in out}:
            continue
        try:
            addr = open(f"{d}/address").read().strip().upper()
        except OSError:
            continue
        if addr and addr not in seen:
            out.append({"path": "", "hci": hci, "address": addr, "name": "",
                        "powered": False, "discoverable": False,
                        "bus": "built-in" if "serial" in os.path.realpath(f"{d}/device") else "USB"})
    return sorted(out, key=lambda x: x["hci"])


def device(objs, adapter_addr, addr):
    if not addr:
        return None
    for path, ifaces in objs.items():
        a = ifaces.get("org.bluez.Adapter1")
        if a and str(a["Address"]).upper() == adapter_addr:
            d = objs.get(dbus.ObjectPath(f"{path}/dev_{addr.replace(':', '_')}"), {}).get("org.bluez.Device1")
            if d:
                return {"address": addr, "name": str(d.get("Alias", "")),
                        "connected": bool(d.get("Connected")), "paired": bool(d.get("Paired")),
                        "path": f"{path}/dev_{addr.replace(':', '_')}", "adapter_path": str(path)}
            return {"address": addr, "name": "", "connected": False, "paired": False}
    return {"address": addr, "name": "", "connected": False, "paired": False}


def bus_keys(ads, car, phone):
    """Remember whether each chosen adapter is on USB (for the watchdog)."""
    bus = {a["address"]: ("usb" if a["bus"] == "USB" else "builtin") for a in ads}
    return {"CAR_ADAPTER_BUS": bus.get(str(car).upper(), ""),
            "PHONE_ADAPTER_BUS": bus.get(str(phone).upper(), "")}


def auto_assign():
    """First run: car = first USB adapter, phone = second (built-in as last resort)."""
    c = tbconf.load()
    if c.get("CAR_ADAPTER") and c.get("PHONE_ADAPTER"):
        return
    try:
        _, objs = bluez()
    except dbus.DBusException:
        return
    ads = adapters(objs)
    usb = [a["address"] for a in ads if a["bus"] == "USB"]
    builtin = [a["address"] for a in ads if a["bus"] != "USB"]
    # Default: car on the built-in Bluetooth (keeps the busiest link off USB),
    # phone on the first dongle. Change it any time on the setup page.
    order = builtin + usb
    upd = {}
    if not c.get("CAR_ADAPTER") and order:
        upd["CAR_ADAPTER"] = order[0]
    car = upd.get("CAR_ADAPTER") or c.get("CAR_ADAPTER", "").upper()
    rest = [a for a in usb + builtin if a != car]
    if not c.get("PHONE_ADAPTER") and rest:
        upd["PHONE_ADAPTER"] = rest[0]
    upd.update(bus_keys(ads, upd.get("CAR_ADAPTER", c.get("CAR_ADAPTER", "")),
                        upd.get("PHONE_ADAPTER", c.get("PHONE_ADAPTER", ""))))
    if upd:
        tbconf.save(upd)
        tbconf.event("Assigned adapters automatically: " + ", ".join(f"{k}={v}" for k, v in upd.items()))
        tbconf.restart_all(delay=1, skip=("tb-pairing",))


def window():
    try:
        w = json.load(open(WINDOW_FILE))
        left = int(w.get("until", 0) - time.time())
        if left > 0:
            return {"role": w.get("role"), "seconds_left": left}
    except (OSError, ValueError):
        pass
    return None


# ------------------------------------------------------------- services
def unit_states(user):
    out = {}
    for u in SYSTEM_UNITS:
        out[u] = sh("systemctl", "is-active", u)[1] or "unknown"
    for u in USER_UNITS:
        r = tbconf.run_as_user(user, ["systemctl", "--user", "is-active", u])
        out[u] = (r.stdout or "").strip() or "unknown"
    return out


def state():
    c = tbconf.load()
    user = c.get("AUDIO_USER", "chris")
    try:
        _, objs = bluez()
        ads = adapters(objs)
        car = device(objs, c.get("CAR_ADAPTER", "").upper(), c.get("CAR", "").upper())
        phone = device(objs, c.get("PHONE_ADAPTER", "").upper(), c.get("PHONE", "").upper())
        bt_error = None
    except dbus.DBusException as e:
        ads, car, phone, bt_error = [], None, None, e.get_dbus_message()
    for d in (car, phone):
        if d:
            d.pop("path", None)
            d.pop("adapter_path", None)
    for a in ads:
        a["role"] = "car" if a["address"] == c.get("CAR_ADAPTER", "").upper() else \
                    "phone" if a["address"] == c.get("PHONE_ADAPTER", "").upper() else ""
    try:
        events = open(tbconf.EVENTS).read().splitlines()[-30:]
    except OSError:
        events = []
    conf = {k: c.get(k, "") for k in EDITABLE}
    conf["WEB_PASSWORD"] = "set" if c.get("WEB_PASSWORD") else ""
    conf.update({k: c.get(k, "") for k in ("CAR", "CAR_ADAPTER", "PHONE", "PHONE_ADAPTER")})
    return {
        "conf": conf, "adapters": ads, "car": car, "phone": phone, "bt_error": bt_error,
        "window": window(), "in_call": open(CALL_FILE).read().strip() == "1" if os.path.exists(CALL_FILE) else False,
        "services": unit_states(user), "events": events, "setup_wifi": STATE["ap"],
        "home_wifi": home_networks(), "wifi_client": wifi_client_connected(),
    }


# ------------------------------------------------------------- actions
def act_pair(body):
    role = body.get("role")
    if role not in ("car", "phone"):
        raise ValueError("role must be car or phone")
    secs = max(30, min(int(body.get("seconds", 120)), 600))
    os.makedirs(os.path.dirname(WINDOW_FILE), exist_ok=True)
    json.dump({"role": role, "until": time.time() + secs}, open(WINDOW_FILE, "w"))
    name = tbconf.load().get("CAR_NAME" if role == "car" else "PHONE_NAME", "teslabridge")
    tbconf.event(f"Pairing window open for the {role} ({secs} s): on the {role}, add Bluetooth device '{name}'")
    return {"ok": True}


def act_pair_cancel(body):
    try:
        os.remove(WINDOW_FILE)
    except OSError:
        pass
    tbconf.event("Pairing window closed")
    return {"ok": True}


def act_forget(body):
    role = body.get("role")
    keys = {"car": ("CAR", "CAR_ADAPTER"), "phone": ("PHONE", "PHONE_ADAPTER")}
    if role not in keys:
        raise ValueError("role must be car or phone")
    c = tbconf.load()
    dk, ak = keys[role]
    addr = c.get(dk, "").upper()
    if addr:
        bus, objs = bluez()
        d = device(objs, c.get(ak, "").upper(), addr)
        if d and d.get("adapter_path") and d.get("path"):
            try:
                dbus.Interface(bus.get_object("org.bluez", d["adapter_path"]), "org.bluez.Adapter1") \
                    .RemoveDevice(dbus.ObjectPath(d["path"]))
            except dbus.DBusException:
                pass
    tbconf.save({dk: ""})
    tbconf.event(f"Forgot the {role}")
    tbconf.restart_all(delay=1, skip=("tb-pairing",))
    return {"ok": True}


def act_config(body):
    upd = {}
    for k, v in body.items():
        if k not in EDITABLE:
            raise ValueError(f"{k} cannot be changed here")
        v = str(v).strip()
        if not re.fullmatch(EDITABLE[k], v):
            raise ValueError(f"invalid value for {k}")
        upd[k] = v
    old = tbconf.load()
    tbconf.save(upd)
    if any(old.get(k) != upd.get(k) for k in ("SETUP_WIFI_SSID", "SETUP_WIFI_PASS") if k in upd):
        sh("nmcli", "connection", "delete", AP_NAME)   # recreated with the new details
        STATE["ap"] = False
    tbconf.event("Settings changed: " + ", ".join(sorted(upd)))
    tbconf.restart_all(delay=1, skip=("tb-pairing",))
    return {"ok": True}


def act_adapters(body):
    car, phone = str(body.get("car", "")).upper(), str(body.get("phone", "")).upper()
    _, objs = bluez()
    ads = adapters(objs)
    present = {a["address"] for a in ads}
    if car not in present or (phone and phone not in present) or car == phone:
        raise ValueError("pick two different adapters that are present")
    c = tbconf.load()
    upd = {"CAR_ADAPTER": car, "PHONE_ADAPTER": phone}
    upd.update(bus_keys(ads, car, phone))
    if c.get("CAR_ADAPTER", "").upper() != car:
        upd["CAR"] = ""      # pairings belong to an adapter; pair again
    if c.get("PHONE_ADAPTER", "").upper() != phone:
        upd["PHONE"] = ""
    tbconf.save(upd)
    tbconf.event("Adapters assigned: car " + car + ", phone " + (phone or "none"))
    tbconf.restart_all(delay=1, skip=("tb-pairing",))
    return {"ok": True}


def act_check(body):
    c = tbconf.load()
    user = c.get("AUDIO_USER", "chris")
    home = os.path.expanduser(f"~{user}")
    args = [f"{home}/.local/bin/audio-check.sh"] + (["--fix"] if body.get("fix") else [])
    r = tbconf.run_as_user(user, args, timeout=90)
    text = re.sub(r"\x1b\[[0-9;]*m", "", (r.stdout or "") + (r.stderr or ""))
    return {"ok": True, "output": text}


def act_restart(body):
    tbconf.event("Restarting all services")
    tbconf.restart_all(delay=1, skip=("tb-web", "tb-pairing"))
    return {"ok": True}


UPDATE_DIR = "/var/lib/teslabridge/update"
UPDATE_LOG = "/var/lib/teslabridge/update.log"
MAX_UPDATE = 50 * 1024 * 1024


def act_update(data):
    """Install an uploaded zip: the release zip (tb2/...) or a GitHub download
    of the repository (<repo>-<branch>/tb2/...). Runs its update.sh as a
    separate systemd job, because update.sh restarts this web server."""
    import io
    import shutil
    import zipfile
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise ValueError("that file is not a zip")
    found = sorted((n for n in z.namelist() if n == "tb2/update.sh" or n.endswith("/tb2/update.sh")),
                   key=len)
    if not found:
        raise ValueError("no tb2/update.sh in the zip: is it the teslabridge zip?")
    prefix = found[0][:-len("update.sh")]
    if sh("systemctl", "is-active", "--quiet", "tb-update")[0] == 0:
        raise ValueError("an update is already running")
    shutil.rmtree(UPDATE_DIR, ignore_errors=True)
    dest = os.path.join(UPDATE_DIR, "tb2")
    for info in z.infolist():
        if not info.filename.startswith(prefix) or info.is_dir():
            continue
        rel = info.filename[len(prefix):]
        if rel.startswith("/") or ".." in rel.split("/"):
            raise ValueError(f"unsafe path in zip: {info.filename}")
        out = os.path.join(dest, rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with z.open(info) as src, open(out, "wb") as f:
            shutil.copyfileobj(src, f)
    os.makedirs(os.path.dirname(UPDATE_LOG), exist_ok=True)
    with open(UPDATE_LOG, "w") as f:
        f.write(f"Update unpacked ({len(data) // 1024} KB); starting in 2 s...\n")
    user = tbconf.load().get("AUDIO_USER", "chris")
    sh("systemctl", "reset-failed", "tb-update")
    # The 2 s lets this reply reach the browser before tb-web is restarted.
    script = (f'sleep 2; bash "{dest}/update.sh" >>"{UPDATE_LOG}" 2>&1; '
              f'echo "== finished (exit $?)" >>"{UPDATE_LOG}"')
    rc, out = sh("systemd-run", "--unit=tb-update", "--collect", f"--setenv=SUDO_USER={user}",
                 "/bin/bash", "-c", script)
    if rc:
        raise ValueError(f"could not start the update: {out}")
    tbconf.event("Update uploaded from the setup page; installing")
    return {"ok": True}


def act_wifi_off(body):
    STATE["button_until"] = 0
    STATE["last_hit"] = 0
    STATE["force_off"] = True
    tbconf.event("Setup Wi-Fi switched off from the page")
    return {"ok": True}


def logs(unit):
    c = tbconf.load()
    if unit == "update":
        try:
            return open(UPDATE_LOG).read()
        except OSError:
            return "(no update run yet)"
    if unit in SYSTEM_UNITS:
        return sh("journalctl", "-u", unit, "-n", "120", "--no-pager", "-o", "short-iso")[1]
    if unit in USER_UNITS:
        r = tbconf.run_as_user(c.get("AUDIO_USER", "chris"),
                               ["journalctl", "--user-unit", unit, "-n", "120", "--no-pager"])
        out = r.stdout.strip()
        if not out:  # user journal not persisted: fall back to the system journal
            out = sh("journalctl", f"_SYSTEMD_USER_UNIT={unit}.service", "-n", "120", "--no-pager")[1]
        return out
    if unit == "kernel":
        return sh("sh", "-c", "journalctl -k -n 300 --no-pager | grep -i -E 'bluetooth|hci|rtl|usb' | tail -80")[1]
    return "unknown unit"


# ------------------------------------------------------------- home Wi-Fi
def home_networks():
    """Saved Wi-Fi client networks (never the setup hotspot); no passwords."""
    out = []
    _, names = sh("nmcli", "-t", "-f", "NAME,TYPE,DEVICE", "connection", "show")
    for line in names.splitlines():
        parts = line.rsplit(":", 2)
        if len(parts) != 3 or parts[1] != "802-11-wireless" or parts[0] == AP_NAME:
            continue
        name = parts[0].replace("\\:", ":")
        _, ssid = sh("nmcli", "-g", "802-11-wireless.ssid", "connection", "show", name)
        _, mode = sh("nmcli", "-g", "802-11-wireless.mode", "connection", "show", name)
        if mode == "ap":
            continue
        out.append({"name": name, "ssid": ssid or name, "active": parts[2] == "wlan0"})
    return out


def act_wifi_add(body):
    ssid = str(body.get("ssid", "")).strip()
    pw = str(body.get("password", ""))
    if not (1 <= len(ssid.encode()) <= 32) or any(ord(ch) < 32 for ch in ssid):
        raise ValueError("Wi-Fi name must be 1-32 characters")
    if pw and not (8 <= len(pw) <= 63):
        raise ValueError("Wi-Fi password must be 8-63 characters (or blank for an open network)")
    name = HOME_PREFIX + re.sub(r"[^\w\-]", "_", ssid)[:40]
    sh("nmcli", "connection", "delete", name)          # replace if it already exists
    args = ["nmcli", "connection", "add", "type", "wifi", "ifname", "wlan0", "con-name", name,
            "autoconnect", "yes", "ssid", ssid]
    if pw:
        args += ["wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", pw]
    rc, out = sh(*args)
    if rc != 0:
        raise ValueError("could not save the network: " + out.splitlines()[-1] if out else "nmcli failed")
    tbconf.event(f"Saved home Wi-Fi '{ssid}'" + ("" if STATE["ap"] else "; connecting"))
    if not STATE["ap"]:
        sh("nmcli", "connection", "up", name, timeout=40)
    return {"ok": True, "joins_after_setup": STATE["ap"]}


def act_wifi_remove(body):
    name = str(body.get("name", ""))
    if not name or name == AP_NAME or name not in [n["name"] for n in home_networks()]:
        raise ValueError("unknown network")
    rc, out = sh("nmcli", "connection", "delete", name)
    if rc != 0:
        raise ValueError(out or "nmcli failed")
    tbconf.event(f"Removed Wi-Fi network '{name}'")
    return {"ok": True}


def act_wifi_scan(body):
    rc, out = sh("nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list",
                 "--rescan", "yes", timeout=30)
    if rc != 0:
        raise ValueError("scan not possible right now (the setup Wi-Fi is using the radio)")
    seen = {}
    for line in out.splitlines():
        parts = re.split(r"(?<!\\):", line)
        if len(parts) < 3 or not parts[0]:
            continue
        ssid = parts[0].replace("\\:", ":")
        sig = int(parts[1] or 0)
        if ssid not in seen or sig > seen[ssid]["signal"]:
            seen[ssid] = {"ssid": ssid, "signal": sig, "secure": bool(parts[2] and parts[2] != "--")}
    return {"ok": True, "networks": sorted(seen.values(), key=lambda n: -n["signal"])}


def act_wifi_connect(body):
    """End the setup Wi-Fi and join a saved network now (this page will drop)."""
    name = str(body.get("name", ""))
    if name not in [n["name"] for n in home_networks()]:
        raise ValueError("unknown network")
    STATE["connect_after"] = name
    STATE["force_off"] = True
    STATE["button_until"] = 0
    tbconf.event(f"Switching from setup Wi-Fi to '{name}'")
    return {"ok": True}


ACTIONS = {"pair": act_pair, "pair/cancel": act_pair_cancel, "forget": act_forget,
           "config": act_config, "adapters": act_adapters, "check": act_check,
           "restart": act_restart, "wifi/off": act_wifi_off,
           "wifi/add": act_wifi_add, "wifi/remove": act_wifi_remove,
           "wifi/scan": act_wifi_scan, "wifi/connect": act_wifi_connect}


# ------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "teslabridge"

    def log_message(self, fmt, *args):
        pass

    def authorised(self):
        pw = tbconf.load().get("WEB_PASSWORD", "")
        if not pw:
            return True
        h = self.headers.get("Authorization", "")
        if h.startswith("Basic "):
            try:
                if base64.b64decode(h[6:]).decode().split(":", 1)[1] == pw:
                    return True
            except (ValueError, IndexError):
                pass
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="teslabridge"')
        self.end_headers()
        return False

    def reply(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        STATE["last_hit"] = time.monotonic()
        if not self.authorised():
            return
        if self.path in ("/", "/index.html") or not self.path.startswith("/api/"):
            try:
                return self.reply(200, open(INDEX, "rb").read(), "text/html; charset=utf-8")
            except OSError:
                return self.reply(500, b"index.html missing", "text/plain")
        if self.path == "/api/state":
            return self.reply(200, state())
        if self.path.startswith("/api/logs/"):
            return self.reply(200, {"text": logs(self.path.rsplit("/", 1)[-1])})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        STATE["last_hit"] = time.monotonic()
        if not self.authorised():
            return
        name = self.path[len("/api/"):] if self.path.startswith("/api/") else ""
        if name == "update":   # raw zip upload, not JSON
            try:
                n = int(self.headers.get("Content-Length", 0) or 0)
                if not 0 < n <= MAX_UPDATE:
                    return self.reply(400, {"error": "upload missing or too large"})
                return self.reply(200, act_update(self.rfile.read(n)))
            except (ValueError, OSError) as e:
                return self.reply(400, {"error": str(e)})
        fn = ACTIONS.get(name)
        if not fn:
            return self.reply(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
            self.reply(200, fn(body))
        except (ValueError, KeyError) as e:
            self.reply(400, {"error": str(e)})
        except dbus.DBusException as e:
            self.reply(500, {"error": e.get_dbus_message() or str(e)})


# ------------------------------------------------------------- setup Wi-Fi
def wifi_client_connected():
    _, out = sh("nmcli", "-t", "-f", "DEVICE,STATE,CONNECTION", "device")
    for line in out.splitlines():
        parts = line.split(":")
        if len(parts) >= 3 and parts[0] == "wlan0" and parts[1] == "connected" and parts[2] != AP_NAME:
            return True
    return False


def ap_profile(c):
    if sh("nmcli", "-t", "-f", "NAME", "connection", "show")[1].splitlines().count(AP_NAME):
        return
    sh("nmcli", "connection", "add", "type", "wifi", "ifname", "wlan0", "con-name", AP_NAME,
       "autoconnect", "no", "ssid", c.get("SETUP_WIFI_SSID", "teslabridge-setup"),
       "mode", "ap", "802-11-wireless.band", "a", "802-11-wireless.channel", "36",
       "ipv4.method", "shared", "ipv4.addresses", "10.42.0.1/24",
       "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", c.get("SETUP_WIFI_PASS", "teslabridge"))


def ensure_wlan():
    """The Pi 3's built-in Wi-Fi driver sometimes fails to come up at boot,
    leaving no wlan0 at all. Reloading the driver brings it back."""
    if os.path.exists("/sys/class/net/wlan0"):
        STATE["wlan_tries"] = 0
        return True
    tries = STATE.get("wlan_tries", 0)
    if tries >= 3:
        return False
    STATE["wlan_tries"] = tries + 1
    tbconf.event(f"No Wi-Fi interface; reloading the Wi-Fi driver (try {tries + 1}/3)")
    sh("rfkill", "unblock", "wifi")
    sh("modprobe", "-r", "brcmfmac")
    sh("modprobe", "brcmfmac")
    time.sleep(8)
    return os.path.exists("/sys/class/net/wlan0")


def ap_up(c):
    if not ensure_wlan():
        return False
    ap_profile(c)
    rc, out = sh("nmcli", "connection", "up", AP_NAME, timeout=40)
    if rc != 0:  # 5 GHz not available here (e.g. Pi 3B): use 2.4 GHz channel 6
        first = out.splitlines()[-1] if out else ""
        sh("nmcli", "connection", "modify", AP_NAME, "802-11-wireless.band", "bg", "802-11-wireless.channel", "6")
        rc, out = sh("nmcli", "connection", "up", AP_NAME, timeout=40)
        if rc != 0:
            msg = (out.splitlines()[-1] if out else "") or first or "unknown error"
            if STATE.get("ap_error") != msg:
                STATE["ap_error"] = msg
                tbconf.event("Setup Wi-Fi failed to start: " + msg)
    if rc == 0:
        STATE["ap_error"] = None
        tbconf.event(f"Setup Wi-Fi on: '{c.get('SETUP_WIFI_SSID')}', then open http://10.42.0.1")
    return rc == 0


def ap_down():
    sh("nmcli", "connection", "down", AP_NAME, timeout=30)
    tbconf.event("Setup Wi-Fi off")


def ap_loop():
    while True:
        try:
            c = tbconf.load()
            if uptime() > 25:
                ensure_wlan()
            now = time.monotonic()
            minutes = int(c.get("SETUP_WIFI_MINUTES", "10") or 0)
            unconfigured = not c.get("CAR")
            in_use = STATE["ap"] and now - STATE["last_hit"] < 300
            want = (unconfigured or (minutes and uptime() < minutes * 60)
                    or now < STATE["button_until"] or in_use)
            if STATE.pop("force_off", False):
                want = False
                STATE["boot_window_done"] = True
            if STATE.get("boot_window_done") and not unconfigured and now >= STATE["button_until"] and not in_use:
                want = False
            # Give a normal Wi-Fi network 30 s after boot to connect first,
            # and never take the radio away from one.
            if want and not STATE["ap"]:
                if (uptime() > 45 or unconfigured) and not wifi_client_connected():
                    STATE["ap"] = ap_up(c)
            elif not want and STATE["ap"]:
                ap_down()
                STATE["ap"] = False
            if not STATE["ap"] and STATE.get("connect_after"):
                name, STATE["connect_after"] = STATE["connect_after"], None
                sh("nmcli", "connection", "up", name, timeout=40)
        except Exception as e:  # never let the Wi-Fi manager die
            print(f"setup Wi-Fi: {e}", flush=True)
        time.sleep(5)


def button_loop():
    held = 0.0
    gpio = ""
    while True:
        try:
            g = tbconf.load().get("SETUP_BUTTON_GPIO", "").strip()
            if g != gpio:
                gpio = g
                if gpio:
                    sh("pinctrl", "set", gpio, "ip", "pu")
            if gpio:
                _, out = sh("pinctrl", "get", gpio, timeout=5)
                pressed = "| lo" in out
                held = held + 0.5 if pressed else 0.0
                if held >= 3 and time.monotonic() >= STATE["button_until"]:
                    STATE["button_until"] = time.monotonic() + 15 * 60
                    STATE["boot_window_done"] = False
                    tbconf.event("Setup button: setup Wi-Fi on for 15 minutes")
        except Exception as e:
            print(f"button: {e}", flush=True)
        time.sleep(0.5)


def main():
    auto_assign()
    threading.Thread(target=ap_loop, daemon=True).start()
    threading.Thread(target=button_loop, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", 80), Handler)
    print("Setup page on port 80", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
