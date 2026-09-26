#!/system/bin/sh
set -eu
export PATH=/system/bin:/system/xbin
unset LD_PRELOAD
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$(id -u)" in 0|2000) ;; *) echo 'Use an authorized Android shell (Shizuku/local ADB/root).' >&2; exit 1;; esac
[ -x /system/bin/app_process ] || { echo 'Run this in Android shell, not the DSHA Linux terminal.' >&2; exit 1; }
[ ! -d "$BASE/install.lock" ] || { echo 'Installation in progress; retry later.' >&2; exit 1; }
for file in helper.jar scrcpy.jar token viewer.html viewer.js viewer.css video.js; do
 [ -s "$BASE/$file" ] || { echo "Missing runtime file: $file" >&2; exit 1; }
done
export CLASSPATH="$BASE/scrcpy.jar:$BASE/helper.jar"
control() { app_process / local.dsh.vdisplay.HelperControl "$BASE" status; }
if control; then echo 'Helper is already running; current session preserved.'; exit 0; else result=$?; fi
[ "$result" -eq 2 ] || { echo 'Existing service rejected the health check; no replacement started.' >&2; exit 1; }
if ! mkdir "$BASE/start.lock" 2>/dev/null; then
 old=$(cat "$BASE/start.lock/pid" 2>/dev/null || true)
 case "$old" in ''|*[!0-9]*) echo 'Startup lock incomplete; retry or inspect the stale lock.' >&2; exit 1;; esac
 if kill -0 "$old" 2>/dev/null; then echo 'Another startup is in progress.' >&2; exit 1; fi
 rm -f "$BASE/start.lock/pid"
 rmdir "$BASE/start.lock"
 mkdir "$BASE/start.lock"
fi
echo $$ > "$BASE/start.lock/pid"
trap 'rm -f "$BASE/start.lock/pid"; rmdir "$BASE/start.lock"' EXIT
if control >/dev/null 2>&1; then echo 'Helper is already running.'; exit 0; else result=$?; fi
[ "$result" -eq 2 ] || exit 1
umask 077
setsid app_process / local.dsh.vdisplay.PhoneDisplay "$BASE/token" > "$BASE/service.log" 2>&1 </dev/null &
for attempt in 1 2 3 4 5 6 7 8 9 10; do
 sleep 1
 if control; then echo 'Phone-local Helper ready.'; exit 0; fi
done
echo "Helper did not become ready. Inspect $BASE/service.log locally." >&2
exit 1
