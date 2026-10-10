# AuxLink — the Pi side

The Pi part of AuxLink: music + wheel controls + track info into the car,
calls and contacts relayed from the phone, self-healing, starts at boot. And
**nothing is hard-coded**: devices are paired and settings changed from a web
page, and the Pi is only pairable when you open a pairing window.

## Install (Pi 4; wiring and full guide in the main README)
    unzip auxlink-pi-update-X.zip && cd pi
    sudo ./install.sh
    sudo reboot
Your existing /etc/auxlink.conf is kept (new settings are added), so the
car and phone stay paired. On a fresh Pi nothing is paired yet.

## Updating an installed Pi
    unzip -o auxlink-pi-update-X.zip && cd pi
    sudo ./update.sh
Copies the programs and service files and restarts the AuxLink
services. No internet or reboot needed, and the car and phone stay connected
(a call in progress is dropped).

Or without SSH: on the setup page, **Update** -> choose the zip (the release
zip, or GitHub's whole-repository download) -> **Install update**. The page
shows the output and reloads when done. Anyone who can open the page can do
this, so set a **Page password** if the Pi is on a shared network.

## Installing without SSH (first boot does it all)
1. Flash Raspberry Pi OS Lite (64-bit) with Imager: hostname, user, Wi-Fi
   country. For internet during install either plug in an **Ethernet cable**
   or put your **phone's hotspot** in Imager's Wi-Fi box (needed once).
2. Before ejecting, open the card's **bootfs** drive on the PC:
   - copy the whole **pi** folder onto it;
   - open **user-data** in Notepad and add at the end (if a `runcmd:` line
     already exists, add just the second line under it):
         runcmd:
           - [ systemd-run, --unit=auxlink-firstboot, bash, /boot/firmware/pi/firstboot.sh ]
3. Boot the Pi and wait ~30-40 minutes. It installs, builds the contacts
   server and reboots by itself. Progress: **bootfs/auxlink-install.log**
   (put the card back in the PC to read it if something seems stuck).
4. Join the Wi-Fi **AuxLink-setup** (password **auxlink-setup**) and open
   **http://10.42.0.1** to pair the car and phone.

## Choosing adapters (built-in Bluetooth or dongles)
The Pi's built-in Bluetooth is always available, alongside any USB dongles.
On the setup page, **Bluetooth adapters** lets you pick, for each side:
- **Built-in Bluetooth** or **USB dongle 1 / 2** for the car,
- the same, or **none**, for the phone.

**Recommended: two USB dongles, built-in unused.** The built-in chip has to
carry call audio over its serial link (sco-route-hci) and crashed mid-call
in testing (HCI "Hardware Error"), dropping the car until Bluetooth was
restarted. Built-in for the car + one dongle for the phone (the first-run
default) works for music but is not reliable for calls. Adapters not
assigned to anything are switched off. Changing a side's adapter clears
that side's pairing: pair it again afterwards. No reinstall or reboot needed.

### Which dongles
Any USB dongle Linux supports with *Bluetooth Classic* (BR/EDR) works:
music (A2DP) and call audio (SCO) both run over USB. Known good:
- **TP-Link UB500** (Realtek RTL8761BU) - what this was built and tested on.
  Two identical ones are fine: each side is chosen by its Bluetooth address.
  The warm-reboot hang these chips have is handled (bt-dongle-off, bt-usb-cycle).
- Other Realtek **RTL8761B/BU** dongles (many UGREEN/ASUS/Edimax "BT 5.0").
- CSR8510 A10 "BT 4.0" dongles work too, but cheap clones are hit and miss.

Avoid: dongles sold as "Windows only" or "driver CD required", most
"Bluetooth 5.3/5.4" dongles built on Barrot or Actions (ATS2851) chips
(little or no Linux support), LE-only dongles, and "Bluetooth audio
transmitter" boxes (they are not adapters). Use the black USB 2.0 ports
(USB 3.0 interferes with 2.4 GHz), ideally one dongle on a short extension.
The XIAO serial link uses the Pi's second UART (same pins) so that the
built-in Bluetooth can keep the main one.

## Opening the setup page
- **At home on your Wi-Fi:** http://auxlink.local
- **Anywhere else:** join the Wi-Fi **AuxLink-setup** (password
  **auxlink-setup**, change it on the page) and open **http://10.42.0.1**.
  The setup Wi-Fi is on:
  * whenever no car is paired,
  * for the first 10 minutes after boot (setting; 0 = off),
  * for 15 minutes after holding the setup button 3 s
    (optional push button between GPIO26 = pin 37 and GND = pin 34),
  and stays on while the page is in use. It never starts while the Pi is
  connected to a normal Wi-Fi network. Use it from the SMO's browser or any phone.

## Home Wi-Fi
The page's **Home Wi-Fi** section saves networks for the Pi to join by itself
(add by name + password, or tap **Scan nearby** and pick one; remove any time).
- While you're on the setup Wi-Fi, a new network is only *saved*: the radio
  can't do both, so the Pi joins it once setup Wi-Fi switches off (timer,
  "Finish setup", or **Join now**, which ends setup Wi-Fi straight away; reopen
  the page at http://auxlink.local on that network).
- When a saved network is in range at boot, the Pi joins it and the setup
  Wi-Fi stays off; in the car (no known network) the setup Wi-Fi rules apply.
- Passwords are kept by NetworkManager (root-only) and never shown on the page.

## Pairing
1. Tap **Pair a car** (or **Pair a phone**): a 2-minute window opens and only
   that side's Bluetooth becomes visible.
2. On the car/phone: Bluetooth -> add new device -> **AuxLink** /
   **AuxLink-phone** -> confirm the code on its screen. The Pi accepts it.
3. The page shows it as connected; everything restarts to use it.
First-time setup: with no car paired the car side is already open.
Outside a window, only the paired car and phone are accepted.

## Music source
Pick what plays into the car on the setup page's **Music source** card:
- **Wired: XIAO RP2040 (I²S)**: the default, as before. Track info and keys go
  over the XIAO's serial link (the now-playing app on the player).
- **Bluetooth (phone or player)**: choose an adapter (not the car's; it may
  share the phone side's), **Save**, then **Pair a music source** and pair the
  player with **AuxLink-music**. Track info, play state and the car's
  play/pause/skip keys go over Bluetooth, so no app is needed. It reconnects by
  itself.
- **USB-C (Pi as a USB sound card)**: plug the player into the Pi's USB-C
  port, where it appears as a USB sound card plus a serial link for the
  now-playing app (v1.0.5+). **Power the Pi externally for reliable use**
  (5 V on the GPIO pins or a USB-C power/data splitter), because the USB-C
  port is now a data port. Switching to it needs **one reboot** (the page
  says so).

## Page sections
Devices · Music source · Music check/fix · Services · Bluetooth adapters (which dongle is the
car side / phone side) · Settings (phone features, pause SMO for calls,
contacts sync, auto-pairable, resume delay, names, setup
Wi-Fi, page password, button GPIO) · Recent events · Logs.
