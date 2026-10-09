# teslabridge — release 2 (web setup)

Same core as release 1 (SMO music + wheel controls + track info into the car,
calls and contacts relayed from the phone, self-healing, starts at boot), but
**nothing is hard-coded**: devices are paired and settings changed from a web
page, and the Pi is only pairable when you open a pairing window.

## Install (Pi 4, same wiring as release 1)
    unzip teslabridge-v2-websetup.zip && cd tb2
    sudo ./install.sh
    sudo reboot
Your existing /etc/teslabridge.conf is kept (new settings are added), so the
car and Oppo stay paired. On a fresh Pi nothing is paired yet.

## Installing without SSH (first boot does it all)
1. Flash Raspberry Pi OS Lite (64-bit) with Imager: hostname, user, Wi-Fi
   country. For internet during install either plug in an **Ethernet cable**
   or put your **phone's hotspot** in Imager's Wi-Fi box (needed once).
2. Before ejecting, open the card's **bootfs** drive on the PC:
   - copy the whole **tb2** folder onto it;
   - open **user-data** in Notepad and add at the end (if a `runcmd:` line
     already exists, add just the second line under it):
         runcmd:
           - [ systemd-run, --unit=tb-firstboot, bash, /boot/firmware/tb2/firstboot.sh ]
3. Boot the Pi and wait ~30-40 minutes. It installs, builds the contacts
   server and reboots by itself. Progress: **bootfs/teslabridge-install.log**
   (put the card back in the PC to read it if something seems stuck).
4. Join the Wi-Fi **teslabridge-setup** (password **teslabridge**) and open
   **http://10.42.0.1** to pair the car and phone.

## Choosing adapters (built-in Bluetooth or dongles)
The Pi's built-in Bluetooth is always available, alongside any USB dongles.
On the setup page, **Bluetooth adapters** lets you pick, for each side:
- **Built-in Bluetooth** or **USB dongle 1 / 2** for the car,
- the same, or **none**, for the phone.

Examples: built-in for the car + one dongle for the phone (the default on
first run, and the recommendation on a Pi 4: the busiest link stays off
USB), or two dongles with the built-in unused. Adapters not assigned to
anything are switched off. Changing a side's adapter clears that side's
pairing: pair it again afterwards. No reinstall or reboot needed.
The XIAO serial link uses the Pi's second UART (same pins) so that the
built-in Bluetooth can keep the main one.

## Opening the setup page
- **At home on your Wi-Fi:** http://teslabridge.local
- **Anywhere else:** join the Wi-Fi **teslabridge-setup** (password
  **teslabridge**, change it on the page) and open **http://10.42.0.1**.
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
  the page at http://teslabridge.local on that network).
- When a saved network is in range at boot, the Pi joins it and the setup
  Wi-Fi stays off; in the car (no known network) the setup Wi-Fi rules apply.
- Passwords are kept by NetworkManager (root-only) and never shown on the page.

## Pairing
1. Tap **Pair a car** (or **Pair a phone**): a 2-minute window opens and only
   that side's Bluetooth becomes visible.
2. On the car/phone: Bluetooth -> add new device -> **teslabridge** /
   **teslabridge-phone** -> confirm the code on its screen. The Pi accepts it.
3. The page shows it as connected; everything restarts to use it.
First-time setup: with no car paired the car side is already open.
Outside a window, only the paired car and phone are accepted.

## Page sections
Devices · Music check/fix · Services · Bluetooth adapters (which dongle is the
car side / phone side) · Settings (phone features, pause SMO for calls,
contacts sync, auto-pairable like release 1, resume delay, names, setup
Wi-Fi, page password, button GPIO) · Recent events · Logs.

## Switching between releases
- **To release 1:** `sudo systemctl disable --now tb-web`, then run release 1's
  `sudo ./install.sh` and reboot. (Release 1 ignores the extra settings; set
  CAR/PHONE in /etc/teslabridge.conf if they were cleared.)
- **To release 2:** run this `install.sh` again and reboot.
