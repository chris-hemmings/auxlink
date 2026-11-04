#!/usr/bin/env python3
"""AuxLink debug log (DEBUG_LOG=1): record everything, to download later.

Problems that a restart clears (Phone Link "can't reach the Pi", no
now-playing, the setup page not answering while Wi-Fi is up) are gone by the
time anyone can look. So this writes, to /var/log/auxlink-debug (kept across
reboots, capped at DEBUG_MAX_MB):

  journal-*.log   the whole system journal, live: every AuxLink service,
                  bluetoothd, NetworkManager, the kernel (USB-C, Wi-Fi,
                  under-voltage), PipeWire and the user services, restarts
                  and crashes
  bluez-*.log     BlueZ's D-Bus signals: each Bluetooth connect/disconnect,
                  audio transport state, adapter changes, with timestamps
  events-*.log    state checks, a line whenever something changes: USB-C to
                  the SMO, Phone Link's last word, Bluetooth devices, Wi-Fi,
                  IPs, services, power/temperature, the setup page's health
  snapshot-*.log  the full state every 5 minutes

When the setup page stops answering, or Phone Link goes quiet, the stuck
services are asked (SIGUSR1) to print every thread's stack to the journal:
where each one is blocked. The setup page bundles it all, with this and the
previous boot's journal ("Download debug logs").

  auxlink-debug.py            run the collector (the auxlink-debug service)
  auxlink-debug.py snapshot   print the current state once
"""
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, "/usr/local/lib/auxlink")
import auxconf  # noqa: E402

DIR = "/var/log/auxlink-debug"
APP_SEEN = "/run/auxlink/app-seen"     # auxlink-media touches this on each Phone Link line
CONF = auxconf.load()
USER = CONF.get("AUDIO_USER", "chris")
SYSTEM_UNITS = auxconf.SYSTEM_UNITS + ["auxlink-web", "bluetooth", "NetworkManager", "dbus"]
USER_UNITS = auxconf.USER_UNITS + ["pipewire", "wireplumber", "pipewire-pulse"]
FAST = 1          # s: USB-C state and Phone Link age
SLOW = 10         # s: everything else
FULL = 300        # s: full snapshot
STACKS_EVERY = 600


def now():
    t = time.time()
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t)) + f".{int(t % 1 * 1000):03d}"


class RotLog:
    """A text log that rolls over at max_mb: name-0.log is the newest."""

    def __init__(self, name, max_mb, keep):
        self.name, self.max, self.keep = name, int(max_mb * 1024 * 1024), keep
        self.lock = threading.Lock()
        self.f = None
        self.open()

    def path(self, i):
        return os.path.join(DIR, f"{self.name}-{i}.log")

    def open(self):
        self.f = open(self.path(0), "a", buffering=1, encoding="utf-8", errors="replace")

    def write(self, line, stamp=True):
        with self.lock:
            self.f.write((f"{now()} " if stamp else "") + line.rstrip("\n") + "\n")
            if self.f.tell() > self.max:
                self.f.close()
                for i in range(self.keep - 1, 0, -1):
                    if os.path.exists(self.path(i - 1)):
                        os.replace(self.path(i - 1), self.path(i))
                self.open()


def run(cmd, timeout=5, user=False):
    """Output of a command ("" on any failure), never hanging the collector."""
    if user:
        try:
            import pwd
            uid = pwd.getpwnam(USER).pw_uid
        except KeyError:
            return ""
        cmd = ["runuser", "-u", USER, "--", "env", f"XDG_RUNTIME_DIR=/run/user/{uid}"] + cmd
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return f"<{type(e).__name__}>"


def read(path):
    try:
        return open(path).read().strip()
    except OSError:
        return ""


