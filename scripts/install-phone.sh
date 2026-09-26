#!/system/bin/sh
set -eu
export PATH=/system/bin:/system/xbin
unset LD_PRELOAD
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE=${VDISPLAY_DIR:-/data/local/tmp/dsh-phone-vdisplay}
case "$BASE" in /data/local/tmp/dsh-phone-vdisplay|/data/local/tmp/dsh-phone-vdisplay-test-*) ;; *) echo 'Unsupported runtime directory.' >&2; exit 1;; esac
case "$BASE" in *..*|*[!a-zA-Z0-9/_-]*) echo 'Invalid runtime directory.' >&2; exit 1;; esac
case "$(id -u)" in 0|2000) ;; *) echo 'Use an authorized Android shell (Shizuku/local ADB/root).' >&2; exit 1;; esac
[ -x /system/bin/app_process ] || { echo 'This is not an Android shell.' >&2; exit 1; }
cd "$SOURCE"
sha256sum -c SHA256SUMS >/dev/null || { echo 'Bundle checksum verification failed.' >&2; exit 1; }
CONFIG=${1:-$SOURCE/virtual-display.setup.json}
[ -s "$CONFIG" ] || { echo 'First run configure.mjs inside DSHA to create the temporary handoff.' >&2; exit 1; }
token=$(sed -n 's/^{"token":"\([A-Za-z0-9_-]*\)"}$/\1/p' "$CONFIG")
[ "${#token}" -eq 43 ] || { echo 'Invalid setup token; rerun configure.mjs.' >&2; exit 1; }
umask 077
mkdir -p "$BASE"
chmod 700 "$BASE"
if [ -e "$BASE/token" ] && [ "$(cat "$BASE/token")" != "$token" ]; then
 echo 'Existing Helper uses a different token. Restore matching DSHA configuration; no files changed.' >&2; exit 1
fi
mkdir "$BASE/install.lock" || { echo 'Another installation is in progress; inspect the lock before retrying.' >&2; exit 1; }
trap 'rm -f "$BASE/install.lock/token"; rmdir "$BASE/install.lock"' EXIT
printf '%s\n' "$token" > "$BASE/install.lock/token"
export CLASSPATH="$SOURCE/scrcpy.jar:$SOURCE/helper.jar"
if app_process / local.dsh.vdisplay.HelperControl "$BASE/install.lock" status >/dev/null; then
 echo 'Helper is running. To upgrade, finish your work and reboot before installing; nothing was overwritten.' >&2; exit 1
else result=$?; fi
[ "$result" -eq 2 ] || { echo 'Local port is occupied or configuration mismatches; refusing installation.' >&2; exit 1; }
for file in helper.jar scrcpy.jar viewer.html viewer.js viewer.css video.js start.sh; do
 cp "$SOURCE/$file" "$BASE/$file"
 chmod 600 "$BASE/$file"
done
cp "$BASE/install.lock/token" "$BASE/token"
chmod 600 "$BASE/token"
rm -f "$CONFIG"
rm -f "$BASE/install.lock/token"
rmdir "$BASE/install.lock"
trap - EXIT
echo 'Runtime installed; temporary credential handoff removed.'
exec sh "$BASE/start.sh"
