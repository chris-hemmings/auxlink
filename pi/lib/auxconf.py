"""Read and write /etc/auxlink.conf (KEY=value, comments kept)."""
import os
import re
import subprocess
import tempfile

PATH = "/etc/auxlink.conf"
SYSTEM_UNITS = ["auxlink-reconnect", "hfp-relay", "auxlink-media", "auxlink-pairing", "auxlink-cover", "auxlink-usb-gadget"]
USER_UNITS = ["auxlink-audio", "pbap-sync"]


def load(path=PATH):
    conf = {}
    try:
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                conf[k.strip()] = v.strip().strip('"')
    except FileNotFoundError:
        pass
    return conf


def save(updates, path=PATH):
    """Change or add keys, keeping every other line and comment as it was."""
    lines = open(path).read().splitlines() if os.path.exists(path) else []
    done = set()
    def fmt(k, v):
        v = str(v)
        # Quote anything with spaces etc. so bash can still "source" the file.
        return f'{k}="{v}"' if re.search(r"[^A-Za-z0-9_:./@%+,-]", v) else f"{k}={v}"

    for i, line in enumerate(lines):
        s = line.strip()
        if s and not s.startswith("#") and "=" in s:
            k = s.split("=", 1)[0].strip()
            if k in updates:
                lines[i] = fmt(k, updates[k])
                done.add(k)
    for k, v in updates.items():
        if k not in done:
            lines.append(fmt(k, v))
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
    with os.fdopen(fd, "w") as f:
        f.write("\n".join(lines) + "\n")
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)


def user_env(user):
    import pwd
    uid = pwd.getpwnam(user).pw_uid
    return {"XDG_RUNTIME_DIR": f"/run/user/{uid}", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin"}


def run_as_user(user, args, **kw):
    try:
        return subprocess.run(["/usr/sbin/runuser", "-u", user, "--"] + args,
                              env=user_env(user), capture_output=True, text=True, **kw)
    except (OSError, KeyError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(args, 1, "", str(e))


def restart_all(delay=1, skip=()):
    """Restart every auxlink service in the background (so a caller that
    is itself one of them can finish what it is doing first)."""
    user = load().get("AUDIO_USER", "chris")
    # Never the USB-C gadget: restarting it unplugs the music device (it
    # pauses, and the app loses its link). Choosing the source restarts it.
    sys_units = " ".join(u for u in SYSTEM_UNITS if u not in skip and u != "auxlink-usb-gadget")
    env = user_env(user)
    cmd = (f"sleep {delay}; systemctl restart {sys_units}; "
           f"/usr/sbin/runuser -u {user} -- env XDG_RUNTIME_DIR={env['XDG_RUNTIME_DIR']} "
           f"systemctl --user restart {' '.join(USER_UNITS)}")
    subprocess.Popen(["sh", "-c", cmd], start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def restart_user(units, delay=1):
    user = load().get("AUDIO_USER", "chris")
    env = user_env(user)
    cmd = (f"sleep {delay}; /usr/sbin/runuser -u {user} -- env XDG_RUNTIME_DIR={env['XDG_RUNTIME_DIR']} "
           f"systemctl --user restart {' '.join(units)}")
    subprocess.Popen(["sh", "-c", cmd], start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


EVENTS = "/run/auxlink/events.log"


def event(msg):
    import time
    os.makedirs(os.path.dirname(EVENTS), exist_ok=True)
    with open(EVENTS, "a") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")
