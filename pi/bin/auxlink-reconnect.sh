#!/bin/bash
# Keeps the Bluetooth links up:
#   car adapter   -> Tesla  (the Pi is the phone / music source; the car
#                            connects itself - see CAR_CALLS)
#   phone adapter -> Oppo   (the Pi is the car kit)
# and recovers RTL8761BU dongles that come up stuck after a warm reboot.
# Rules that keep it from hurting the car link:
#   * a missing PHONE dongle is never acted on while the car is connected
#   * a missing phone dongle must be gone 60 s before USB is power-cycled
#   * a missing CAR adapter is acted on after 20 s
. /usr/local/lib/auxlink/common.sh
# Not set up yet: nothing to keep connected (the setup page pairs the car).
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/auxlink.conf; done
[ -z "$PHONE" ] || [ -z "$PHONE_ADAPTER" ] && PHONE_ENABLED=0

car_was=no; phone_was=no; phone_warned=no; car_err=; source_was=no; source_next=0
# Calls the Pi makes to the car: first one 45 s after start (the car usually
# connects by itself first), then backing off 60 s -> 5 min.
CAR_FIRST_WAIT=45; CAR_MIN_WAIT=60; CAR_MAX_WAIT=300
car_next=$(( $(date +%s) + CAR_FIRST_WAIT )); car_wait=$CAR_MIN_WAIT
missing=0; restarts=0; phone_next=0

