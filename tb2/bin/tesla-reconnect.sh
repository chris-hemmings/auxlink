#!/bin/bash
# Keeps the Bluetooth links up:
#   car adapter   -> Tesla  (the Pi is the phone / music source)
#   phone adapter -> Oppo   (the Pi is the car kit)
# and recovers RTL8761BU dongles that come up stuck after a warm reboot.
# Rules that keep it from hurting the car link:
#   * a missing PHONE dongle is never acted on while the car is connected
#   * a missing phone dongle must be gone 60 s before USB is power-cycled
#   * a missing CAR adapter is acted on after 20 s
. /usr/local/lib/teslabridge/common.sh
# Not set up yet: nothing to keep connected (the setup page pairs the car).
while [ -z "$CAR" ] || [ -z "$CAR_ADAPTER" ]; do sleep 5; . /etc/teslabridge.conf; done
[ -z "$PHONE" ] || [ -z "$PHONE_ADAPTER" ] && PHONE_ENABLED=0

car_was=no; phone_was=no; phone_warned=no; car_err=
missing=0; restarts=0; phone_next=0

while true; do
  if pairing_active; then sleep 2; continue; fi   # hands off during pairing
  . /etc/teslabridge.conf      # pick up a newly paired car/phone without a restart
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

  # Keep both adapters on, with fixed names (a reset can leave "teslabridge #2").
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
    [ "$car_was" = yes ] && echo "Car disconnected, will keep trying"
    car_was=no
    if err=$(timeout 25 $BT "$CAR_ADAPTER" "$CAR" connect 2>&1 >/dev/null); then
      echo "Reconnected to car"; car_err=
    else
      # Log why, but only when the reason changes (this retries every 10 s).
      err=${err:-timed out}
      [ "$err" != "$car_err" ] && echo "Car connect failed: $err"
      car_err=$err
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
