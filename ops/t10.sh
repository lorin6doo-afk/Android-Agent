#!/bin/bash
# T10 baterija — 15 poizvedb iskanja stikov prek DEBUG_FIND; izpiše verzijo APK
# in surove logcat vrstice. Uporaba: bash ops/t10.sh <adb-serial-testnega-telefona>
set -u
SERIAL=${1:?uporaba: t10.sh <adb-serial>}
A() { adb -s "$SERIAL" "$@"; }

echo "== APK =="
A shell dumpsys package si.sopotnik | grep -m1 versionName

echo "== POIZVEDBE =="
A logcat -c
QUERIES=("Urša Zvezdica" "Urša" "ursa" "Svikart" "Rudi" "Bojan" "Novak Jože" \
         "Jože Novak" "Novakovo" "Novak" "Mama" "mama srce" "Janez" \
         "Katarina Kobilca" "Ur")
for q in "${QUERIES[@]}"; do
  A shell "am broadcast -a si.sopotnik.DEBUG_DUMP --es q '$q' si.sopotnik" >/dev/null 2>&1
  sleep 1
done
sleep 2

echo "== REZULTATI =="
A logcat -v time -s Sopotnik:* -d | grep DEBUG_FIND
