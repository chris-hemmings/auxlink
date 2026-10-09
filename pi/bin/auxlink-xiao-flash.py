#!/usr/bin/env python3
"""Write a firmware file to the XIAO while it is in update mode on the Pi.

The XIAO, plugged into one of the Pi's USB ports with its B button held,
shows up as a USB drive called RPI-RP2. The setup page's XIAO card runs this
with the uploaded .uf2 (or the firmware that came with this AuxLink):
    auxlink-xiao-flash.py FILE.uf2 [NAME]
Exit 0 once written; the XIAO restarts by itself.
"""
import os
import shutil
import struct
import subprocess
import sys
import time

sys.path.insert(0, "/usr/local/lib/auxlink")
import auxconf  # noqa: E402

DRIVE = "/dev/disk/by-label/RPI-RP2"
MNT = "/run/auxlink/xiao-flash"
RP2040_FAMILY = 0xE48BFF56


def check_uf2(path):
    """A UF2 file for the RP2040 (not some other board's)."""
    with open(path, "rb") as f:
        block = f.read(512)
    if len(block) < 512:
        raise ValueError("not a UF2 firmware file (too short)")
    magic0, magic1, flags = struct.unpack_from("<III", block, 0)
    family = struct.unpack_from("<I", block, 28)[0]
    if magic0 != 0x0A324655 or magic1 != 0x9E5D5157:
        raise ValueError("not a UF2 firmware file")
    if flags & 0x2000 and family != RP2040_FAMILY:
        raise ValueError("this UF2 is for a different chip, not the RP2040")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 else os.path.basename(src)
    try:
        check_uf2(src)
    except (OSError, ValueError) as e:
        print(f"Not flashed: {e}")
        return 1
    if not os.path.exists(DRIVE):
        print("No XIAO in update mode: unplug it, hold B and plug it into a USB port on the Pi")
        return 1
    os.makedirs(MNT, exist_ok=True)
    subprocess.run(["umount", MNT], capture_output=True)
    r = subprocess.run(["mount", "-t", "vfat", "-o", "sync", DRIVE, MNT], capture_output=True, text=True)
    if r.returncode:
        print(f"Could not open the XIAO's update drive: {r.stderr.strip()}")
        return 1
    try:
        try:
            info = open(os.path.join(MNT, "INFO_UF2.TXT")).read()
        except OSError:
            info = ""
        if "RP2" not in info or "RP2350" in info:
            print("That RPI-RP2 drive is not an RP2040's update drive; left alone")
            return 1
        print(f"Writing {name} to the XIAO...", flush=True)
        try:
            with open(src, "rb") as f, open(os.path.join(MNT, "firmware.uf2"), "wb") as out:
                shutil.copyfileobj(f, out)
                out.flush()
                os.fsync(out.fileno())
        except OSError:
            pass    # the XIAO restarts as the last block lands, often mid-close
    finally:
        time.sleep(1)
        subprocess.run(["umount", "-l", MNT], capture_output=True)
    # The drive goes away once the XIAO has taken the firmware and restarted.
    for _ in range(20):
        if not os.path.exists(DRIVE):
            break
        time.sleep(0.5)
    if os.path.exists(DRIVE):
        print("Written, but the XIAO did not restart: unplug it and try again")
        return 1
    auxconf.event(f"XIAO firmware written: {name}")
    print(f"Done: {name} written and the XIAO restarted. Plug it back into the SMO "
          "(Android asks once: tick Always).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
