# teslabridge

A Raspberry Pi 4 that sits between a **Tesla**, your **phone** and an
**Android music device** (for example a Screenmate/SMO head unit, an old phone
or a tablet):

- **Music** from the Android device plays in the car over Bluetooth, with
  track title, artist, album, progress, **album art** and the car's
  play/pause/skip buttons and steering-wheel controls working.
- **Calls** from your real phone are relayed through the Pi to the car (the
  car's mic and speakers), with **contacts, favourites and recent calls**
  copied to the car's screen.
- **Voice search** on the Android device can use the **car's microphone**
  (wired source only).
- Everything is set up from a **web page** on the Pi. Nothing is hard-coded,
  and it reconnects by itself after the car sleeps or the Pi reboots.

```
                 Bluetooth (Pi = "phone")              Bluetooth (Pi = "car kit")
   Tesla  <------------------------------>  Pi 4  <------------------------------>  your phone
                                             ^
                                             |  music in: one of
                                             |   - Wired: XIAO RP2040 (USB sound card for the
                                             |     Android device) -> I2S + serial to the Pi
                                             |   - Bluetooth: the Android device pairs with the Pi
                                             |   - USB-C: the Pi itself is a USB sound card
                                       Android music device
                                       (+ "SMO Now Playing" app)
```

What is in this repository:

| Folder / file | What it is |
|---|---|
| `tb2/` | Everything that runs on the Pi (installer, services, web setup page). See also `tb2/README.md`. |
| `teslabridge-v2-websetup.zip` | The `tb2` folder zipped, for copying to the Pi or uploading on the setup page. |
| `smo-nowplaying/` | Source of the **SMO Now Playing** Android app. Signed APKs are on the [Releases page](https://github.com/chris-hemmings/teslabridge/releases). |
| `firmware/source-xiao-keys-mic.uf2` | Ready-to-flash firmware for the **Seeed XIAO RP2040** (wired source). |
| `firmware/rp2040/` | Source for that firmware (Rust, based on [TeslAux](https://github.com/jbschooley/TeslAux), MIT). |

---

## 1. What you need

### Required
| Item | Notes |
|---|---|
| **Raspberry Pi 4** (2 GB or more) | Built and tested on a Pi 4. |
| **microSD card**, 16 GB or more | A good brand (Samsung/SanDisk). |
| **Power for the Pi in the car** | 5 V / 3 A. A good USB-C car charger or a 12 V to 5 V 3 A converter. Weak supplies cause Bluetooth drop-outs. For the USB-C music source the Pi must be powered through its GPIO pins or a splitter instead (see 5.3). |
| **2 × USB Bluetooth dongles** (recommended) | **TP-Link UB500** (Realtek RTL8761BU) is what this was built and tested on. Two identical ones are fine. One is for the car, one for your phone. See [Bluetooth dongles](#bluetooth-dongles). |
| **An Android music device** | Android 8.0 or newer. For the wired source it needs **USB host (OTG)**, which any head unit with a USB port has. |
| **A Tesla** | Any model with Bluetooth music and phone. |

### Needed for the wired music source (recommended)
| Item | Notes |
|---|---|
| **Seeed Studio XIAO RP2040** | The small board that the Android device sees as a USB sound card. |
| **USB-C cable** | From the Android device's USB port to the XIAO. It also powers the XIAO. |
| **6 jumper wires** (female-female) or solder | XIAO to the Pi's GPIO header. Keep the I2S wires short (under ~15 cm). |

### Optional
- **Push button** between GPIO26 (pin 37) and GND (pin 34). Hold it 3 s to
  turn the setup Wi-Fi on.
- **Ethernet cable** for the first install (or use your phone's hotspot).
- **Short USB extension lead** for one dongle, to keep the two dongles apart.

### Bluetooth dongles
Any USB dongle with Linux support for **Bluetooth Classic (BR/EDR)** works.
Known good:
- **TP-Link UB500** (RTL8761BU). Recommended.
- Other Realtek **RTL8761B/BU** "BT 5.0" dongles (many UGREEN, ASUS and Edimax models).
- CSR8510 A10 "BT 4.0" dongles (cheap clones are hit and miss).

Avoid:
- "Windows only" or "driver CD" dongles;
- most "BT 5.3/5.4" dongles on Barrot or Actions (ATS2851) chips;
- LE-only dongles;
- "Bluetooth audio transmitter" boxes.

Use the Pi's **black USB 2.0 ports**, because USB 3.0 interferes with
Bluetooth.

The Pi's **built-in Bluetooth** can be used as well. It works well for a
Bluetooth music source, or for the car side if you only have one dongle.
It is not reliable for **calls**: the built-in chip crashed mid-call in
testing.

---

## 2. Install the Pi

### 2.1 Flash the SD card
1. Install **Raspberry Pi Imager** on your PC.
2. Choose **Raspberry Pi 4** and **Raspberry Pi OS Lite (64-bit)**.
3. In the settings (cog / "Edit settings"):
   - **hostname:** `teslabridge`
   - **username and password:** your choice; this user owns the audio.
   - **Wi-Fi:** your home Wi-Fi, or your phone's hotspot. It needs internet
     once, for the install. Set the **Wi-Fi country**.
   - **Services:** enable **SSH**.
4. Write the card.

### 2.2 Install teslabridge

**Option A: over SSH (recommended).** Boot the Pi, then from your PC run
`ssh <user>@teslabridge.local`, followed by:

```bash
sudo apt update && sudo apt -y full-upgrade && sudo apt install -y git
git clone https://github.com/chris-hemmings/teslabridge.git
cd teslabridge/tb2
sudo ./install.sh                       # packages, services, I2S + serial setup (~5 min)
./extras/build-obexd-dummy.sh           # contacts/recent calls server for the car (~20 min)
sudo ./install.sh                       # again, so it picks up the contacts server
sudo ./extras/build-bluetoothd-cover.sh # album art in the car (~15-20 min)
sudo reboot
```

- Run `build-obexd-dummy.sh` **without** sudo. It asks for your password when
  it needs it.
- Both builds are optional. Without the first, there are no contacts or recent
  calls on the car's screen. Without the second, there is no album art.
  Everything else works.
- To undo the album-art build: `sudo ./extras/build-bluetoothd-cover.sh --undo`.

**Option B: without SSH (first boot does it).**
1. Before ejecting the card from your PC, open its **bootfs** drive.
2. Copy the whole **`tb2`** folder onto it. Unzip `teslabridge-v2-websetup.zip`
   from this repository to get it.
3. Open **`user-data`** in a text editor and add this at the end. If a
   `runcmd:` line already exists, add only the second line under it:
   ```yaml
   runcmd:
     - [ systemd-run, --unit=tb-firstboot, bash, /boot/firmware/tb2/firstboot.sh ]
   ```
4. Put the card in the Pi and boot it with internet (Ethernet or the Wi-Fi
   set in Imager). Wait **30–40 minutes**. It installs everything, including
   the contacts server, and reboots by itself.
5. The progress log is `bootfs/teslabridge-install.log`, readable on a PC.
6. Album art still needs `sudo tb2/extras/build-bluetoothd-cover.sh`,
   run once over SSH.

### 2.3 What the installer changes
- It installs PipeWire, WirePlumber, BlueZ, NetworkManager, Avahi, sox and a
  few Python modules.
- It adds the following to `/boot/firmware/config.txt`:
  - `dtparam=i2s=on`
  - `dtoverlay=xiao-i2s-in` (the XIAO's I2S input)
  - `enable_uart=1` and `core_freq=250` (a stable serial link while the
    built-in Bluetooth stays on)
- It creates `/etc/teslabridge.conf`. Settings are normally changed on the
  setup page.
- It turns on a hardware watchdog (the Pi reboots itself if it ever freezes)
  and caps the logs at 50 MB.

---

## 3. Wire the XIAO RP2040 (wired source only)

### 3.1 Flash the firmware
1. Download **`firmware/source-xiao-keys-mic.uf2`** from this repository.
2. Hold the XIAO's **B (BOOT)** button while plugging it into a PC. A drive
   called **RPI-RP2** appears.
3. Copy the `.uf2` file onto that drive. The XIAO restarts by itself, and
   its RGB LED lights up.

This firmware makes the XIAO appear to the Android device as:
- a **USB sound card** (48 kHz stereo speaker) whose audio goes to the Pi
  over I2S;
- a **USB microphone** carrying the car's cabin mic, for voice search;
- **USB media keys**, so the car's buttons control the player;
- a **data link** that the SMO Now Playing app uses for track info and album art.

### 3.2 Pinout

**Do not connect 5 V or 3.3 V between the boards.** The XIAO is powered by
the Android device's USB port. Connect only the 6 wires below.

| Signal | XIAO RP2040 pad | Pi 4 GPIO | Pi header pin |
|---|---|---|---|
| I2S LRCLK (word clock) | **D0** (GP26) | GPIO19 (PCM_FS) | **35** |
| I2S BCLK (bit clock) | **D2** (GP28) | GPIO18 (PCM_CLK) | **12** |
| I2S DATA | **D3** (GP29) | GPIO20 (PCM_DIN) | **38** |
| Serial XIAO → Pi | **D6** (TX, GP0) | GPIO15 (RXD) | **10** |
| Serial Pi → XIAO | **D7** (RX, GP1) | GPIO14 (TXD) | **8** |
| Ground | **GND** | GND | **6** (or 39, 34, ...) |

```
  XIAO RP2040 (USB-C at the top)             Pi 4 header (USB/Ethernet at the bottom)
        +-----[USB-C]-----+                        3V3  1  2  5V
  D0  --| 1            14 |-- 5V                        3  4  5V
  D1  --| 2  (unused)  13 |-- GND  --> pin 6            5  6  GND  <-- XIAO GND
  D2  --| 3            12 |-- 3V3  (unused)             7  8  GPIO14 TXD --> XIAO D7
  D3  --| 4            11 |-- D10                  GND  9 10  GPIO15 RXD <-- XIAO D6
  D4  --| 5            10 |-- D9               ...
  D5  --| 6             9 |-- D8                   GPIO18 PCM_CLK 12 <-- XIAO D2
  D6  --| 7             8 |-- D7               ...
        +-----------------+                    GPIO19 PCM_FS  35 <-- XIAO D0
                                               GPIO20 PCM_DIN 38 <-- XIAO D3
```

- **D1** is a "shield" pin. The firmware holds it low so that a quiet line
  sits between the two clocks. Leave it unconnected, or, if you build a
  ribbon or cable, run it alongside the clock wires so that it sits between
  BCLK and LRCLK.
- Keep the three I2S wires short and together. Long or loose I2S wires show
  up as crackles or a corrupted right channel.
- The serial link runs at 115200 baud 8N1 on the Pi's `/dev/serial0`.

### 3.3 Connect it
- Plug the XIAO's USB-C into the Android device's USB port, using an OTG
  adapter if needed.
- In the device's sound settings, the output should switch to the USB device
  (it shows as **TeslAux Bridge**). Android does this by itself when a USB sound
  card is plugged in.

---

## 4. First setup on the web page

1. After the reboot, the Pi turns on its own Wi-Fi, **`teslabridge-setup`**
   (password **`teslabridge`**). Join it from a phone or the Android device
   and open **http://10.42.0.1**.
   - At home on the same Wi-Fi as the Pi, use **http://teslabridge.local**.
   - The setup Wi-Fi is on whenever no car is paired, for 10 minutes after
     each boot, and for 15 minutes after holding the setup button. You can
     change the times and password on the page.
2. **Bluetooth adapters:** choose which adapter is the **car** side and which
   is the **phone** side. With two dongles, use one each and leave the
   built-in unused. Then tap **Save adapters**.
3. **Pair the car:** tap **Pair a car**. In the Tesla, go to **Bluetooth →
   Add new device** and choose **teslabridge**, then confirm the code. The Pi
   accepts it by itself.
   - Your real phone should **not** stay paired directly with the Tesla.
     Remove it from the car's Bluetooth list; it connects through the Pi
     instead.
4. **Pair your phone:** tap **Pair a phone**. On the phone, go to Bluetooth,
   add **teslabridge-phone** and confirm the code. Allow **contacts and call
   history** access when the phone asks. That is what fills the car's
   contacts and recent calls.
5. **Music source:** choose how music gets into the Pi (see section 5), then
   tap **Save music source**.
6. **Home Wi-Fi** (optional): add your home network so that you can open
   http://teslabridge.local and install updates from home.
7. **Settings:** a page password, whether to pause music for calls, contacts
   sync, and the Bluetooth names. The defaults are fine.
8. Use **Check audio** / **Check and fix** on the Music card any time the
   music is not coming through.

---

## 5. Music sources

Pick one on the setup page's **Music source** card. Calls and the car
connection are not affected when you switch.

### 5.1 Wired: XIAO RP2040 (I2S) (recommended)
- **Uses:** the XIAO wired as in section 3.
- **Sound:** the Android device plays into the XIAO, which passes it to the
  Pi over I2S, and the Pi sends it to the car.
- **Track info and album art:** from the **SMO Now Playing** app (section 6),
  over the XIAO's data link.
- **Car buttons:** reach the device as USB media keys.
- **Voice search:** the device's mic input is the **car's cabin mic** (the
  Tesla shows a call while it listens; its hang-up button ends it).

### 5.2 Bluetooth (phone or player)
No XIAO and no wiring.
1. Choose **Bluetooth**, then pick the **adapter** for it. It can't be the
   car's adapter; it can share the phone's, or use the built-in one.
2. Tap **Save music source**, then **Pair a music source**.
3. On the Android device, pair with the name in the banner,
   **teslabridge-music** (or **teslabridge-phone** if it shares the phone's
   adapter).

- **Track info, play state and car buttons:** go over Bluetooth, so the app
  is not needed.
- **Not supported:** album art and the car-mic voice search.
- **If it connects but is silent:**
  - restart playback on the device, or toggle its Bluetooth;
  - unplug any USB sound card (such as the XIAO) from it, because Android
    sends audio to USB ahead of Bluetooth;
  - check that **Media audio** is on for teslabridge-music in the device's
    Bluetooth settings.

### 5.3 USB-C (Pi as a USB sound card)
The Android device plugs straight into the **Pi's USB-C port**, and the Pi
shows up as a USB sound card (plus a data link and media keys).
- **The Pi must then be powered externally:** 5 V into GPIO **pin 2 or 4**
  plus **GND (pin 6)**, from a solid 5 V / 3 A supply, or through a **USB-C
  power/data splitter**. Its USB-C port is now a data port.
- After saving, **reboot once**; the page says when this is needed. Saving
  adds `dtoverlay=dwc2,dr_mode=peripheral` to `/boot/firmware/config.txt`.
- **Track info and album art:** from the SMO Now Playing app (v1.0.5 or
  newer), over the same cable.
- **Not supported yet:** the car-mic voice search.

---

## 6. The Android music device and the SMO Now Playing app

The app reads what is playing on the device and sends it to the Pi, which
shows it in the car:
- title, artist, album, length, position, play/pause state;
- album art, from any player that publishes artwork (Spotify, YouTube Music,
  YouTube, Poweramp, ...).

It is needed for the **wired** and **USB-C** sources. The Bluetooth source
does not need it.

### 6.1 Install with Obtainium (recommended: automatic updates)
[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates apps
straight from GitHub releases.

1. Install Obtainium on the Android device. Get the APK from its
   [GitHub releases](https://github.com/ImranR98/Obtainium/releases), or from
   F-Droid / IzzyOnDroid.
2. Open Obtainium, tap **Add App** and paste
   `https://github.com/chris-hemmings/teslabridge`. Leave the source as
   **GitHub** and tap **Add**.
3. Tap **Install**. Allow Obtainium to install apps when Android asks
   ("Install unknown apps").
4. New releases appear in Obtainium as updates. Each push to `main` builds
   and publishes a new signed APK (`v1.0.N`).
   - You can turn on background update checks in Obtainium's settings.
   - Optionally, under the app's **Additional options** in Obtainium, set
     the APK filter (regular expression) to `smo-nowplaying` so that it only
     ever picks the app.

### 6.2 Or install by hand
Download the latest `smo-nowplaying-v1.0.N.apk` from the
[Releases page](https://github.com/chris-hemmings/teslabridge/releases) on
the device and open it. To update, install the newer APK over the top; it is
signed with the same key.

### 6.3 Set the app up (once)
1. Open **SMO Now Playing** and tap **Open notification access settings**.
   Turn on **SMO Now Playing**. This is how Android lets it see what is
   playing; it does not read your notifications' content.
   - If the switch is **greyed out** ("restricted setting"), go to
     **Settings → Apps → SMO Now Playing → ⋮ (top right) → Allow restricted
     settings**, then try again. Android 13+ does this for apps installed
     outside the Play Store.
2. Plug in the XIAO (or connect the device to the Pi's USB-C). Android asks
   **"Open SMO Now Playing (USB) when this device is connected?"** Tick
   **Always** and tap **OK**.
   - This is the USB permission. Nothing visible opens on later plug-ins.
3. Go back to the app. Its status should show:
   - Notification access: **granted**
   - XIAO plugged in: **yes**
   - Data link open: **yes**
4. Recommended: **Settings → Apps → SMO Now Playing → Battery → Unrestricted**,
   so that Android never stops it in the background.
5. Play something. The car shows the track and, after a second or two, the
   album art.

The app runs in the background by itself. You don't need to keep it open.
If the car ever shows an old track, play/pause once; the app re-checks every
5 seconds which player is playing.

### 6.4 Other requirements for the device
- **Android 8.0 or newer.**
- **USB host / OTG** for the wired source (the XIAO).
- For the **USB-C source**, the device must be able to output audio to a USB
  sound card. Almost all Android 8+ devices can.
- The **Google app / Assistant** (or the head unit's own voice search) uses
  the USB mic automatically when the XIAO is plugged in.

---

## 7. Updating

- **The Pi:**
  - **From the web page:** go to **Update**, choose `teslabridge-v2-websetup.zip`
    (or GitHub's "Download ZIP" of this repository) and tap **Install update**.
    No reboot is needed, and the car and phone stay connected.
  - **Over SSH:** `cd teslabridge && git pull && cd tb2 && sudo ./update.sh`.
- **The app:** Obtainium (or install the newer APK).
- **The XIAO:** only when `firmware/source-xiao-keys-mic.uf2` changes. Flash it
  again with BOOT held, as in 3.1.

---

## 8. Troubleshooting

| Problem | Try |
|---|---|
| Can't find the setup page | Join `teslabridge-setup` and open http://10.42.0.1. If the setup Wi-Fi is off, hold the setup button 3 s, or reboot (it is on for 10 minutes after boot). |
| No music in the car | Setup page → Music → **Check audio**, then **Check and fix**. Check that the car's media source is **Bluetooth / teslabridge**. |
| Silent or only one side | **Check and fix** recreates the audio path. With the wired source, check the three I2S wires and their ground. |
| No track info (wired) | App status: notification access granted, XIAO plugged in, data link open. Re-plug the XIAO. Check the serial wires (D6 → pin 10, D7 ← pin 8). |
| No album art | The album-art bluetoothd must be built (`build-bluetoothd-cover.sh`). Test with `sudo cover-test.sh` on the Pi, then turn the car's Bluetooth off and on once. |
| No contacts / recent calls in the car | Build the contacts server (2.2), allow contacts and call history on the phone, then on the setup page restart the services. |
| Car doesn't reconnect | It reconnects by itself when it wakes. If not, open **Recent events / Logs** on the setup page. |
| Logs over SSH | `journalctl -u teslabridge-keys -u hfp-relay -u tb-pairing -f` and `journalctl --user -u tesla-audio -f` |

---

## 9. Building from source (optional)

**XIAO firmware**: Rust; the pinned toolchain installs itself.
```bash
cd firmware/rp2040
cargo build --release --bin source --features rp2040-zero,smo-mic,ultra-low
# convert to UF2, for example with elf2uf2-rs:
cargo install elf2uf2-rs
elf2uf2-rs target/thumbv6m-none-eabi/release/source source-xiao-keys-mic.uf2
```
`smo-mic` includes `media-keys`, and `ultra-low` includes `clock-steered`,
which is the build the pinout above is for. `rp2040-zero` selects the
RGB-LED status code that the XIAO also uses.

**Android app**: open `smo-nowplaying/` in Android Studio, or run
`gradle assembleRelease`. The GitHub Actions workflow
(`.github/workflows/release.yml`) builds and signs it on every push to `main`
and publishes the release that Obtainium follows. It needs the
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` repository secrets.

---

## Credits
The XIAO firmware is based on [TeslAux](https://github.com/jbschooley/TeslAux)
by jbschooley (MIT, see `firmware/rp2040/LICENSE-TeslAux`).
