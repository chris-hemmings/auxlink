# AuxLink — outstanding work

## Album art for Bluetooth-sourced media (phone media; also BT music source)

**Status:** parked, needs doing.

**Symptom:** when the phone's own media plays (Source = Phone/Auto, or the "both"
mix), the car shows the correct **track text** (title/artist/album) but **no
album art / thumbnail**. Cover art to the car works fine when the source is
**USB-C or the XIAO** (the SMO app pushes the JPEG over the link).

**What works today:**
- Serving art *to the car* (Pi → car). The SMO app sends a base64 JPEG over the
  app link → `Player.smo_art()` stores it → `auxlink-cover` serves it → the Pi's
  registered AVRCP player advertises it to the car via `bluez:ImgHandle` (the
  cover-art bluetoothd patch, `pi/extras/bluez-cover-art.patch`). Confirmed
  working over USB-C / XIAO.

**What's missing:** reading art *from* a Bluetooth device (phone → Pi), i.e. the
controller side of AVRCP 1.6 cover art.
- The phone **does** advertise the cover-art (BIP/OBEX) service — confirmed by
  SDP (AVRCP Target record, attr 0x000D contains an OBEX L2CAP PSM:
  "cover-art advertised: YES").
- But the phone's `org.bluez.MediaPlayer1` `Track` has **no `ImgHandle`** — only
  Title/Artist/Album/Duration. So there is no per-track image handle for the Pi
  to fetch by. BlueZ (controller side) isn't surfacing the remote player's
  cover-art handle.
- `strings /usr/local/libexec/bluetoothd | grep -i imghandle` found nothing on
  the test Pi (but USB-C serve-side art works, so interpret that cautiously —
  the serve path may use the patch via a different code path, or that binary was
  an older build). The decisive fact is the empty `ImgHandle` in the phone's
  Track.

**What needs to be done:**
1. Get BlueZ to expose the remote (phone) player's cover-art image handle in its
   `MediaPlayer1.Track` (controller-side AVRCP 1.6 cover art). Likely a
   bluetoothd patch/rebuild (and possibly requesting attribute 0x08 on
   GetElementAttributes). **This is the hard/uncertain part** — it means
   rebuilding and swapping the Bluetooth daemon, which risks all Bluetooth if it
   goes wrong, so do it carefully with a recovery plan.
2. Parameterise `BtArt` (in `pi/bin/auxlink-media.py`) to take a device
   `addr`/`adapter`/`tag` (defaults to the current `SOURCE`/`SOURCE_ADAPTER`),
   and give it a per-device temp file (`auxlink-<tag>-art.jpg`) and art_id tag so
   the phone and a BT music source don't clash.
3. In `phone_media_monitor`, create a `BtArt` for the phone
   (`addr=PHONE_ADDR`, `adapter=PHONE_ADAPTER`, `tag="phone"`), include
   `ImgHandle` in `phone_info`, and drive `art.update(player.phone_active,
   imghandle, track_key=title)` each poll; call `art.update(False, "")` on
   release. The fetched JPEG flows through the existing `Player.smo_art()` →
   `auxlink-cover` → the car (that path already works).
4. Make sure the SMO's own art doesn't fight the phone's while the phone owns the
   display (gate the SMO art source when `player.phone_active`, as `smo_update`
   is already gated).

The fetch machinery (`sdp_bip_psm`, `auxlink-bip.py`, `BtArt`) already exists for
a Bluetooth *music source*; it has just never been wired to the phone, and the
controller-side handle (#1) is the blocker.
