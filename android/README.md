# AuxLink

Sends the Screenmate's now-playing info (title, artist, album, length,
position, play state) over USB to the TeslAux XIAO, which forwards it to the
Pi, which shows it on the Tesla's screen over Bluetooth AVRCP.

Build: every push to main runs GitHub Actions, which builds a signed APK and
publishes it as a GitHub release - install/update it with Obtainium.
Full setup (Obtainium, permissions, USB) is in the main README, section 6.
