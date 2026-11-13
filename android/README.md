# AuxLink

Sends the music device's now-playing info (title, artist, album, length,
position, play state, album art) to the AuxLink Pi - over USB (through the
XIAO, or the Pi's USB-C port) or over Bluetooth - which shows it on the car's
screen over Bluetooth AVRCP.

Build: every push to main runs GitHub Actions, which builds a signed APK and
publishes it as a GitHub release - install/update it with Obtainium.
Full setup (Obtainium, permissions, USB) is in the main README, section 6.

## USB permission on head units that forget it (root adb)

Some head units (the Screenmate) ask for USB permission again at every boot,
even with "Always" ticked. If the head unit has root adb - on the Screenmate:
Developer options, **USB debugging** on and, on Screenmate OS 1.1.0b+,
**Allow rooted debugging** on - Phone Link grants itself the permission:

1. Open Phone Link once with the Pi plugged in. Android asks
   "Allow USB debugging?" for Phone Link's own key: tick **Always allow**, tap
   Allow. That's the only tap, ever.
2. From then on Phone Link connects to the head unit's own adb
   (127.0.0.1:5555), runs a small helper from its APK as root
   (`RootHelper`, through `app_process`), and gives itself USB access each
   time the Pi appears - no prompt. It then switches off its own plug-in
   prompt; if root adb ever stops working, the prompt comes back and
   everything works the normal way.
3. While it's there it also turns on notification access, turns off battery
   optimisation and grants the no-volume-warning permission, if any of
   those are off.

Without USB debugging (or without root) none of this runs and Phone Link
behaves exactly as before. Code: `RootAdb.kt`, `RootHelper.java`.
