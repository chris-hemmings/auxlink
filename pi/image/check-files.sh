#!/bin/bash
# Every file the installer, updater and image scripts copy must be in the
# AuxLink folder (a missing one stops install.sh on the first boot, before the
# setup page and its Wi-Fi exist). Run by build-image.sh; also by hand:
#   pi/image/check-files.sh [pi-folder]
cd "${1:-$(dirname "$0")/..}" || exit 1
missing=0
for f in install.sh update.sh firstboot.sh image/customize.sh; do
  [ -f "$f" ] || continue
  for p in $(grep -oE '\$(HERE|A)/[A-Za-z0-9_./*-]+' "$f" | sort -u); do
    rel=${p#\$HERE/}; rel=${rel#\$A/}
    # Optional files are guarded by [ -f ... ] where they are used.
    grep -qE "\[ -f \"?\\\$(HERE|A)/$rel\"? \]" "$f" && continue
    compgen -G "$rel" >/dev/null || { echo "MISSING: $rel (used by $f)"; missing=1; }
  done
done
[ "$missing" = 0 ] && echo "All files the install scripts use are present"
exit $missing
