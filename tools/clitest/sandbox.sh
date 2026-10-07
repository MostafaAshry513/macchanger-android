#!/bin/sh
#
# clitest/sandbox.sh - device-path redirector for the CLI behavioural test.
#
# cli/macchanger.sh assigns its device paths as plain constants:
#
#     BB=${BB:-/data/adb/magisk/busybox}        <- env-overridable
#     DIR=/data/adb/macchanger                  <- NOT env-overridable
#     BAK=$DIR/WIFI.factory                     <- NOT env-overridable
#     NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI   <- NOT env-overridable
#     NET=/sys/class/net                        <- NOT env-overridable
#
# so there is no variable the test suite could set to point the script at a
# synthetic NVRAM image.  Rather than test a rewritten copy of the script (which
# would prove something about the copy, not about what ships), clitest.sh runs
# the REAL script inside a private mount namespace and bind-mounts throwaway
# scenario directories over the four paths the script hardcodes:
#
#     /mnt/vendor/nvdata/APCFG/APRDEB  <-  $scenario/nvram    (the calibration file)
#     /data/adb/macchanger             <-  $scenario/record   (the factory record)
#     /sys/class/net                   <-  $scenario/net      (wlan0 + its MAC)
#
# /mnt, /data and /sys themselves are covered with tmpfs first, so nothing
# outside the namespace is created, hidden for longer than the run, or written:
# the mounts are private (unshare -m) and vanish when this process exits.
#
# Usage (clitest.sh does this; do not call it by hand):
#
#     unshare -m --propagation private sh sandbox.sh <scenario-dir> <cli> [args...]
#
# Environment (set by clitest.sh):
#     CLITEST_SHELL      shell used to run the CLI (default: sh)
#     TEST_BUSYBOX       real busybox to symlink at the Magisk default path
#     TEST_SVC_LOG       where the stub `svc` appends its log
#     TEST_MAC_OFFSET    offset the stub `svc` re-reads on `svc wifi enable`
#     TEST_SVC_NORELOAD  non-empty: the stub does not re-read (driver ignored it)
#
# Exit status: the CLI's own status, or 91 when the sandbox itself could not be
# set up.  91 is deliberately not a valid CLI status: clitest.sh treats it as a
# broken harness (hard error) instead of as a test PASS/FAIL.

SCN=$1
CLI=$2
shift 2

RUNSHELL=${CLITEST_SHELL:-sh}
BB=${TEST_BUSYBOX:-busybox}

setup_fail() {
    echo "sandbox: $1" >&2
    exit 91
}

[ -n "$SCN" ] || setup_fail "no scenario directory given"
[ -d "$SCN/nvram" ] || setup_fail "scenario has no nvram/ directory: $SCN"
[ -d "$SCN/record" ] || setup_fail "scenario has no record/ directory: $SCN"
[ -d "$SCN/net" ] || setup_fail "scenario has no net/ directory: $SCN"
[ -f "$CLI" ] || setup_fail "no CLI script to run: $CLI"

case "$RUNSHELL" in
    */*) [ -x "$RUNSHELL" ] || setup_fail "shell is not executable: $RUNSHELL";;
esac
command -v "$RUNSHELL" >/dev/null 2>&1 || [ -x "$RUNSHELL" ] \
    || setup_fail "shell not found: $RUNSHELL"

# Everything below needs CAP_SYS_ADMIN and uid 0.
[ "$(id -u)" = 0 ] || setup_fail "must be uid 0 inside the namespace"

# --- the calibration file ---------------------------------------------------
mount -t tmpfs tmpfs /mnt 2>/dev/null || setup_fail "cannot mount tmpfs on /mnt"
mkdir -p /mnt/vendor/nvdata/APCFG/APRDEB || setup_fail "cannot create the APCFG/APRDEB path"
mount --bind "$SCN/nvram" /mnt/vendor/nvdata/APCFG/APRDEB \
    || setup_fail "cannot bind the scenario nvram/ onto the MTK calibration path"
[ -f /mnt/vendor/nvdata/APCFG/APRDEB/WIFI ] \
    || setup_fail "the calibration file is missing from the scenario"

# --- the factory record -----------------------------------------------------
mount -t tmpfs tmpfs /data 2>/dev/null || setup_fail "cannot mount tmpfs on /data"
mkdir -p /data/adb/magisk /data/adb/macchanger \
    || setup_fail "cannot create /data/adb/{magisk,macchanger}"
mount --bind "$SCN/record" /data/adb/macchanger \
    || setup_fail "cannot bind the scenario record/ onto /data/adb/macchanger"

# The CLI's own default busybox path, resolved to the real busybox: this keeps
# the default (no BB= in the environment) branch of the script under test
# instead of forcing the BB override.
if [ -n "$BB" ] && [ -x "$BB" ]; then
    ln -s "$BB" /data/adb/magisk/busybox 2>/dev/null \
        || setup_fail "cannot link busybox into the sandbox Magisk path"
else
    setup_fail "no usable busybox to place at /data/adb/magisk/busybox (TEST_BUSYBOX='$BB')"
fi

# The stub `svc`: `svc wifi disable/enable` is what the script uses to restart
# WiFi.  Placed in /data/adb/magisk, which the script prepends to PATH, so the
# stub is what a restart actually runs.
cp "$(dirname "$0")/stubs/svc" /data/adb/magisk/svc \
    || setup_fail "cannot install the stub svc"
chmod 755 /data/adb/magisk/svc || setup_fail "cannot make the stub svc executable"

# --- the interface and its runtime MAC -------------------------------------
mount -t tmpfs tmpfs /sys 2>/dev/null || setup_fail "cannot mount tmpfs on /sys"
mkdir -p /sys/class/net || setup_fail "cannot create /sys/class/net"
mount --bind "$SCN/net" /sys/class/net \
    || setup_fail "cannot bind the scenario net/ onto /sys/class/net"

# --- run the real thing -----------------------------------------------------
exec "$RUNSHELL" "$CLI" "$@"
