# AuxLink XIAO RP2040 firmware (music device side)

Based on [TeslAux](https://github.com/jbschooley/TeslAux) (MIT, see
`rp2040/LICENSE-TeslAux`): the phone-facing `source` board, ported to the XIAO
RP2040, with the `media-keys` additions (HID media keys from the Pi, now-playing
info from the SMO app to the Pi) and, new, `smo-mic`: a USB microphone that
carries the car's cabin mic to the music device for voice search and navigation.

`auxlink-xiao.uf2` is built with:

    cd rp2040
    cargo build --release --bin source --features rp2040-zero,smo-mic,ultra-low

(`smo-mic` includes `media-keys`.) Flash: hold BOOT while plugging the XIAO in,
copy the .uf2 to the RPI-RP2 drive.

## How the mic works
1. The SMO opens the USB mic (voice search) -> the XIAO sends `0x01 'M' '1'` to
   the Pi on the serial link (every 0.5 s while open; `0x01 'M' '0'` on close).
2. auxlink-media writes `/run/auxlink/mic`; hfp-relay borrows the car's
   mic over Bluetooth (by default shown to the car as a call; Teslas ignore
   voice recognition requests) and sends the audio back to auxlink-media.
3. auxlink-media sends it to the XIAO as G.711 mu-law frames
   (`0x01, len, data`) between the key bytes, 115200 baud as before.
4. The XIAO decodes, upsamples 8 -> 48 kHz and streams it to the SMO.

Wiring to the Pi (pinout) and the full setup are in the main README,
section 3.
