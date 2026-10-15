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

## Phone notification mirroring (in progress)

Mirror every notification from the phone to the AuxLink head unit, shown as a
pop-down with a timeout; tap to expand to the full text.

**Transport:** Android exposes no standard Bluetooth profile for app
notifications, so a forwarder runs *on the phone*: the AuxLink app in
"forwarder" mode captures notifications and sends them to the Pi over a
dedicated Bluetooth serial service (`NOTIF_UUID`), which relays them to the
music device's app as `{"notif": {...}}`.

**Stage 1 (done, v1.0.114; channel fix v1.0.115):**
- Pi: `NotifLink` RFCOMM/Profile1 service (`NOTIF_UUID`), always registered;
  accepts the phone only; relays `{"notif"}` lines to the SMO app (`app_send`).
- App forwarder: `setForwardNotifs()` + `onNotificationPosted()` captures
  app/title/text/big-text and a small PNG of the app icon, sends over
  `BtLink(NOTIF_UUID)`. Skips the app's own, ongoing/foreground, group
  summaries, and call/media categories. A switch in Tools ("Mirror this
  phone's notifications") turns it on (enable on the PHONE only). Default off.
- App receiver (head unit): `{"notif"}` -> heads-up banner (`TextPopup.show`)
  with app icon + app name + title/body; tap -> `showNotifDetail()` expands.
  SMS from the Pi still uses the classic `TextPopup.showMessage` pop-up (tap
  opens the chat / Tesla path unchanged, with the default messaging app's
  icon). The forwarder auto-skips the SMS app (no need to mute Messages).
  Tools → Apps to forward lists all launchable apps (Wear-style) for the rest.
- BlueZ did not publish an SDP RFCOMM record for the custom UUID (sdptool
  found nothing; phone "socket failure", no bluetoothd log). Fixed by pinning
  `NOTIF_CHANNEL=22` / `APP_CHANNEL=23` on the Pi and having `BtLink` connect
  straight to that channel (bypass SDP).

**Stage 2 (done):**
- Alerts tab: scrollback + forwarder controls (moved out of Tools); tap for
  full text; Clear.
- Apps to forward: Wear-style full app list with Select all / none and Save.
- De-dupe: same pkg+title+text within 45 s is not re-sent.
- Forwarder-only mode: phone stops music-device traffic; UI hides Phone /
  Messages / Status and keeps Alerts.

**Stage 3 (done, Pi v1.0.120):** reply/actions
- Phone forwarder packs up to 3 `Notification.Action`s (prefer Reply /
  Mark as read, including WearableExtender) with `nid` + action ids, and
  holds their `PendingIntent`s (+ `RemoteInput`) for ~15 minutes.
- Pi relays `{"notif_action":{"nid","id","text"?}}` from the head unit back
  over `NOTIF_UUID` to the phone (`NotifLink.to_phone`).
- Head-unit banner and Alerts detail show action pills; Reply opens a
  short text field and fires the phone-side RemoteInput.
- Re-landed in **v1.0.120** on top of the USB-C reliability work (the brief
  v1.0.117 rollback was precautionary; Stage 3 did not cause the silence).

**USB-C reliability (v1.0.118–119):** composite gadget (UAC + CDC) can leave
Android with Wired selected but silent PCM when the app holds serial oddly.
- Check and fix / Pi: force re-plug when input is silent but the card looks OK.
- Auto: if SMO Playing and no PCM for ~40 s, force gadget re-plug (3 min cool-down).
- App: defer CDC open 2.5 s after attach; soft-claim first; recycle serial if
  the live audio path reports "silent but should be playing".
