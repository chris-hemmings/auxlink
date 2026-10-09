#!/bin/bash
# Stage-1 album-art test: show a fixed test picture as the car's album art,
# then watch the car fetch it. Needs the patched bluetoothd
# (extras/build-bluetoothd-cover.sh).   sudo cover-test.sh [off]
DIR=/run/teslabridge/cover
[ "$(id -u)" = 0 ] || { echo "Run with sudo"; exit 1; }
if [ "$1" = off ]; then
  rm -f "$DIR/current" "$DIR/1000001.jpg"; echo "Test picture removed."; exit 0
fi
if ! systemctl show bluetooth -p Environment | grep -q TB_COVER_ART_PSM; then
  echo "bluetoothd is the stock one: run  sudo extras/build-bluetoothd-cover.sh  first."; exit 1
fi
systemctl is-active --quiet tb-cover || { echo "tb-cover is not running:"; systemctl status tb-cover --no-pager | tail -5; exit 1; }
mkdir -p "$DIR"
cp /usr/local/share/teslabridge/cover-test.jpg "$DIR/1000001.jpg"
echo 1000001 > "$DIR/current"
echo "Test picture is now the album art. Look at the car's music screen."
echo "(If nothing appears, turn the car's Bluetooth off and on: it reads the"
echo " cover-art support when it connects.)  Watching for 60 s..."
echo
timeout 60 journalctl -u tb-cover -u teslabridge-keys -f -n 0 --no-pager -o cat | grep --line-buffered -iE "cover|image|car "
echo
echo "Remove the test picture with:  sudo cover-test.sh off"
