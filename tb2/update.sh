#!/bin/bash
# teslabridge quick update: copy the programs and service files from this
# folder and restart the teslabridge services. Run from this folder:
#   sudo ./update.sh
# No packages, no internet, no reboot, Bluetooth stays up (the car and phone
# links survive). Use install.sh for a first install or if a release says so.
set -e
[ "$(id -u)" = 0 ] || { echo "Run with sudo: sudo ./update.sh"; exit 1; }
[ -f /etc/teslabridge.conf ] || { echo "Not installed yet: run sudo ./install.sh first"; exit 1; }

U=${SUDO_USER:-$(getent passwd 1000 | cut -d: -f1)}
H=$(getent passwd "$U" | cut -d: -f6)
UID_U=$(id -u "$U")
HERE=$(cd "$(dirname "$0")" && pwd)
asuser() { runuser -u "$U" -- env XDG_RUNTIME_DIR=/run/user/$UID_U "$@"; }

# Settings: keep every value, add any this release introduced.
added=""
while IFS= read -r line; do
  case "$line" in ''|\#*) continue ;; esac
  k=${line%%=*}
  grep -q "^$k=" /etc/teslabridge.conf || { echo "$line" >> /etc/teslabridge.conf; added="$added $k"; }
done < "$HERE/etc/teslabridge.conf"
echo "Settings kept${added:+; added:$added}"

install -D -m 644 "$HERE/lib/common.sh" /usr/local/lib/teslabridge/common.sh
install -D -m 644 "$HERE/lib/tbconf.py" /usr/local/lib/teslabridge/tbconf.py
install -D -m 644 "$HERE/share/index.html" /usr/local/share/teslabridge/index.html
install -m 755 "$HERE"/bin/* /usr/local/bin/
install -o "$U" -g "$U" -m 755 "$HERE"/user-bin/* "$H/.local/bin/"
install -m 644 "$HERE"/systemd/system/*.service /etc/systemd/system/
install -o "$U" -g "$U" -m 644 "$HERE"/systemd/user/*.service "$H/.config/systemd/user/"
install -o "$U" -g "$U" -m 644 "$HERE"/wireplumber/*.conf "$H/.config/wireplumber/wireplumber.conf.d/"
echo "Programs and services copied"

systemctl daemon-reload
systemctl restart tb-pairing tesla-reconnect hfp-relay teslabridge-keys tb-web
asuser systemctl --user daemon-reload
asuser systemctl --user restart tesla-audio pbap-sync
echo "Services restarted. Done (no reboot needed)."
