#!/bin/bash
# Keep ~/phonebook current from the Oppo: pull 30 s after it connects, then
# every 3 hours while it stays connected; retry after 5 minutes on failure.
. /usr/local/lib/auxlink/common.sh
if [ "$PHONE_ENABLED" != 1 ] || [ "${CONTACTS_SYNC:-1}" != 1 ] || [ -z "$PHONE" ] || [ -z "$PHONE_ADAPTER" ]; then
  echo "Contacts sync off (no phone set up, or CONTACTS_SYNC=0)"; exec sleep infinity
fi
EVERY=$((3 * 3600)); was=no; next=0
while true; do
  if $BT "$PHONE_ADAPTER" "$PHONE" connected 2>/dev/null; then
    now=$(date +%s)
    if [ "$was" = no ]; then echo "Oppo connected; pulling contacts in 30 s"; was=yes; next=$((now + 30)); fi
    if [ "$now" -ge "$next" ]; then
      if timeout 600 python3 "$HOME/.local/bin/pbap-pull.py"; then next=$((now + EVERY))
      else echo "Pull failed; retrying in 5 minutes"; next=$((now + 300)); fi
    fi
  else
    was=no
  fi
  sleep 15
done