# ---------------------------------------------------------------- state
def usb_state(full=True):
    """full=False: the cheap part, checked every FAST s."""
    s = {}
    for u in glob.glob("/sys/class/udc/*"):
        s["usb.udc"] = os.path.basename(u)
        s["usb.state"] = read(f"{u}/state")
    try:
        age = time.time() - os.stat(APP_SEEN).st_mtime
        s["phonelink"] = "talking" if age < 15 else ("quiet <60s" if age < 60 else "QUIET")
        s["phonelink.age_s"] = int(age)          # snapshot only (changes every second)
    except OSError:
        s["phonelink"] = "never heard (this boot)"
    if not full:
        return s
    s["usb.functions"] = " ".join(sorted(os.path.basename(f) for f in
                                         glob.glob("/sys/kernel/config/usb_gadget/auxlink/configs/c.1/*.usb0")))
    s["usb.sound_card"] = "yes" if any(x in read("/proc/asound/cards") for x in ("UAC1", "UAC2")) else "no"
    holders = []
    for fd in glob.glob("/proc/[0-9]*/fd/*"):
        try:
            if os.readlink(fd) == "/dev/ttyGS0":
                holders.append(read(f"/proc/{fd.split('/')[2]}/comm"))
        except OSError:
            pass
    s["usb.serial_open_by"] = ",".join(sorted(set(holders))) or "nobody"
    return s


def bluez_state():
    s = {}
    t0 = time.monotonic()
    out = run(["busctl", "--json=short", "--timeout=5", "call", "org.bluez", "/",
               "org.freedesktop.DBus.ObjectManager", "GetManagedObjects"], timeout=8)
    s["bluez.answer_ms"] = int((time.monotonic() - t0) * 1000)   # snapshot only
    try:
        objs = json.loads(out)["data"][0]
    except (ValueError, KeyError, IndexError, TypeError):
        s["bluez"] = "NOT ANSWERING" if out.startswith("<") or not out else "unreadable reply"
        return s
    s["bluez"] = "ok"

    def v(props, k):
        x = props.get(k, {})
        return x.get("data") if isinstance(x, dict) else x
    for path, ifaces in objs.items():
        a = ifaces.get("org.bluez.Adapter1")
        if a:
            s[f"bt.adapter.{v(a, 'Address')}"] = (f"powered={v(a, 'Powered')} discoverable={v(a, 'Discoverable')} "
                                                  f"pairable={v(a, 'Pairable')} name={v(a, 'Alias')}")
        d = ifaces.get("org.bluez.Device1")
        if d and (v(d, "Connected") or v(d, "Paired")):
            s[f"bt.dev.{path.split('/')[3]}.{v(d, 'Address')}"] = (
                f"{'CONNECTED' if v(d, 'Connected') else 'off'} {v(d, 'Alias')}")
        t = ifaces.get("org.bluez.MediaTransport1")
        if t:
            s[f"bt.transport.{'/'.join(path.split('/')[3:])}"] = f"{v(t, 'State')} codec={v(t, 'Codec')}"
    return s