while true; do
  if pairing_active; then sleep 2; continue; fi   # hands off during pairing
  . /etc/auxlink.conf      # pick up a newly paired car/phone without a restart
  [ -z "$PHONE" ] || [ -z "$PHONE_ADAPTER" ] && PHONE_ENABLED=0
  phone_missing=no
  if [ "$PHONE_ENABLED" = 1 ] && ! $BT "$PHONE_ADAPTER" present 2>/dev/null; then phone_missing=yes; fi
  if [ "$phone_missing" = yes ] && $BT "$CAR_ADAPTER" "$CAR" connected 2>/dev/null; then
    [ "$phone_warned" = no ] && echo "Phone adapter missing; leaving it alone while the car is connected"
    phone_warned=yes; phone_missing=no
  else
    phone_warned=no
  fi

  if ! $BT "$CAR_ADAPTER" present 2>/dev/null || [ "$phone_missing" = yes ]; then
    missing=$((missing + 1))
    limit=4
    $BT "$CAR_ADAPTER" present 2>/dev/null && limit=12      # only the phone side is gone
    if [ "$missing" -ge "$limit" ]; then
      missing=0; restarts=$((restarts + 1))
      # A USB power cycle only helps a missing USB dongle, not the built-in chip.
      usb_missing=no
      ! $BT "$CAR_ADAPTER" present 2>/dev/null && [ "${CAR_ADAPTER_BUS:-usb}" != builtin ] && usb_missing=yes
      [ "$phone_missing" = yes ] && [ "${PHONE_ADAPTER_BUS:-usb}" != builtin ] && usb_missing=yes
      if [ "$restarts" -ge 1 ] && [ "$usb_missing" = yes ]; then
        echo "Bluetooth adapter missing; power-cycling USB"
        /usr/local/bin/bt-usb-cycle.sh cycle
        restarts=0
        sleep 8
      fi
      echo "Restarting bluetoothd"
      rfkill unblock bluetooth
      systemctl restart bluetooth
      sleep 5
      continue
    fi
    $BT "$CAR_ADAPTER" present 2>/dev/null || { sleep 5; continue; }
  else
    missing=0; restarts=0
  fi

  # Keep both adapters on, with fixed names (a reset can leave "auxlink #2").
  $BT "$CAR_ADAPTER" power >/dev/null 2>&1
  $BT "$CAR_ADAPTER" alias "$CAR_NAME" 2>/dev/null
  if [ "$PHONE_ENABLED" = 1 ]; then
    $BT "$PHONE_ADAPTER" power >/dev/null 2>&1
    $BT "$PHONE_ADAPTER" alias "$PHONE_NAME" 2>/dev/null
  fi

  # --- Car ---
  if $BT "$CAR_ADAPTER" "$CAR" connected; then
    [ "$car_was" = no ] && echo "Car connected"
    car_was=yes
  else
    now=$(date +%s)
    if [ "$car_was" = yes ]; then
      if [ "${CAR_CALLS:-0}" = 1 ]; then
        echo "Car disconnected; giving it ${CAR_FIRST_WAIT}s to reconnect by itself"
      else
        echo "Car disconnected; waiting for it to reconnect"
      fi
      car_next=$((now + CAR_FIRST_WAIT)); car_wait=$CAR_MIN_WAIT
    fi
    car_was=no
    # The Tesla hangs up on connections the Pi starts, and reconnects by
    # itself - but only if the Pi isn't calling it at that moment: a Pi
    # attempt colliding with the car's own connect made the car drop both.
    # So by default (CAR_CALLS=0) never call it; with CAR_CALLS=1 call
    # rarely, backing off after each failure.
    if [ "${CAR_CALLS:-0}" = 1 ] && [ "$now" -ge "$car_next" ]; then
      if err=$(timeout 25 $BT "$CAR_ADAPTER" "$CAR" connect 2>&1 >/dev/null); then
        echo "Reconnected to car"; car_err=; car_wait=$CAR_MIN_WAIT
      else
        err=${err:-timed out}
        [ "$err" != "$car_err" ] && echo "Car connect failed: $err (retrying less often; the car can still connect any time)"
        car_err=$err
        car_next=$(( $(date +%s) + car_wait ))
        car_wait=$((car_wait * 2)); [ "$car_wait" -gt "$CAR_MAX_WAIT" ] && car_wait=$CAR_MAX_WAIT
      fi
    fi
  fi

  # --- Bluetooth music source (every ~60 s; sources reconnect by themselves too) ---
  if [ "${MUSIC_SOURCE:-wired}" = bluetooth ] && [ -n "$SOURCE" ] && [ -n "$SOURCE_ADAPTER" ] &&
     $BT "$SOURCE_ADAPTER" present 2>/dev/null; then
    if $BT "$SOURCE_ADAPTER" "$SOURCE" connected; then
      [ "$source_was" = no ] && echo "Music source connected"
      source_was=yes
      # Connected, but maybe only its headset link (voice search): an Android
      # device can drop media audio and keep that. No A2DP transport for it
      # = no music: open the music link again (every ~30 s at most).
      now=$(date +%s)
      if ! busctl tree org.bluez 2>/dev/null | grep -q "dev_${SOURCE//:/_}/sep[0-9]*/fd" &&
         [ "$now" -ge "${source_a2dp_next:-0}" ]; then
        source_a2dp_next=$((now + 30))
        echo "Music source connected without its music link; reopening it"
        timeout 25 $BT "$SOURCE_ADAPTER" "$SOURCE" connect 0000110a-0000-1000-8000-00805f9b34fb >/dev/null 2>&1 \
          && echo "Music source: music link reopened"
      fi
    else
      [ "$source_was" = yes ] && echo "Music source disconnected, will keep trying"
      source_was=no
      now=$(date +%s)
      if [ "$now" -ge "$source_next" ]; then
        source_next=$((now + 60))
        timeout 25 $BT "$SOURCE_ADAPTER" "$SOURCE" connect >/dev/null 2>&1 && echo "Reconnected to the music source"
      fi
    fi
  fi

  # --- Phone (every ~30 s; phones normally reconnect by themselves too) ---
  if [ "$PHONE_ENABLED" = 1 ] && $BT "$PHONE_ADAPTER" present 2>/dev/null; then
    if $BT "$PHONE_ADAPTER" "$PHONE" connected; then
      [ "$phone_was" = no ] && echo "Phone connected"
      phone_was=yes
    else
      [ "$phone_was" = yes ] && echo "Phone disconnected, will keep trying"
      phone_was=no
      now=$(date +%s)
      if [ "$now" -ge "$phone_next" ]; then
        phone_next=$((now + 30))
        timeout 25 $BT "$PHONE_ADAPTER" "$PHONE" connect >/dev/null 2>&1 && echo "Reconnected to phone"
      fi
    fi
  fi
  sleep 10
done
