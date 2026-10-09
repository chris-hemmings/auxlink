#!/usr/bin/env python3
"""Flash the XIAO with the firmware that came with this AuxLink.

Started by udev (99-auxlink-xiao.rules) when an RP2040 update drive
("RPI-RP2") appears: the XIAO plugged into one of the Pi's USB ports with
its B button held. Copies the bundled .uf2 onto it; the XIAO then restarts
by itself and can go back into the SMO.
    auxlink-xiao-flash.py sda1
"""
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, "/usr/local/lib/auxlink")
import auxconf  # noqa: E402

UF2 = "/usr/local/share/auxlink/auxlink-xiao.uf2"
VERSION_FILE = "/usr/local/share/auxlink/auxlink-xiao.version"
MNT = "/run/auxlink/xiao-flash"


def say(msg):
    print(msg, flush=True)
    auxconf.event(msg)


def main():
    dev = "/dev/" + os.path.basename(sys.argv[1])
    try:
        version = open(VERSION_FILE).read().strip()
    except OSError:
        version = "?"
    if not os.path.exists(UF2):
        say("XIAO update drive found, but no firmware file is installed")
        return 1
    os.makedirs(MNT, exist_ok=True)
    subprocess.run(["umount", MNT], capture_output=True)
    r = subprocess.run(["mount", "-t", "vfat", "-o", "sync", dev, MNT], capture_output=True, text=True)
    if r.returncode:
        say(f"XIAO update drive found, but it could not be opened: {r.stderr.strip()}")
        return 1
    try:
        # Only an RP2040's own update drive (an RP2350's would not run this firmware).
        try:
            info = open(os.path.join(MNT, "INFO_UF2.TXT")).read()
        except OSError:
            info = ""
        if "RP2" not in info or "RP2350" in info:
            say("A USB drive called RPI-RP2 appeared but it is not an RP2040; left alone")
            return 1
        say(f"XIAO in update mode: writing AuxLink XIAO firmware {version}")
        dest = os.path.join(MNT, "auxlink-xiao.uf2")
        try:
            with open(UF2, "rb") as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
                out.flush()
                os.fsync(out.fileno())
        except OSError:
            pass   # the XIAO restarts as the last block lands, often mid-close
    finally:
        time.sleep(1)
        subprocess.run(["umount", "-l", MNT], capture_output=True)
    say(f"XIAO firmware {version} written. Plug the XIAO back into the SMO "
        "(Android asks once: tick Always)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