def net_state():
    s = {}
    for line in run(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device"]).splitlines():
        p = line.split(":")
        if len(p) >= 4 and p[1] in ("wifi", "ethernet"):
            s[f"net.{p[0]}"] = f"{p[2]} {':'.join(p[3:])}"
    s["net.ip"] = run(["ip", "-4", "-br", "addr"]).replace("\n", " | ")
    sig = [ln for ln in run(["nmcli", "-t", "-f", "ACTIVE,SSID,SIGNAL", "device", "wifi", "list",
                             "--rescan", "no"]).splitlines() if ln.startswith("yes:")]
    if sig:
        s["net.signal"] = sig[0][4:]                 # snapshot only
    s["net.radio"] = run(["nmcli", "radio", "wifi"])
    return s


def services_state():
    s = {}
    for u in SYSTEM_UNITS:
        out = run(["systemctl", "show", u, "-p", "ActiveState,SubState,NRestarts"])
        d = dict(ln.split("=", 1) for ln in out.splitlines() if "=" in ln)
        s[f"svc.{u}"] = f"{d.get('ActiveState', '?')}/{d.get('SubState', '?')} restarts={d.get('NRestarts', '?')}"
    out = run(["systemctl", "--user", "show"] + [f"{u}.service" for u in USER_UNITS]
              + ["-p", "Id,ActiveState,SubState,NRestarts"], user=True)
    for block in out.split("\n\n"):
        d = dict(ln.split("=", 1) for ln in block.splitlines() if "=" in ln)
        if d.get("Id"):
            s[f"svc.user.{d['Id'].replace('.service', '')}"] = (
                f"{d.get('ActiveState', '?')}/{d.get('SubState', '?')} restarts={d.get('NRestarts', '?')}")
    return s


def power_state():
    s = {}
    th = run(["vcgencmd", "get_throttled"])
    if th:
        s["power.throttled"] = th            # 0x0 = fine; bit 0 under-voltage now, bit 16 since boot
    t = read("/sys/class/thermal/thermal_zone0/temp")
    if t.isdigit():
        s["power.temp_c"] = int(t) // 1000   # snapshot only
    s["sys.load"] = read("/proc/loadavg").split(" ")[0]          # snapshot only
    mem = dict(ln.split(":", 1) for ln in read("/proc/meminfo").splitlines() if ":" in ln)
    s["sys.mem_free_mb"] = int(mem.get("MemAvailable", "0 kB").split()[0]) // 1024   # snapshot only
    du = shutil.disk_usage("/")
    s["sys.disk_free_mb"] = du.free // (1024 * 1024)            # snapshot only
    return s


def audio_state():
    s = {}
    for kind in ("sinks", "sources"):
        for line in run(["pactl", "list", kind, "short"], user=True).splitlines():
            p = line.split("\t")
            if len(p) >= 5 and not p[1].endswith(".monitor"):
                s[f"pw.{kind[:-1]}.{p[1]}"] = p[-1]
    return s


def web_state():
    """Fetch the setup page from here, as a browser would."""
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen("http://127.0.0.1/", timeout=5) as r:
            code = r.status
        res = f"ok {code}"
    except urllib.error.HTTPError as e:
        res = f"ok {e.code}"                 # answering (a login page or 401 is fine)
    except Exception as e:                   # noqa: BLE001
        res = f"NOT ANSWERING ({type(e).__name__}: {e})"
    return {"web": res, "web.answer_ms": int((time.monotonic() - t0) * 1000)}


VOLATILE = ("phonelink.age_s", "bluez.answer_ms", "net.signal", "power.temp_c", "sys.load",
            "sys.mem_free_mb", "sys.disk_free_mb", "web.answer_ms")


def snapshot():
    s = {}
    for f in (usb_state, bluez_state, net_state, services_state, power_state, audio_state, web_state):
        try:
            s.update(f())
        except Exception as e:               # noqa: BLE001
            s[f"error.{f.__name__}"] = repr(e)
    return s


# ---------------------------------------------------------------- stacks
def pid_of(unit):
    out = run(["systemctl", "show", unit, "-p", "MainPID", "--value"])
    return int(out) if out.isdigit() and out != "0" else None


LAST_STACKS = {}


def dump_stacks(unit, why, ev):
    """The unit's Python prints every thread's stack to its journal (it set
    faulthandler on SIGUSR1). Once per STACKS_EVERY a unit."""
    if time.monotonic() - LAST_STACKS.get(unit, -1e9) < STACKS_EVERY:
        return
    LAST_STACKS[unit] = time.monotonic()
    pid = pid_of(unit)
    if not pid:
        ev.write(f"STACKS {unit}: not running ({why})")
        return
    threads = read(f"/proc/{pid}/status")
    threads = next((ln.split()[1] for ln in threads.splitlines() if ln.startswith("Threads:")), "?")
    fds = len(os.listdir(f"/proc/{pid}/fd")) if os.path.isdir(f"/proc/{pid}/fd") else "?"
    ev.write(f"STACKS {unit} pid {pid} ({why}): threads={threads} open_fds={fds}; "
             f"listening :80 -> {run(['sh', '-c', 'ss -ltnp | grep -w :80 || echo nobody'])}")
    try:
        os.kill(pid, signal.SIGUSR1)
        ev.write(f"STACKS {unit}: asked it to print its threads to the journal (look for 'Thread 0x')")
    except OSError as e:
        ev.write(f"STACKS {unit}: {e}")


# ---------------------------------------------------------------- runners
def follow(cmd, log):
    """Copy a command's output into a log, restarting it if it ends."""
    while True:
        try:
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, errors="replace", bufsize=1)
            for line in p.stdout:
                log.write(line, stamp=False if cmd[0] == "journalctl" else True)
            p.wait()
        except OSError as e:
            log.write(f"[debug] {cmd[0]} failed: {e}")
        time.sleep(5)


def prune(max_mb):
    """Keep the directory under max_mb: the oldest rotated files go first."""
    files = [f for f in glob.glob(os.path.join(DIR, "*")) if os.path.isfile(f)]
    total = sum(os.path.getsize(f) for f in files)
    for f in sorted(files, key=os.path.getmtime):
        if total <= max_mb * 1024 * 1024:
            break
        if f.endswith("-0.log"):
            continue                          # never the live files
        total -= os.path.getsize(f)
        os.remove(f)


def main():
    if CONF.get("DEBUG_LOG", "0") != "1":
        print("DEBUG_LOG is off in /etc/auxlink.conf: not logging", flush=True)
        while True:                           # stay up (no restart loop); re-check now and then
            time.sleep(60)
            if auxconf.load().get("DEBUG_LOG", "0") == "1":
                os.execv(sys.executable, [sys.executable] + sys.argv)
    os.makedirs(DIR, exist_ok=True)
    max_mb = int(CONF.get("DEBUG_MAX_MB", "200") or 200)
    free = shutil.disk_usage(DIR).free // (1024 * 1024)
    if free < max_mb + 300:                   # never fill the card
        max_mb = max(30, free - 300)
    journal = RotLog("journal", max_mb * 0.55 / 8, 8)
    bluez = RotLog("bluez", max_mb * 0.15 / 4, 4)
    ev = RotLog("events", max_mb * 0.15 / 4, 4)
    snap = RotLog("snapshot", max_mb * 0.15 / 4, 4)
    ev.write(f"=== auxlink-debug started: version {read('/usr/local/share/auxlink/VERSION')}, "
             f"boot {read('/proc/sys/kernel/random/boot_id')}, cap {max_mb} MB ===")

    threading.Thread(target=follow, daemon=True, args=(
        ["journalctl", "-f", "-n", "0", "-o", "short-precise", "--no-hostname"], journal)).start()
    threading.Thread(target=follow, daemon=True, args=(
        ["dbus-monitor", "--system",
         "type='signal',sender='org.bluez',interface='org.freedesktop.DBus.Properties'",
         "type='signal',sender='org.bluez',interface='org.freedesktop.DBus.ObjectManager'"], bluez)).start()

    last = {}
    next_slow = next_full = 0.0
    web_bad = 0
    while True:
        t = time.monotonic()
        if t >= next_slow:
            # A fresh full state, so something gone (a device that dropped)
            # shows up as gone.
            next_slow = t + SLOW
            if auxconf.load().get("DEBUG_LOG", "0") != "1":
                ev.write("=== DEBUG_LOG switched off: stopping the recording ===")
                os.execv(sys.executable, [sys.executable] + sys.argv)
            full = snapshot()
            cur = dict(full)
            web_bad = web_bad + 1 if full.get("web", "").startswith("NOT") else 0
            if web_bad >= 3:
                dump_stacks("auxlink-web", "the setup page isn't answering", ev)
            if full.get("phonelink") == "QUIET" and full.get("usb.state") == "configured":
                dump_stacks("auxlink-media", "Phone Link is quiet", ev)
            if full.get("bluez") == "NOT ANSWERING" and last.get("bluez") != "NOT ANSWERING":
                ev.write("BlueZ isn't answering D-Bus: anything that asks it (setup page, media) can hang")
            if t >= next_full:
                next_full = t + FULL
                snap.write("--- snapshot ---")
                for k in sorted(full):
                    snap.write(f"  {k} = {full[k]}", stamp=False)
                prune(max_mb)
        else:
            cur = dict(last)
            cur.update(usb_state(full=False))
        for k in sorted(set(cur) | set(last)):
            if k in VOLATILE:
                continue
            if cur.get(k) != last.get(k):
                ev.write(f"{k}: {last.get(k, '(none)')} -> {cur.get(k, '(gone)')}")
        last = cur
        time.sleep(FAST)


if __name__ == "__main__":
    if sys.argv[1:] == ["snapshot"]:
        for k, val in sorted(snapshot().items()):
            print(f"{k} = {val}")
    else:
        main()
