#!/system/bin/sh
# macchanger - persistent WiFi MAC changer for MediaTek (MTK) Android
# Device: Infinix SMART 5 (MT6761) / Android 11 / Magisk
#
# Why not plain "ip link set"?  On MTK the WiFi driver reloads the factory
# MAC from NVRAM on every WiFi re-init, so ip-link spoofs vanish on
# reconnect/reboot.  The factory MAC lives in:
#     /mnt/vendor/nvdata/APCFG/APRDEB/WIFI   (6 bytes at offset 4)
# Editing that file persists across reboots and reconnects.
#
# The MAC field is never assumed to be at a fixed offset: it is located by
# scanning the file for the live runtime MAC as SIX RAW BYTES (mac_at/scan_mac
# below - this is narrower than the app, which additionally tries the
# byte-reversed and lowercase colon/plain-ASCII encodings findMacWindow uses),
# and the write is refused unless the bytes at the matched offset are that very
# MAC.  A file that stores its MAC in another encoding is therefore reported as
# not containing it - a refusal, never a wrong write - and the message says so.
# Writes are in-place, bounded to 6 bytes and verified against a whole window
# around the offset, so verification cannot confirm a write that landed
# elsewhere - or confirm itself by re-reading the bytes it just wrote.
#
# A factory image is only ever captured while the NVRAM still holds the MAC the
# driver is using.  'restore' and 'show' never capture one: a "factory" record
# harvested from an already-spoofed NVRAM is worse than no record at all.
#
# Beyond that core:
#   * 'doctor' (alias 'selftest') reports the whole recoverability picture -
#     root, busybox applets, interface, every candidate NVRAM path, the recorded
#     factory value and its provenance, the backup directory, and whether that
#     record can actually be written back - and writes nothing at all, not even
#     a temp file.
#   * 'panic' (alias 'undo') is the disaster path: it puts the recorded image
#     back when the live NVRAM is corrupt, unreadable or truncated, and refuses
#     rather than guesses when the record cannot be trusted.
#   * the WiFi restart is observed, never slept through: the state the ROM
#     reports (or the MAC the driver publishes) is polled within a bounded
#     budget, so a slow-but-legal reload, a toggle the platform refused
#     (airplane mode) and a ROM that reports no state at all are told apart
#     instead of all being reported as the driver ignoring the MAC.
#   * every writing command takes --dry-run, which prints the file, the offset
#     and the bytes that would change and exits with the status the real run
#     would have for the checks it evaluates (the plan itself, and a recorded
#     image that fails its digest check or is too short to be written back).
#     It does not run the capture a real 'set' performs, so a refusal that only
#     arises while capturing - a live MAC that is locally administered, or a MAC
#     field that cannot be located - is not predicted by it: the dry run exits 0
#     where the real run exits 6.
#   * exit codes are per failure class (see 'help'), and --quiet/--json make the
#     read-only commands scriptable.

PATH=/system/bin:/system/xbin:/data/adb/magisk:$PATH
BB=${BB:-/data/adb/magisk/busybox}
DIR=/data/adb/macchanger
NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
NV_LEGACY=/data/nvram/APCFG/APRDEB/WIFI   # MTK, older layout; reported, never written
NET=/sys/class/net

umask 077

# --- exit codes -------------------------------------------------------------
# One class per failure so a wrapper can tell "refused to write" from "wrote it
# and the driver ignored it".  Documented in 'help'.
EX_USAGE=1       # bad command line, bad MAC, unknown option or command
EX_NOTROOT=2     # not uid 0
EX_TOOL=3        # busybox missing, or an applet unusable
EX_DEVICE=4      # NVRAM path missing, or not a regular file
EX_IFACE=5       # WiFi interface not resolvable, or ambiguous
EX_PRECOND=6     # precondition on the target failed: refusing to write blind
EX_NOBACKUP=7    # factory image missing, damaged, foreign, or would be overwritten
EX_WRITE=8       # the write, or its verification, failed
EX_APPLY=9       # written and verified, but the driver is not using the MAC

ORIG_ARGS="$*"

die() { # [CODE] message... - CODE defaults to 1, so unclassified failures stay 1
    case "$1" in
        ''|*[!0-9]*) _c=$EX_USAGE ;;
        *) _c=$1; shift ;;
    esac
    echo "error: $*" >&2
    exit "$_c"
}

# say  - results and warnings: always shown.
# info - progress: hidden by --quiet.
# In --json mode everything human-readable goes to stderr, so stdout stays one
# parseable document.
say()  { if [ -n "$JSON" ]; then echo "$*" >&2; else echo "$*"; fi; }
info() { [ -n "$QUIET" ] && return 0; say "$*"; }

# --- JSON output ------------------------------------------------------------
# Values here are single-line scalars (paths, MACs, sizes, verdicts); the
# escaper is built character by character with awk instead of gsub(), because
# backslash handling in gsub replacements is not portable.
jstr() {
    printf '%s' "$1" | $BB awk 'BEGIN{ printf "\"" }
        { for (i = 1; i <= length($0); i++) {
              c = substr($0, i, 1);
              if (c == "\"") printf "\\\"";
              else if (c == "\\") printf "\\\\";
              else if (c == "\t") printf "\\t";
              else if (c == "\r") printf "\\r";
              else printf "%s", c;
          } }
        END{ printf "\"" }'
}

JSON_FIRST=
jopen()  { [ -n "$JSON" ] || return 0; JSON_FIRST=; printf '{\n'; }
jclose() { [ -n "$JSON" ] || return 0; printf '\n}\n'; }
jfield() { # $1 = already-quoted key, $2 = raw value
    [ -n "$JSON" ] || return 0
    [ -n "$JSON_FIRST" ] && printf ',\n'
    JSON_FIRST=1
    printf '  %s: %s' "$1" "$(jstr "$2")"
}

# row KEY LABEL VALUE - one reported fact, in whichever format was asked for.
row() {
    if [ -n "$JSON" ]; then
        jfield "\"$1\"" "$3"
    else
        printf '%-15s: %s\n' "$2" "$3"
    fi
}

# --- device paths: constants, and only constants -----------------------------
# $DIR, $NV and $NET above are literals and NOTHING in the environment can
# redirect them.  An earlier revision of this file shipped an off-device testing
# hook - MACCHANGER_TEST plus MACCHANGER_DIR/MACCHANGER_NV/MACCHANGER_NET - which
# pointed the calibration path, and therefore the write, at a path the caller
# chose.  That is a root-write primitive for a tool whose whole job is to rewrite
# a calibration partition as uid 0, and it was reachable by whoever controls the
# environment of the invocation: this script's own 'must run as root' message
# tells the user to invoke it as 'su -c ...', which passes that environment
# through to the root shell.  Its gate was also looser than its documentation -
# '[ -n "$MACCHANGER_TEST" ]' armed the redirect for MACCHANGER_TEST=0 - so the
# safety property the README and 'help' stated was false as shipped.
#
# No validation of the redirected target can repair that, because the caller
# chooses the target: an allow-list of calibration paths cannot cover a
# validation sandbox, and the sandbox is the only reason the hook exists.  The
# hook is therefore gone.  Nothing off-device needs it: tools/clitest runs this
# exact script text under a private mount namespace with throwaway directories
# bound over $DIR, $NV and $NET, which is what a revision with no hook has to do
# anyway and which needs no cooperation from the script.  See
# tools/clitest/README.md and tools/clitest/sandbox.sh.
BAK=$DIR/WIFI.factory

# busybox is a hard requirement (dd/od/hexdump/awk/cmp/sha256sum).  It is
# resolved and probed BEFORE anything else, so a broken busybox can never
# surface later as a bogus NVRAM error.  Only the built-in default path may be
# replaced by a busybox found on PATH; an explicitly configured BB that is not
# executable is fatal instead of being silently swapped out.
if [ ! -x "$BB" ] && [ "$BB" = /data/adb/magisk/busybox ]; then
    BB=busybox
fi
"$BB" true 2>/dev/null || die "$EX_TOOL" "busybox not found (tried '$BB'): install it or set BB="

# --- preconditions ----------------------------------------------------------
# Checked by the commands that need them rather than at load time, so 'doctor'
# can report a device that is not rooted or has no NVRAM at all.
require_root() {
    [ "$(id -u)" = 0 ] || die "$EX_NOTROOT" "must run as root (try: su -c '$0 $ORIG_ARGS')"
}

require_nvram() {
    [ -f "$NV" ] || die "$EX_DEVICE" "NVRAM file not found: $NV"
}

# --- cross-process lock -----------------------------------------------------
# MainActivity.java defines /data/adb/macchanger/lock and calls it "the lock
# directory both front ends can see": the app takes it with mkdir before it reads
# and rewrites the calibration file, refuses while a fresh timestamp is inside,
# and breaks it as abandoned after LOCK_STALE_S.  Its own comments name this CLI
# as the other writer of that same file - so as long as the CLI took no lock, the
# mutual exclusion the app documents was one-sided: it excluded the app from
# running beside itself and did nothing at all about the CLI.  Same path, same
# mkdir atomicity, and the same 300 s staleness rule, so either front end's lock
# stops the other.
#
# Two details the app's script leaves open are closed here, because getting them
# wrong is a silent double-writer rather than a refusal:
#   * the stale break is atomic.  The abandoned lock is renamed to a name only
#     this process uses and only the process whose rename succeeds may proceed,
#     so two breakers cannot both take it (rm -rf followed by mkdir can).
#   * release checks the owner token first, so a process whose lock was broken as
#     stale - and which therefore no longer owns it - cannot delete the new
#     owner's lock on its way out.
LOCK=$DIR/lock
LOCK_STALE_S=300
LOCK_HELD=
LOCK_TOKEN=

lock_acquire() {
    [ -n "$LOCK_HELD" ] && return 0
    mkdir -m 700 -p "$DIR" 2>/dev/null
    LOCK_TOKEN="$$.$(date +%s 2>/dev/null)"
    if ! mkdir "$LOCK" 2>/dev/null; then
        _t=$(cat "$LOCK/ts" 2>/dev/null)
        case "$_t" in ''|*[!0-9]*) _t=$(stat -c %Y "$LOCK" 2>/dev/null);; esac
        case "$_t" in ''|*[!0-9]*) _t=0;; esac
        _n=$(date +%s 2>/dev/null)
        case "$_n" in ''|*[!0-9]*) _n=0;; esac
        if [ "$_t" -gt 0 ] && [ $((_n - _t)) -lt "$LOCK_STALE_S" ]; then
            _o=$(cat "$LOCK/owner" 2>/dev/null)
            die "$EX_PRECOND" "another macchanger operation is running: $LOCK was taken \
$((_n - _t)) s ago by ${_o:-an unknown writer} and has not been released. Refusing to write \
alongside it - the other writer may be mid-write on $NV. Retry when it has finished; a lock left \
behind by a killed process is broken automatically after $LOCK_STALE_S s."
        fi
        # Abandoned: rename it out of the way.  rename(2) is atomic, so exactly one
        # of several breakers wins; the losers see it gone and report busy.
        _q="$LOCK.stale.$$"
        mv "$LOCK" "$_q" 2>/dev/null \
            || die "$EX_PRECOND" "another macchanger operation is running: $LOCK was released and \
re-taken while this run was breaking the abandoned lock. Refusing to write alongside it."
        rm -rf "$_q" 2>/dev/null
        mkdir "$LOCK" 2>/dev/null \
            || die "$EX_PRECOND" "another macchanger operation is running: $LOCK was taken again \
while this run was breaking the abandoned one. Refusing to write alongside it."
    fi
    date +%s >"$LOCK/ts" 2>/dev/null
    printf '%s\n' "$LOCK_TOKEN" >"$LOCK/owner" 2>/dev/null
    LOCK_HELD=1
    trap 'lock_release' EXIT HUP INT TERM
    return 0
}

# Best effort, and only for a lock this run still owns: if the lock was broken as
# stale, someone else is the writer now and deleting their lock would hand the
# calibration file to two writers.
lock_release() {
    [ -n "$LOCK_HELD" ] || return 0
    _o=$(cat "$LOCK/owner" 2>/dev/null)
    [ "$_o" = "$LOCK_TOKEN" ] && rm -rf "$LOCK" 2>/dev/null
    LOCK_HELD=
    return 0
}

# --- interface resolution ---------------------------------------------------
# wlan0 is not guaranteed (concurrent AP/STA, hotspot tethering, ROMs that only
# expose p2p0 early in boot), and rewriting the NVRAM entry of the WRONG
# interface is worse than refusing, so the station interface is detected.  An
# empty or ambiguous result is fatal unless --iface=NAME forces the choice.
iface_candidates() {
    _c=$(ls "$NET" 2>/dev/null | grep '^wlan')
    [ -n "$_c" ] || _c=$(iw dev 2>/dev/null | $BB awk '$1 == "Interface" { print $2 }' | grep '^wlan')
    [ -n "$_c" ] || _c=$(cmd wifi status 2>/dev/null | grep -o 'wlan[0-9]*')
    echo "$_c"
}

IF=
IF_ERR=
pick_iface() {
    [ -n "$IF" ] && return 0
    IF_ERR=
    if [ -n "$IF_FORCED" ]; then
        IF=$IF_FORCED
        export IF
        return 0
    fi
    _cands=$(iface_candidates)
    _n=0
    _one=
    for _c in $_cands; do _n=$((_n + 1)); _one=$_c; done
    if [ "$_n" -eq 1 ]; then
        IF=$_one
        export IF
    elif [ "$_n" -eq 0 ]; then
        IF_ERR="cannot determine the WiFi interface; use --iface=NAME"
    else
        IF_ERR="multiple WiFi interfaces ($(echo $_cands)); use --iface=NAME to choose one"
    fi
}

require_iface() { pick_iface; [ -n "$IF_ERR" ] && die "$EX_IFACE" "$IF_ERR"; return 0; }
get_runtime()   { pick_iface; [ -n "$IF" ] && cat "$NET/$IF/address" 2>/dev/null; }

# --- NVRAM read helpers -----------------------------------------------------
# busybox hexdump needs an explicit iteration count: -e '"%02x"' without one
# eats the first byte of every block (5 bytes printed as 9 hex digits), so
# every format string here carries 'N/1'.  Reads never assume offset 4.
hex_at() { # $1=file $2=offset $3=length -> hex, no separators
    $BB dd if="$1" bs=1 skip="$2" count="$3" 2>/dev/null | $BB hexdump -v -e '1/1 "%02x"'
}

mac_at() { # $1=file $2=offset -> aa:bb:cc:dd:ee:ff
    [ -f "$1" ] || return 1
    $BB dd if="$1" bs=1 skip="$2" count=6 2>/dev/null \
      | $BB hexdump -v -e '6/1 "%02x"' \
      | sed 's/\(..\)/\1:/g; s/:$//'
}

# "aabbcc" -> "aa bb cc", for printing a window a human can line up with od.
hex_pairs() { echo "$1" | sed 's/\(..\)/\1 /g'; }

# Low/high byte index (0-based, inside the window) where two equal-length hex
# images first and last differ -> "first last", or nothing when identical.
hex_diff() { # $1=before $2=after
    $BB awk -v a="$1" -v b="$2" 'BEGIN{
        n = length(a) / 2; f = -1; l = -1;
        for (i = 0; i < n; i++) {
            if (substr(a, 2 * i + 1, 2) != substr(b, 2 * i + 1, 2)) {
                if (f < 0) f = i;
                l = i;
            }
        }
        if (f >= 0) printf "%d %d", f, l;
    }'
}

# Every byte offset at which $2 (plain hex, no colons) occurs in $1, as
# "count off1 off2 ...".  Scanning is what makes the write precondition mean
# something: the offset written is the offset that actually holds the MAC.
scan_mac() { # sets SCAN_N and SCAN_OFFS
    _s=$($BB awk -v h="$($BB hexdump -v -e '1/1 "%02x"' "$1" 2>/dev/null)" -v p="$2" 'BEGIN{
        c = 0; out = ""; i = 1; L = length(h);
        while (i + 11 <= L) {
            if (substr(h, i, 12) == p) { c++; out = out sprintf(" %d", (i - 1) / 2); i += 12 }
            else i += 2;
        }
        printf "%d%s", c, out;
    }')
    SCAN_N=$(echo "$_s" | $BB awk '{ print $1 }')
    SCAN_OFFS=$(echo "$_s" | $BB awk '{ $1 = ""; sub(/^ /, ""); print }')
}

# POSIX octal escapes for "%b" (built with awk, never \xHH): verified
# byte-exact under dash's builtin and under busybox's sh and printf applet -
# dash's builtin prints \xHH literally, so it is not used anywhere.
mac_bytes() {
    echo "$1" | $BB awk -F: 'BEGIN{
        split("0123456789abcdef", d, "");
    }{
        for (i = 1; i <= NF; i++) {
            hi = index("0123456789abcdef", tolower(substr($i, 1, 1)));
            lo = index("0123456789abcdef", tolower(substr($i, 2, 1)));
            if (hi == 0 || lo == 0) exit 1;
            printf "\\%03o", (hi - 1) * 16 + (lo - 1);
        }
    }'
}

# Window image with the MAC spliced in at $2 (relative byte offset), used as
# the expected post-write image.
splice_mac() {
    $BB awk -v h="$1" -v r="$2" -v n="$3" 'BEGIN{
        printf "%s%s%s", substr(h, 1, 2 * r), n, substr(h, 2 * r + 13);
    }'
}

valid_mac() {
    echo "$1" | grep -Eq '^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$'
}
norm_mac() { echo "$1" | tr 'A-F' 'a-f'; }

# Bit 0x02 of the first octet marks a locally administered address.  Every
# randomizer sets it (this script's own 'random' included), so seeing it in the
# NVRAM is evidence that the file already holds a spoof - which is why the bit
# is rejected for a value about to be recorded as the factory MAC, and nowhere
# else.
locally_administered() {
    _first=$(echo "$1" | cut -d: -f1)
    [ $((0x$_first & 2)) -ne 0 ]
}

# A MAC that could never be a station address at all - multicast/broadcast (bit
# 0x01 of the first octet), all-zero, or all-0xFF - is not "probably a spoof",
# it is proof that the value is not a factory burn-in: no vendor burns one in
# and Android's own randomizer never produces one either.  This is the app's
# isUsableTarget(), and it is the same rule cmd_set already applies to a MAC it
# is about to write.  It is deliberately stricter than locally_administered():
# --assume-factory may be the user's acknowledgement of a 0x02 value, but there
# is nothing to acknowledge about a multicast, all-zero or broadcast address, so
# it is refused as factory evidence even with that flag - mirroring
# MainActivity's restoreRefusal(), where "no provenance makes them writable".
usable_factory_target() { # $1 = MAC -> 0 = plausible station address
    valid_mac "$1" || return 1
    case "$(echo "$1" | tr -d ':')" in
        000000000000|ffffffffffff) return 1;;
    esac
    _first=$(echo "$1" | cut -d: -f1)
    [ $((0x$_first & 1)) -eq 0 ]
}

locally_administered() { # $1 = MAC -> 0 = locally administered (a spoof signature)
    # The 0x02 bit is what a spoofer sets, and what no factory burn-in carries.
    # It is deliberately NOT part of usable_factory_target(): a locally
    # administered value is a perfectly legal thing to WRITE, and an illegal
    # thing to keep as evidence of what the device shipped with.
    valid_mac "$1" || return 1
    [ $((0x$(echo "$1" | cut -d: -f1) & 2)) -ne 0 ]
}

# --- NVRAM write ------------------------------------------------------------
# The window around $2 is computed once, here, and used by both the write and
# the dry run: a dry run must never be able to describe something the write
# would not do.  $4 is the file size, so the caller's own bound check is the one
# that decides whether the write is legal.
mac_window() { # $1=file $2=offset $3=mac $4=size -> W_START W_LEN W_PRE W_EXP
    W_START=$((($2) - 4))
    [ "$W_START" -lt 0 ] && W_START=0
    W_LEN=16
    [ $((W_START + W_LEN)) -le "$4" ] || W_LEN=$(($4 - W_START))
    W_PRE=$(hex_at "$1" "$W_START" "$W_LEN")
    W_EXP=$(splice_mac "$W_PRE" $(($2 - W_START)) "$(echo "$3" | tr -d ':')")
}

# In-place seek-and-write, never cp/mv over the calibration file (that changes
# the SELinux label and can truncate it).  count=6 bounds the write, and the
# verification compares a whole window around the offset against an image
# computed from the pre-write bytes, so it covers everything the write could
# have disturbed instead of only re-reading the offset just written.
write_mac() { # $1=file $2=mac $3=offsets
    _file=$1
    _mac=$2
    _size=$(wc -c <"$_file")
    _bytes=$(mac_bytes "$_mac") || die "$EX_WRITE" "cannot encode $_mac as bytes"
    for _o in $3; do
        [ $((_o + 6)) -le "$_size" ] \
            || die "$EX_PRECOND" "refusing to write $_mac at offset $_o: $_file is only $_size bytes"
        mac_window "$_file" "$_o" "$_mac" "$_size"
        $BB printf "%b" "$_bytes" | $BB dd of="$_file" bs=1 seek="$_o" count=6 conv=notrunc,fsync 2>/dev/null \
            || die "$EX_WRITE" "write to $_file failed at offset $_o"
        _post=$(hex_at "$_file" "$W_START" "$W_LEN")
        [ "$_post" = "$W_EXP" ] \
            || die "$EX_WRITE" "NVRAM write verification failed at offset $_o: expected $W_EXP, found $_post"
    done
    _now=$(wc -c <"$_file")
    [ "$_now" = "$_size" ] \
        || die "$EX_WRITE" "$_file changed size ($_size -> $_now): a truncated calibration file is not recoverable"
}

# --dry-run: the same bound check and the same window arithmetic as write_mac,
# printed instead of applied.  Touches nothing, not even $BAK.
plan_write() { # $1=file $2=mac $3=offsets
    _f=$1
    _m=$2
    _size=$(wc -c <"$_f")
    mac_bytes "$_m" >/dev/null || die "$EX_WRITE" "cannot encode $_m as bytes"
    say "[*] dry run: nothing was written"
    say "    file      : $_f ($_size bytes)"
    say "    new MAC   : $_m"
    for _o in $3; do
        [ $((_o + 6)) -le "$_size" ] \
            || die "$EX_PRECOND" "refusing to write $_m at offset $_o: $_f is only $_size bytes"
        mac_window "$_f" "$_o" "$_m" "$_size"
        _d=$(hex_diff "$W_PRE" "$W_EXP")
        say "    offset    : $_o"
        say "      old     : $(mac_at "$_f" "$_o")"
        say "      new     : $_m"
        say "      before  : $(hex_pairs "$W_PRE")"
        say "      after   : $(hex_pairs "$W_EXP")"
        if [ -n "$_d" ]; then
            say "      changes : $((W_START + ${_d% *}))..$((W_START + ${_d#* })) of $_f \
($(( ${_d#* } - ${_d% *} + 1 )) bytes, of which 6 are the MAC)"
        else
            say "      changes : nothing - $_f already holds $_m at offset $_o"
        fi
    done
}

# 'svc wifi enable' can be legally refused - WifiSettingsStore.handleWifiToggled
# returns early while airplane mode is on, an su policy can deny NETWORK_SETTINGS
# and a dead HAL refuses everything - and it exits 0 either way, while the driver
# re-reads NVRAM asynchronously.  A fixed wait therefore cannot tell "the driver
# read the NVRAM and kept its MAC" from "the restart has not happened yet", and
# the old 2 s + 6 s sleeps reported the second one as the first.  The observed
# state decides now, inside a bounded poll, and the return status names which of
# the cases happened:
#   0  observed    the wanted state was seen, or the driver published the MAC it
#                  was asked to load
#   1  the 'svc wifi enable' command itself failed
#   2  stuck       the state was read and never changed: WiFi did not toggle
#   3  unverified  this ROM reports no WiFi state at all, so only the MAC the
#                  driver publishes can be observed (the app's WIFI_UNVERIFIED)
# Only 'set' and 'wifi' turn that into a non-zero exit, and each says which case
# it was instead of blaming the MAC.
WIFI_POLL_TRIES=20      # 20 polls 0.5 s apart: the 10 s budget the app uses
WIFI_POLL_DOWN=4        # 4 polls (2 s) for the interface to actually go down

# One reading of the interface as "<state>|<mac>".  The state comes from the WiFi
# service's own report when it has one and from sysfs when it does not, and is
# '-' when this ROM reports neither.  It is matched by content, never by line
# position.  The MAC is the runtime address, which some ROMs expose as their only
# state signal at all.
wifi_state() {
    _wst=-
    _wraw=$(cmd wifi status 2>/dev/null)
    case "$_wraw" in
        *"Wifi is enabled"*)  _wst=enabled;;
        *"Wifi is disabled"*) _wst=disabled;;
    esac
    if [ "$_wst" = - ] && [ -r "$NET/$IF/operstate" ]; then
        _wst=$(tr -d ' \n' <"$NET/$IF/operstate" 2>/dev/null)
        [ -n "$_wst" ] || _wst=-
    fi
    _wmac=$(get_runtime)
    [ -n "$_wmac" ] || _wmac=-
    echo "$_wst|$_wmac"
}

reinit_wifi() { # [$1 = the MAC the driver should end up publishing]
    _want=$1
    info "[*] reinitialising WiFi (this briefly disconnects)..."
    _before=$(wifi_state)
    _bstate=${_before%%|*}
    _bmac=${_before#*|}
    svc wifi disable 2>/dev/null || info "[!] 'svc wifi disable' failed (WiFi is probably already off)"
    # The interface gets a short, bounded chance to go down, and only while this
    # ROM reports a state at all: with no state source there is nothing to
    # observe, and waiting for it would put the blind delay straight back.
    _down=
    if [ "$_bstate" != - ]; then
        _t=0
        while [ "$_t" -lt "$WIFI_POLL_DOWN" ]; do
            _s=$(wifi_state)
            case "${_s%%|*}" in
                disabled|down|dormant|lowerlayerdown) _down=1; break;;
            esac
            sleep 0.5
            _t=$((_t + 1))
        done
    fi
    if ! svc wifi enable 2>/dev/null; then
        say "[!] 'svc wifi enable' failed on this ROM: WiFi was NOT restarted, so the driver has"
        say "    not been asked to re-read the NVRAM yet."
        return 1
    fi
    # Poll for evidence that the restart happened.  With an expected MAC the
    # evidence that ends the poll early is that MAC itself - nothing less: the
    # interface coming back up, or a different MAC appearing, is recorded as
    # evidence that the restart happened but does NOT end the wait, because the
    # driver publishes the reloaded value asynchronously and stopping early would
    # report the slow reload as a driver refusal (the bug this poll exists for).
    # Without an expected MAC (a bare restart) that evidence is the answer.
    _seen=
    _t=0
    while [ "$_t" -lt "$WIFI_POLL_TRIES" ]; do
        _s=$(wifi_state)
        _sw=${_s%%|*}
        _sm=${_s#*|}
        if [ -n "$_want" ] && [ "$_sm" = "$_want" ]; then
            _seen=1
            break
        fi
        case "$_sw" in
            -|disabled|down|dormant|lowerlayerdown) ;;
            *)
                # The interface is back up after having been seen down.
                if [ -n "$_down" ]; then
                    _seen=1
                    [ -n "$_want" ] || break
                fi
                ;;
        esac
        if [ "$_sm" != - ] && [ "$_sm" != "$_bmac" ]; then
            # The driver republished a MAC, so it re-read the NVRAM.
            _seen=1
            [ -n "$_want" ] || break
        fi
        sleep 0.5
        _t=$((_t + 1))
    done
    if [ -n "$_seen" ]; then
        return 0
    fi
    if [ "$_bstate" = - ]; then
        return 3
    fi
    return 2
}

# --- factory image ----------------------------------------------------------
# A "factory" record is only ever captured from an NVRAM image that still holds
# the MAC the driver is using.  A capture taken while the file already holds a
# spoof would enshrine that spoof as the factory value - a loss no later
# command can undo - so 'restore' and 'show' never capture, and a capture whose
# evidence is missing needs an explicit --assume-factory acknowledgement.
has_sha() { $BB sha256sum </dev/null >/dev/null 2>&1; }
sha_of()  { $BB sha256sum <"$1" 2>/dev/null | $BB awk '{ print $1 }'; }

# $BAK.offset records where the MAC field sits in the captured image: a plain
# integer, or '?N' when the capture was forced with --assume-factory and the
# offset was assumed (the documented MTK offset 4) rather than located.
BAK_OFF=
BAK_ASSUMED=
read_bak_offset() {
    BAK_OFF=
    BAK_ASSUMED=
    [ -f "$BAK.offset" ] || return 1
    _o=$(cat "$BAK.offset" 2>/dev/null)
    case "$_o" in
        \?*) BAK_ASSUMED=1; _o=${_o#\?};;
    esac
    case "$_o" in
        ''|*[!0-9]*) return 1;;
    esac
    BAK_OFF=$_o
    return 0
}

# Digest state of the recorded image -> BAK_DIGEST = ok | missing | mismatch |
# none.  Every command that reads or writes back the image asks this, so they
# cannot disagree about whether the record can be trusted.
BAK_DIGEST=
bak_digest_state() {
    BAK_DIGEST=none
    [ -f "$BAK" ] || return 1
    if [ ! -f "$BAK.sha256" ] || ! has_sha; then
        BAK_DIGEST=missing
        return 0
    fi
    _want=$($BB awk '{ print $1 }' "$BAK.sha256" 2>/dev/null)
    if [ -z "$_want" ]; then
        BAK_DIGEST=missing
    elif [ "$_want" = "$(sha_of "$BAK")" ]; then
        BAK_DIGEST=ok
    else
        BAK_DIGEST=mismatch
    fi
    return 0
}

verify_bak_digest() { # 0 = ok, 1 = no digest recorded, 2 = mismatch
    bak_digest_state || return 1
    case "$BAK_DIGEST" in
        ok)       return 0;;
        mismatch) return 2;;
        *)        return 1;;
    esac
}

# Can the recorded image be written back at all, and by which command?
#
# This is the question 'doctor' and 'set' both used to get wrong.  'restore'
# writes only an image exactly as long as the target; 'panic' also accepts a
# LONGER one (it restores a truncated target) but refuses a shorter one, because
# writing it would leave the tail of the current file in place.  So an image that
# is shorter than the file it would be written over is not a way back at all -
# neither command can put it back - and a record in that state must never be
# reported as "ready", nor promised as "restore writes it back".  Sets BAK_WB
# (restore | panic-only | short | none) and BAK_NOTE, one line, for reporting.
BAK_WB=
BAK_NOTE=
bak_writeback_state() {
    BAK_WB=none
    BAK_NOTE="no image at $BAK - nothing can be written back"
    [ -e "$BAK" ] || return 0
    if [ ! -f "$BAK" ]; then
        BAK_NOTE="$BAK is not a regular file - neither 'restore' nor 'panic' will write it back"
        return 0
    fi
    _b=$(wc -c <"$BAK" 2>/dev/null)
    _n=$(wc -c <"$NV" 2>/dev/null)
    if [ -z "$_b" ] || [ -z "$_n" ]; then
        BAK_NOTE="cannot compare sizes: $BAK is ${_b:-unreadable} and $NV is ${_n:-unreadable}"
        return 0
    fi
    if [ "$_b" -lt "$_n" ]; then
        BAK_WB=short
        BAK_NOTE="NO - $_b bytes for a $_n-byte $NV: 'restore' demands equal sizes and 'panic' \
refuses a short image, so NEITHER can put it back"
    elif [ "$_b" -gt "$_n" ]; then
        BAK_WB=panic-only
        BAK_NOTE="panic only - $_b bytes against a $_n-byte $NV: 'restore' refuses unequal sizes, \
'panic' restores it"
    else
        BAK_WB=restore
        BAK_NOTE="'restore' and 'panic' - $_b bytes, the same as $NV"
    fi
    return 0
}

# The way back that actually exists, said in one place so no command promises one
# that does not: '$0 restore' is only mentioned when the image is the target's
# size, and the unverified case is labelled as such instead of being called "the
# factory MAC".  Reads only; writes nothing.
say_way_back() {
    bak_writeback_state
    if [ "$BAK_WB" = restore ] || [ "$BAK_WB" = panic-only ]; then
        if read_bak_offset && [ -n "$BAK_ASSUMED" ]; then
            say "    ($BAK was captured without confirming its MAC field: what it puts back is the"
            say "     value $NV held then, which is not proof of a factory MAC.)"
        fi
    fi
    case "$BAK_WB" in
        restore)    say "    '$0 restore' writes $BAK back in place.";;
        panic-only) say "    only '$0 panic' can write $BAK back: it is longer than $NV.";;
        short)      say "    $BAK is shorter than $NV, so NEITHER '$0 restore' nor '$0 panic' can"
                    say "    put it back - only a full-size image of this path from elsewhere can.";;
        *)          say "    there is no factory image at $BAK: nothing can put the factory MAC back.";;
    esac
    return 0
}

# Where the image was captured from, when the writer recorded it (the app writes
# the same file, WIFI.factory.path).  An image captured on another vendor's path
# must never be written into this one, even when the sizes happen to match.
bak_source_path() {
    [ -f "$BAK.path" ] || return 1
    _src=$(cat "$BAK.path" 2>/dev/null | sed -n '1p')
    [ -n "$_src" ] || return 1
    echo "$_src"
    return 0
}

# The label for the value at $BAK.offset, for the commands that report what they
# just put back.  "factory" is an evidential claim, not a name for the file: it
# is only true for a value that was LOCATED by scanning for the live MAC and is
# itself a plausible factory burn-in.  An image captured with --assume-factory
# ('?N' in $BAK.offset) never established that, and neither has a value that is
# locally administered or is not a usable station address, so those are reported
# as "recorded NVRAM MAC" - the same distinction doctor and show draw.
bak_mac_label() {
    _l="factory NVRAM MAC"
    if [ -z "$BAK_ASSUMED" ]; then
        _v=$(mac_at "$BAK" "$BAK_OFF")
        if valid_mac "$_v"; then
            if ! usable_factory_target "$_v"; then
                _l="recorded NVRAM MAC (not a usable station address)"
            elif locally_administered "$_v"; then
                _l="recorded NVRAM MAC (locally administered, not a factory burn-in)"
            fi
        fi
    else
        _l="recorded NVRAM MAC"
    fi
    printf '%s' "$_l"
}

# "(offset 4)" or "(offset 4, ASSUMED - unverified)" for the same report lines.
bak_offset_label() {
    if [ -n "$BAK_ASSUMED" ]; then
        printf 'offset %s, ASSUMED - unverified' "$BAK_OFF"
    else
        printf 'offset %s' "$BAK_OFF"
    fi
}

# The capture decision, separated from the capture itself so --dry-run can report
# exactly what a real run would do.  Sets CAP_OFF, CAP_MODE (located | assumed-la
# | assumed) and CAP_REFUSE; CAP_REFUSE non-empty means it must not capture.
CAP_OFF=
CAP_MODE=
CAP_REFUSE=
CAP_RUNTIME=
capture_decide() { # $1 = the live runtime MAC (may be empty/unknown)
    _runtime=$1
    _off=
    CAP_OFF=
    CAP_MODE=
    CAP_REFUSE=
    CAP_RUNTIME=$1
    if valid_mac "$_runtime"; then
        scan_mac "$NV" "$(echo "$_runtime" | tr -d ':')"
        [ "$SCAN_N" -gt 0 ] && _off=$(echo "$SCAN_OFFS" | $BB awk '{ print $1 }')
    fi
    if [ -n "$_off" ]; then
        # A multicast, all-zero or all-0xFF value that the driver is publishing
        # cannot be a factory burn-in, and unlike the locally administered case
        # there is nothing for --assume-factory to acknowledge: the address is
        # not a station address at all.  Recording it would put it in
        # $DIR/factory.txt, the record the app gates 'Restore factory' on, where
        # the app's own restoreRefusal() rejects it - so the CLI would have
        # broken the app's recovery path with a value it could never have
        # captured itself.
        if ! usable_factory_target "$_runtime"; then
            CAP_REFUSE="refusing to record $_runtime as the factory MAC: it is not a usable \
station address - the multicast/broadcast bit (0x01 of the first octet) is set, or the address is \
all-zero or all-0xFF. No device is burned in with such an address and no randomizer produces one, so \
$NV holding it does not make it factory evidence. --assume-factory is not accepted here either: that \
flag acknowledges a plausibly-spoofed value, not one that can never be a station MAC. If the NVRAM \
really holds $_runtime, put a known-good factory image of $NV at $BAK first (a copy you kept off the \
device), then run '$0 set MAC'"
            return 0
        fi
        if locally_administered "$_runtime"; then
            if [ -z "$ASSUME" ]; then
                CAP_REFUSE="refusing to record $_runtime as the factory MAC: it is a \
locally administered address, the bit every randomizer sets (including '$0 random'), so $NV \
already holds a spoof, and an image taken now would make '$0 restore' put that spoof back. \
'$0 set MAC' does not get around this either: it captures through this same decision. If you \
know the real factory MAC and accept that the record kept is of this address, \
'$0 set MAC --assume-factory' writes it anyway; otherwise put a known-good factory image back \
at $BAK first (a copy you kept off the device), then run '$0 set MAC'"
                return 0
            fi
            CAP_MODE=assumed-la
        else
            CAP_MODE=located
        fi
        CAP_OFF=$_off
        return 0
    fi
    if [ -z "$ASSUME" ]; then
        CAP_REFUSE="refusing to record $NV as the factory value: it does not \
hold the live runtime MAC (${_runtime:-nothing}), so a capture now could enshrine a spoof as \
the factory value. '$0 set MAC' does not get around this either: it captures through this same \
decision. Re-run with --assume-factory to capture the file anyway, recording the value at the \
documented MTK offset as UNVERIFIED, or put a known-good factory image back at $BAK first (a \
copy you kept off the device), then run '$0 set MAC'"
        return 0
    fi
    # The documented MTK offset, used only because the user acknowledged that the
    # live MAC cannot be found to confirm it.
    CAP_OFF=4
    CAP_MODE=assumed
    return 0
}

# The lines that precede a capture.  $1 = real | dry.
capture_report() {
    if [ "$1" = dry ]; then _v="would capture"; else _v="capturing"; fi
    case "$CAP_MODE" in
        located)
            info "[*] $_v factory image of $NV (MAC field at offset $CAP_OFF = $CAP_RUNTIME)"
            ;;
        assumed-la)
            say "[!] --assume-factory: $CAP_RUNTIME is locally administered, so this image is"
            say "    recorded as UNVERIFIED."
            info "[*] $_v factory image of $NV (MAC field at offset $CAP_OFF = $CAP_RUNTIME)"
            ;;
        assumed)
            say "[!] --assume-factory: $_v $NV without confirming its MAC field;"
            say "    the value at offset $CAP_OFF is recorded as UNVERIFIED."
            ;;
    esac
}

capture_backup() { # $1 = the live runtime MAC (may be empty/unknown)
    capture_decide "$1"
    [ -n "$CAP_REFUSE" ] && die "$EX_PRECOND" "$CAP_REFUSE"
    capture_report real

    [ -L "$BAK" ] && die "$EX_NOBACKUP" "$BAK is a symlink: refusing to capture an image through it"
    mkdir -m 700 -p "$DIR" || die "$EX_NOBACKUP" "cannot create $DIR"
    chmod 700 "$DIR" 2>/dev/null
    _size=$(wc -c <"$NV")
    # fsync so the image is on stable storage before the NVRAM is touched: it is
    # the only way back.  An old busybox without conv=fsync still gets the image,
    # just without the barrier.
    $BB dd if="$NV" of="$BAK" bs=4096 conv=fsync 2>/dev/null \
        || $BB dd if="$NV" of="$BAK" bs=4096 2>/dev/null \
        || die "$EX_NOBACKUP" "cannot save the factory image to $BAK"
    chmod 600 "$BAK" 2>/dev/null
    [ "$(wc -c <"$BAK")" = "$_size" ] \
        || die "$EX_NOBACKUP" "short capture: $BAK is $(wc -c <"$BAK") bytes, $NV is $_size bytes"
    # The MAC the image is claimed to hold is read back out of the image itself:
    # a record that does not contain what it says it contains is worthless.
    _inbak=$(mac_at "$BAK" "$CAP_OFF")
    if [ "$CAP_MODE" = located ] && [ "$_inbak" != "$CAP_RUNTIME" ]; then
        die "$EX_NOBACKUP" "capture check failed: $BAK holds ${_inbak:-nothing} at offset $CAP_OFF, \
expected $CAP_RUNTIME. Not continuing with an image that does not hold what it was captured for"
    fi
    if [ -n "$CAP_MODE" ] && [ "$CAP_MODE" != located ]; then
        echo "?$CAP_OFF" >"$BAK.offset"
    else
        echo "$CAP_OFF" >"$BAK.offset"
    fi
    chmod 600 "$BAK.offset" 2>/dev/null
    # Provenance: which calibration path this image came from.  Same name the app
    # uses, so either front end can tell a foreign image from a usable one.
    echo "$NV" >"$BAK.path"
    chmod 600 "$BAK.path" 2>/dev/null
    if has_sha; then
        sha_of "$BAK" >"$BAK.sha256"
        chmod 600 "$BAK.sha256" 2>/dev/null
        if [ "$(cat "$BAK.sha256")" != "$(sha_of "$BAK")" ]; then
            die "$EX_NOBACKUP" "the digest recorded for $BAK does not match the image on disk"
        fi
        say "[*] saved factory image -> $BAK (offset $CAP_OFF, sha256 $(cat "$BAK.sha256"))"
    else
        say "[!] busybox sha256sum is unavailable: the factory image is stored without a digest"
        say "[*] saved factory image -> $BAK (offset $CAP_OFF)"
    fi
    [ -n "$CAP_MODE" ] && [ "$CAP_MODE" != located ] \
        && say "    the MAC in that image is UNVERIFIED - '$0 doctor' and '$0 show' report it as such."
    record_factory_txt
    return 0
}

# $DIR/factory.txt is the app's text record of the factory value: line 1 the MAC,
# line 2 its provenance ("<path>@0x<offset>", the format the app's own
# TEXT_SCRIPT writes).  Without it the app's 'Restore factory' refuses - it gates
# on a recorded value before it ever considers the saved image - so a record
# captured by the CLI was invisible to the app even though both read the same
# directory.  It is written ONLY from a located capture: the value at the matched
# offset that the live MAC proves, which is the same evidence the image itself
# rests on.  An --assume-factory capture must NEVER be offered to the app as "the
# factory MAC", or the app would restore the spoof the CLI refused to enshrine.
# An existing record holding a different MAC is never overwritten silently: it
# may be a value the user typed, or a record for another calibration path.
record_factory_txt() {
    [ "$CAP_MODE" = located ] || return 0
    [ -n "$CAP_OFF" ] || return 0
    _m=$(mac_at "$BAK" "$CAP_OFF")
    valid_mac "$_m" || return 0
    # Defence in depth: $DIR/factory.txt is the record the app gates 'Restore
    # factory' on, and the app rejects a multicast, all-zero or all-0xFF value
    # there.  capture_decide already refuses to capture such a value, so this
    # can only fire if that guard is ever weakened - and if it does, the right
    # answer is still to write nothing rather than to hand the app a value it
    # will refuse.
    usable_factory_target "$_m" || return 0
    if [ -f "$DIR/factory.txt" ]; then
        _have=$(sed -n '1p' "$DIR/factory.txt" 2>/dev/null)
        if [ -n "$_have" ] && [ "$(norm_mac "$_have")" != "$_m" ]; then
            say "[!] $DIR/factory.txt already records $_have, not $_m: keeping it (the app reads that"
            say "    file, and a record is never overwritten silently). Delete it to have this capture"
            say "    recorded there instead."
            return 0
        fi
    fi
    _hexoff=$($BB awk -v o="$CAP_OFF" 'BEGIN{ printf "%x", o }')
    # Atomically, with a temp file and a rename: $DIR/factory.txt is the record
    # the app reads, and a crash part-way through a plain '>' leaves a truncated
    # first line that the app - and every later run of this CLI - then treats as
    # the recorded value ("10:20:3"), which is a sticky bad state.  The app's own
    # TEXT_SCRIPT writes it the same way.  The rename is over the record, never
    # over the calibration file, so the inode/SELinux rule the NVRAM write
    # follows does not apply here.
    _tmp="$DIR/factory.txt.tmp.$$"
    if printf '%s\n%s\n' "$_m" "$NV@0x$_hexoff" >"$_tmp" 2>/dev/null; then
        chmod 600 "$_tmp" 2>/dev/null
        mv -f "$_tmp" "$DIR/factory.txt" 2>/dev/null || { rm -f "$_tmp" 2>/dev/null; return 0; }
    else
        rm -f "$_tmp" 2>/dev/null
        return 0
    fi
    info "[*] app record    : $DIR/factory.txt ($_m, from $NV at offset $CAP_OFF)"
    return 0
}

# Called by set/random: create the factory image only when it is missing.  An
# existing image is never overwritten - it is the only way back - and an image
# that no longer matches its recorded digest, or that is too short to be written
# back at all, stops the change instead of being quietly relied on.
ensure_backup() {
    if [ -f "$BAK" ]; then
        [ -L "$BAK" ] && die "$EX_NOBACKUP" "$BAK is a symlink: refusing to rely on it"
        # The size relation first: an image shorter than the target is not a way
        # back, so a write made while relying on it is a write with no way back,
        # which is the one thing this tool refuses.  It is checked before the
        # digest because it is the stronger fact - a digest can only say the
        # image is intact, never that it can be restored.
        bak_writeback_state
        if [ "$BAK_WB" = short ]; then
            die "$EX_NOBACKUP" "$BAK_NOTE. Refusing to change the MAC on a record that cannot be \
written back: capture a full-size image instead - delete $BAK and run '$0 backup' while $NV still \
holds the MAC you want to keep - or put a known-good full-size image of $NV at $BAK first"
        fi
        if [ "$BAK_WB" = panic-only ]; then
            say "[!] $BAK_NOTE: '$0 restore' will refuse it later, '$0 panic' will not."
        fi
        verify_bak_digest
        case $? in
            0) ;;
            1) say "[!] $BAK exists but has no digest to verify it against";;
            *) if [ -n "$ALLOW_DAMAGED" ]; then
                   say "[!] --allow-damaged-backup: $BAK does NOT match its recorded digest and is"
                   say "    being kept anyway; if it is damaged, this MAC change may not be reversible"
               else
                   die "$EX_NOBACKUP" "$BAK DOES NOT MATCH the digest recorded when it was captured, \
so the only way back may be damaged. Refusing to change the MAC. Restore a known-good copy of $BAK, \
delete it to capture a fresh image while $NV still holds the MAC you want to keep, or pass \
--allow-damaged-backup to accept the risk"
               fi;;
        esac
        # The same provenance check 'panic' makes before it writes this image
        # back.  Leaving it out here meant 'set' would write the NVRAM while
        # relying on a record that the recovery command declares untrustworthy,
        # and 'doctor' called that device ready - the precondition for a
        # destructive write was weaker than the recovery path's.
        if _src=$(bak_source_path) && [ "$_src" != "$NV" ]; then
            if [ -n "$ALLOW_FOREIGN" ]; then
                say "[!] --allow-foreign: $BAK records that it came from $_src, not from $NV"
            else
                die "$EX_NOBACKUP" "$BAK records that it was captured from $_src, not from $NV, so it \
proves nothing about this file and is not a way back from changing this one. Put a known-good image of \
$NV (with its $BAK.path, or with no $BAK.path at all) at $BAK first, delete $BAK to capture a fresh \
image while $NV still holds the MAC you want to keep, or pass --allow-foreign if you are certain this \
is the right image for this path"
            fi
        fi
        return 0
    fi
    capture_backup "$1"
}

cmd_backup() {
    if [ -f "$BAK" ]; then
        if [ -n "$DRY" ]; then
            say "[*] dry run: nothing was written"
            say "    '$0 backup' would REFUSE: a factory image already exists at $BAK \
($(wc -c <"$BAK") bytes)"
            return "$EX_NOBACKUP"
        fi
        die "$EX_NOBACKUP" "a factory image already exists at $BAK ($(wc -c <"$BAK") bytes); \
refusing to overwrite it - delete it first only if you know it is wrong"
    fi
    require_root
    require_nvram
    require_iface
    # A capture reads the whole NVRAM and writes the record the app reads; it is
    # one of the operations the app's lock exists for, so it takes the same lock.
    [ -n "$DRY" ] || lock_acquire
    cur=$(get_runtime)
    if [ -n "$DRY" ]; then
        # The real run's preconditions are evaluated here, so the dry run's exit
        # status is the exit status of the run it describes.
        CAP_RUNTIME=$cur
        capture_decide "$cur"
        if [ -n "$CAP_REFUSE" ]; then
            say "[*] dry run: nothing was written"
            say "    '$0 backup' would REFUSE: $CAP_REFUSE"
            return "$EX_PRECOND"
        fi
        info "[*] interface $IF, runtime MAC ${cur:-(unreadable)}"
        capture_report dry
        say "[*] dry run: nothing was written"
        say "    would write: $BAK ($(wc -c <"$NV") bytes) + $BAK.offset + $BAK.path + $BAK.sha256"
        say "                 in $DIR (dir mode 700, files mode 600)"
        return 0
    fi
    info "[*] interface $IF, runtime MAC ${cur:-(unreadable)}"
    capture_backup "$cur"
    info "[*] '$0 restore' writes this image back in place."
}

cmd_set() {
    [ -n "$1" ] || die "$EX_USAGE" "usage: $0 set AA:BB:CC:DD:EE:FF [--iface=NAME]"
    mac=$(norm_mac "$1")
    valid_mac "$mac" || die "$EX_USAGE" "invalid MAC address: $1"
    case "$mac" in
        00:00:00:00:00:00) die "$EX_USAGE" "refusing to set an all-zero MAC";;
        ff:ff:ff:ff:ff:ff) die "$EX_USAGE" "refusing to set a broadcast MAC";;
    esac

    first=$(echo "$mac" | cut -d: -f1)
    [ $((0x$first & 1)) -eq 0 ] \
        || die "$EX_USAGE" "$mac is a MULTICAST address; no driver accepts it as a station MAC"

    # Root first: a non-root caller must get EX_NOTROOT (2), not a lock error,
    # and must not be able to leave anything behind in $DIR.
    require_root
    # Then the lock, taken before the NVRAM is READ rather than only before it is
    # written: what this command patches is the state it read, and the app's own
    # reason for the lock is a reader/copier racing a full-image write.
    [ -n "$DRY" ] || lock_acquire
    require_nvram
    require_iface
    cur=$(get_runtime)
    valid_mac "$cur" || die "$EX_PRECOND" "the live MAC of $IF is ${cur:-(unreadable)}: refusing to write \
blind - pass --iface=NAME if $IF is the wrong interface"

    # The MAC field is located, never assumed: the bytes at every offset
    # written must be the MAC the driver is actually using, so the precondition
    # is checked before anything is written.
    scan_mac "$NV" "$(echo "$cur" | tr -d ':')"
    [ "$SCAN_N" -gt 0 ] \
        || die "$EX_PRECOND" "the live MAC $cur was not found anywhere in $NV (bytes 0-15: \
$(hex_at "$NV" 0 16)); refusing to write blind - pass --iface=NAME if $IF is the wrong interface"
    [ "$SCAN_N" -le 8 ] \
        || die "$EX_PRECOND" "$cur occurs $SCAN_N times in $NV; too ambiguous to patch safely"

    if [ -n "$DRY" ]; then
        say "[*] interface : $IF"
        say "[*] current   : $cur (found at offset(s) $SCAN_OFFS in $NV)"
        plan_write "$NV" "$mac" "$SCAN_OFFS"
        # What a real run would do about the way back, and the status it would
        # exit with, so a dry run can be scripted like the run it describes.
        if [ ! -f "$BAK" ]; then
            say "      factory : would be captured first -> $BAK"
            return 0
        fi
        # The same size rule ensure_backup applies: a record that cannot be
        # written back is refused by the real run, so it is refused here too
        # rather than reported as a record that will be kept.
        bak_writeback_state
        if [ "$BAK_WB" = short ]; then
            say "      factory : a real run would REFUSE: $BAK_NOTE"
            return "$EX_NOBACKUP"
        fi
        [ "$BAK_WB" = panic-only ] && say "      factory : kept ($BAK_NOTE)"
        bak_digest_state
        case "$BAK_DIGEST" in
            ok)      say "      factory : kept ($BAK, digest ok)"; return 0;;
            missing) say "      factory : kept ($BAK, no digest recorded - a real run warns)"; return 0;;
            *)       say "      factory : a real run would REFUSE: $BAK does not match its recorded digest"
                     if [ -n "$ALLOW_DAMAGED" ]; then
                         say "                (--allow-damaged-backup was given: it would proceed)"
                         return 0
                     fi
                     return "$EX_NOBACKUP";;
        esac
    fi

    ensure_backup "$cur"

    info "[*] writing NVRAM ($NV) at byte offset(s) $SCAN_OFFS ..."
    write_mac "$NV" "$mac" "$SCAN_OFFS"

    # Reliable apply: full WiFi restart so the driver re-reads NVRAM.
    # Do NOT touch ip link here - it crashes the WiFi service on MTK.
    # The MAC the driver should end up publishing is passed in, so the wait
    # happens inside reinit_wifi's poll: a slow-but-legal reload is reported as
    # "not observed yet", not as the driver refusing the MAC, and there is no
    # fixed sleep afterwards to read the runtime MAC regardless of the state.
    reinit_wifi "$mac"
    _restarted=$?

    now=$(get_runtime)
    say "[+] nvram   MAC : $(mac_at "$NV" "$(echo "$SCAN_OFFS" | $BB awk '{ print $1 }')")  (offset $SCAN_OFFS)"
    say "[+] runtime MAC : ${now:-nothing}"
    if [ "$now" != "$mac" ]; then
        say "[!] the driver is NOT using $mac: the change did not take effect."
        case "$_restarted" in
            1) say "    'svc wifi enable' failed, so WiFi was never restarted and the driver has"
               say "    not been asked to re-read $NV yet.";;
            2) say "    WiFi did not toggle within the $WIFI_POLL_TRIES-poll wait ($IF still reads"
               say "    ${now:-nothing}): the toggle was probably refused (airplane mode on?).";;
            3) say "    the restart was issued, but this ROM reports no WiFi state to read it back"
               say "    from ('cmd wifi status' and $IF/operstate are both silent), and the driver"
               say "    never published $mac, so nothing shows that it re-read $NV.";;
            *) say "    $IF was re-initialised and still reads ${now:-nothing} after the"
               say "    $WIFI_POLL_TRIES-poll wait, so the reload did not take $mac from $NV.";;
        esac
        say "    reboot to apply it."
        say_way_back
        return "$EX_APPLY"
    fi
    say "[!] your whitelisted WiFi will drop unless this MAC is whitelisted."
    say_way_back
    return 0
}

cmd_random() {
    r=$($BB od -An -N5 -tx1 /dev/urandom | $BB awk '{printf "02:%s:%s:%s:%s:%s",$1,$2,$3,$4,$5}')
    valid_mac "$r" || die "$EX_TOOL" "cannot generate a random MAC (busybox od/awk failed)"
    info "[*] random MAC: $r"
    cmd_set "$r"
}

cmd_wifi() {
    # A restart IS the command: there is nothing to preview, and --dry-run is
    # parsed globally, so it must be refused rather than accepted and then
    # ignored while the radio is toggled for real and the user is disconnected.
    [ -n "$DRY" ] && die "$EX_USAGE" "--dry-run is not available for 'wifi': restarting the \
radio is the whole command, there is nothing to preview. Use 'set --dry-run' to see a write plan, or \
'doctor' to inspect the device without changing it"
    require_root
    require_nvram
    require_iface
    # No MAC is expected from this command, so the restart itself is what is
    # observed.  The three answers get three different verdicts instead of the
    # old "it was restarted, so any MAC you see is the truth".
    reinit_wifi
    _wrc=$?
    now=$(get_runtime)
    say "[+] runtime MAC : ${now:-nothing}"
    if [ "$_wrc" = 1 ]; then
        say "[!] WiFi was not restarted (see above), so the driver has not re-read $NV."
        return "$EX_APPLY"
    fi
    if [ "$_wrc" = 2 ]; then
        say "[!] WiFi did not toggle within the $WIFI_POLL_TRIES-poll wait: the toggle was probably"
        say "    refused (airplane mode on?), so the driver has not re-read $NV."
        return "$EX_APPLY"
    fi
    if [ "$_wrc" != 0 ]; then
        # 'set' exits 9 in this exact state, and for the same reason: the
        # restart was issued, this ROM reports no WiFi state, so whether the
        # driver re-read the NVRAM is unknown.  A caller doing
        # 'macchanger.sh wifi && echo reloaded' must not be told "reloaded"
        # about a restart nobody could observe.
        say "[!] the restart was issued but not observed: this ROM reports no WiFi state to read"
        say "    back from ('cmd wifi status' and $IF/operstate are both silent), so whether the"
        say "    driver re-read $NV is unknown."
        return "$EX_APPLY"
    fi
    valid_mac "$now" || { say "[!] $IF reports no usable MAC"; return "$EX_IFACE"; }
    return 0
}

cmd_restore() {
    require_root
    require_nvram
    [ -f "$BAK" ] \
        || die "$EX_NOBACKUP" "no factory backup at $BAK - capture it before spoofing (run '$0 backup' \
while the NVRAM still holds the MAC you want to keep), or apply a factory MAC you recorded \
elsewhere with '$0 set MAC'"
    [ -L "$BAK" ] && die "$EX_NOBACKUP" "$BAK is a symlink: refusing to restore from a link"

    # The same lock the app takes for the same file.  --dry-run writes nothing,
    # so it takes nothing.
    [ -n "$DRY" ] || lock_acquire

    # In-place and length-checked: 'cp' over a calibration file truncates it and
    # changes its SELinux label, and a short image would destroy everything past
    # its last byte.  The inode is kept by writing through an open handle.
    size_bak=$(wc -c <"$BAK")
    size_nv=$(wc -c <"$NV")
    [ "$size_bak" = "$size_nv" ] \
        || die "$EX_NOBACKUP" "backup size mismatch: $BAK is $size_bak bytes but $NV is $size_nv bytes; \
refusing to write (a short image would truncate the calibration file)$( \
[ "$size_nv" -lt "$size_bak" ] && printf '%s' "; '$0 panic' can put a longer image back, because it \
verifies the result instead of demanding equal sizes")"

    verify_bak_digest
    case $? in
        0) ;;
        2) die "$EX_NOBACKUP" "$BAK does not match the digest recorded when it was captured \
($BAK.sha256); refusing to write a damaged image";;
        *) say "[!] no digest recorded for $BAK: verifying with cmp only";;
    esac
    # A sanity note, not a refusal: the image is intact (digest above), but an
    # image captured from another calibration path may still be the wrong one.
    if _src=$(bak_source_path) && [ "$_src" != "$NV" ]; then
        say "[!] $BAK records that it came from $_src, not from $NV; restoring it here anyway \
because it is the image you asked for"
    fi

    # An intact image is not automatically a factory image.  A recorded value
    # that is locally administered is the signature of a spoof, so an image
    # holding one was captured with --assume-factory (or written by another
    # tool).  Writing it back and exiting 0 would report success for a write
    # this tool has just refused to call a factory MAC -- 'doctor' calls the
    # same record a blocker -- so it is refused here and the caller has to say
    # what they mean.
    if read_bak_offset; then
        _rmac=$(mac_at "$BAK" "$BAK_OFF")
        if locally_administered "$_rmac" && [ -z "$ALLOW_NONFACTORY" ]; then
            die "$EX_NOBACKUP" "the recorded MAC in $BAK is $_rmac, which is LOCALLY ADMINISTERED: \
that is the signature of a spoof, not of a factory burn-in, so this image is not evidence of what \
the device shipped with.  Refusing to write it back as the factory MAC.  If you really mean to put \
that value back, say so: '$0 restore --allow-nonfactory-image'"
        fi
    fi

    if [ -n "$DRY" ]; then
        say "[*] dry run: nothing was written"
        say "    would write : $BAK ($size_bak bytes) -> $NV in place (conv=notrunc,fsync, same inode)"
        say "    sizes       : $size_bak -> $size_nv (unchanged)"
        if read_bak_offset; then
            say "    MAC in image: $(mac_at "$BAK" "$BAK_OFF") (offset $BAK_OFF)"
        else
            say "    MAC in image: (offset not recorded; the image would be written byte-for-byte)"
        fi
        return 0
    fi

    info "[*] restoring the factory image in place (same inode, same SELinux label) ..."
    $BB dd if="$BAK" of="$NV" bs=4096 conv=notrunc,fsync 2>/dev/null \
        || die "$EX_WRITE" "restore failed: writing $BAK over $NV did not complete"
    [ "$(wc -c <"$NV")" = "$size_nv" ] \
        || die "$EX_WRITE" "restore verification failed: $NV is $(wc -c <"$NV") bytes, expected $size_nv"
    $BB cmp -s "$BAK" "$NV" || die "$EX_WRITE" "restore verification failed: $NV does not match $BAK"

    # The verdict is the verified image itself, and the MAC printed is re-read
    # from the target - never from the backup helper and never from a runtime
    # value that a randomized network can hide.
    restored=
    if read_bak_offset; then
        restored=$(mac_at "$NV" "$BAK_OFF")
        say "[*] $(bak_mac_label) re-read from $NV ($(bak_offset_label)): ${restored:-unreadable}"
    else
        say "[*] factory image restored and verified byte-for-byte against $BAK"
    fi

    if [ -n "$NO_REINIT" ]; then
        say "[*] --no-reinit: WiFi was not restarted; reboot (or run '$0 wifi') to reload the NVRAM"
        return 0
    fi
    pick_iface
    if [ -z "$IF" ]; then
        say "[!] $IF_ERR"
        say "[!] the image is in place and verified; the runtime MAC could not be checked."
        return 0
    fi
    # The restored value is passed in, so the poll waits for the driver to
    # publish it rather than for a fixed number of seconds; nothing below can
    # turn this into a failure, because the file is already verified.
    reinit_wifi "$restored" \
        || say "    (the image is in place and verified regardless; reboot to apply it)"
    now=$(get_runtime)
    say "[+] runtime MAC : ${now:-nothing}"
    if [ -n "$restored" ] && [ "$now" != "$restored" ]; then
        say "[!] the runtime MAC is not the restored value ($restored); reboot if the driver did not reload it."
    fi
    return 0
}

# --- panic / undo -----------------------------------------------------------
# The disaster path, and the reason it is separate from 'restore': it needs
# nothing to be readable except the recorded image and the size of the target,
# so it still works when the live NVRAM holds garbage, when the runtime MAC is
# unknown and when no interface can be resolved.  It refuses rather than
# guesses: no image, an image that fails its digest, an image recorded from a
# different path, or an image that would leave part of the current file in
# place.  Its exit status is the verified state of the file, not the driver's.
cmd_panic() {
    require_root
    [ -e "$NV" ] || die "$EX_DEVICE" "$NV does not exist. Refusing to create it: a newly created \
file would carry the wrong SELinux label, which is exactly why this tool never cp/mv over a \
calibration file. Restore the partition from a dump of the same model, or reflash it."
    [ -f "$NV" ] || die "$EX_DEVICE" "$NV is not a regular file; refusing to write it"
    [ -f "$BAK" ] || die "$EX_NOBACKUP" "no factory image at $BAK, so there is nothing trustworthy \
to restore from. Recover a copy of the file for this model, or apply a factory MAC you recorded \
elsewhere with '$0 set MAC'."
    [ -L "$BAK" ] && die "$EX_NOBACKUP" "$BAK is a symlink: refusing to restore from a link"

    # The same lock the app takes for the same file; --dry-run takes nothing.
    [ -n "$DRY" ] || lock_acquire

    bak_size=$(wc -c <"$BAK")
    nv_size=$(wc -c <"$NV")

    verify_bak_digest
    case $? in
        0) ;;
        2) die "$EX_NOBACKUP" "$BAK does not match the digest recorded when it was captured \
($BAK.sha256), so it is damaged. Refusing to write it over the calibration file: restore a \
known-good copy of $BAK by hand, or apply a MAC you know with '$0 set MAC'.";;
        *) [ -n "$ALLOW_UNDIGESTED" ] \
               || die "$EX_NOBACKUP" "$BAK has no usable digest ($BAK.sha256 is missing, or busybox \
sha256sum is unavailable), so nothing proves this image is intact. Re-run with --allow-undigested \
to restore it anyway, knowing that a damaged image would be written over $NV."
           say "[!] --allow-undigested: $BAK cannot be checked against a recorded digest";;
    esac

    # An image captured from another calibration path is not evidence about this
    # one, even when the sizes happen to match.
    if _src=$(bak_source_path) && [ "$_src" != "$NV" ]; then
        if [ -n "$ALLOW_FOREIGN" ]; then
            say "[!] --allow-foreign: $BAK records that it came from $_src, not from $NV"
        else
            die "$EX_NOBACKUP" "$BAK records that it was captured from $_src, not from $NV, so it \
proves nothing about this file. Refusing. Pass --allow-foreign if you are certain this is the right \
image for this path."
        fi
    fi

    # Same honesty rule as 'restore': an intact image whose value is locally
    # administered is a recorded spoof, not a factory burn-in, so putting it
    # back is not a recovery and must not be reported as one.  The flag is the
    # caller's acknowledgement, exactly as --allow-foreign is above.
    if read_bak_offset; then
        _pmac=$(mac_at "$BAK" "$BAK_OFF")
        if locally_administered "$_pmac"; then
            if [ -n "$ALLOW_NONFACTORY" ]; then
                say "[!] --allow-nonfactory-image: $BAK records $_pmac, which is locally administered \
(a recorded spoof, not a factory burn-in)"
            else
                die "$EX_NOBACKUP" "$BAK records $_pmac at its MAC offset, which is LOCALLY \
ADMINISTERED: that is the signature of a spoof, so this image is not evidence of what the device \
shipped with and writing it back is not a recovery. Refusing. Pass --allow-nonfactory-image if you \
are certain this value is the one you want on the interface."
            fi
        fi
    fi

    # Size policy.  Equal sizes are the ordinary case.  A target SHORTER than the
    # image is the truncation disaster and is exactly what this command is for:
    # writing the image back in place restores the original length.  A target
    # LONGER than the image cannot be restored faithfully - the tail would stay.
    if [ "$bak_size" -lt "$nv_size" ]; then
        die "$EX_NOBACKUP" "$BAK is only $bak_size bytes while $NV is $nv_size bytes: writing it \
would leave the last $((nv_size - bak_size)) bytes of the current file in place, which is not a \
restore. Refusing - recover a full-size image for this device ('restore' only writes an image whose \
size equals the target's)."
    fi
    if [ "$bak_size" -gt "$nv_size" ]; then
        say "[!] $NV is $nv_size bytes, shorter than the $bak_size-byte factory image: it looks truncated."
        say "    the image will be written back in full, in place, restoring the original length."
    fi

    # What is being replaced, when it can be read at all.  Purely informational:
    # the command must still work when the current file is garbage, so nothing
    # below depends on this.
    if read_bak_offset && [ $((BAK_OFF + 6)) -le "$nv_size" ]; then
        say "[*] $NV currently holds $(mac_at "$NV" "$BAK_OFF") at offset $BAK_OFF"
    fi

    if [ -n "$DRY" ]; then
        say "[*] dry run: nothing was written"
        say "    would write : $BAK ($bak_size bytes) -> $NV in place (conv=notrunc,fsync, same inode)"
        say "    size        : $nv_size -> $bak_size"
        if read_bak_offset && [ $((BAK_OFF + 6)) -le "$bak_size" ]; then
            say "    MAC in image: $(mac_at "$BAK" "$BAK_OFF") (offset $BAK_OFF)"
        else
            say "    MAC in image: (offset not recorded; the image would be written byte-for-byte)"
        fi
        say "    after write : 'wc -c <$NV' must read $bak_size and 'cmp -s $BAK $NV' must be silent"
        return 0
    fi

    info "[*] restoring $BAK over $NV in place (same inode, same SELinux label) ..."
    $BB dd if="$BAK" of="$NV" bs=4096 conv=notrunc,fsync 2>/dev/null \
        || die "$EX_WRITE" "restore failed: writing $BAK over $NV did not complete"
    _now=$(wc -c <"$NV")
    [ "$_now" = "$bak_size" ] \
        || die "$EX_WRITE" "restore verification failed: $NV is $_now bytes, expected $bak_size"
    $BB cmp -s "$BAK" "$NV" || die "$EX_WRITE" "restore verification failed: $NV does not match $BAK"
    say "[+] $NV now matches $BAK byte-for-byte ($bak_size bytes$( \
[ -n "$(cat "$BAK.sha256" 2>/dev/null)" ] && printf ', sha256 %s' "$(cat "$BAK.sha256")"))"

    restored=
    if read_bak_offset && [ $((BAK_OFF + 6)) -le "$bak_size" ]; then
        restored=$(mac_at "$NV" "$BAK_OFF")
        say "[+] $(bak_mac_label) re-read from $NV ($(bak_offset_label)): ${restored:-unreadable}"
    fi

    if [ -n "$NO_REINIT" ]; then
        say "[*] --no-reinit: WiFi was not restarted; reboot (or run '$0 wifi') to reload the NVRAM"
        return 0
    fi
    # The file is already verified, so nothing below can turn this into a
    # failure: a driver that ignores the NVRAM is worth knowing about, not worth
    # reporting as a failed restore.
    pick_iface
    if [ -z "$IF" ]; then
        say "[!] $IF_ERR"
        say "[!] the image is in place and verified; reboot to be sure the driver reloads it."
        return 0
    fi
    # The restored value is passed in, so the poll waits for the driver to
    # publish it rather than for a fixed number of seconds; nothing below can
    # turn this into a failure, because the file is already verified.
    reinit_wifi "$restored" \
        || say "    (the image is in place and verified regardless; reboot to apply it)"
    now=$(get_runtime)
    say "[+] runtime MAC : ${now:-nothing}"
    if [ -n "$restored" ] && [ "$now" != "$restored" ]; then
        say "[!] the runtime MAC is not the restored value ($restored); reboot if the driver did not reload it."
    fi
    return 0
}

cmd_show() {
    # Read-only: 'show' never captures a backup and never writes anything.
    # It does need the calibration file to report a reading about it: without
    # this the command exited 0 on the device class it documents itself as
    # refusing, and printed an empty string where '--json' promises a number.
    require_nvram
    jopen
    pick_iface
    if [ -n "$IF" ]; then
        row interface interface "$IF"
    else
        row interface interface "(not detected - $IF_ERR)"
    fi
    row nvram_file "nvram file" "$NV ($(wc -c <"$NV") bytes)"
    jfield '"nvram_bytes"' "$(wc -c <"$NV")"

    if [ ! -f "$BAK" ]; then
        row factory_mac "factory (image)" "(no backup - run '$0 backup' while the NVRAM holds the MAC you want to keep)"
    elif read_bak_offset; then
        f=$(mac_at "$BAK" "$BAK_OFF")
        if [ -n "$BAK_ASSUMED" ]; then
            row factory_mac "factory (image)" "${f:-unreadable}  [offset $BAK_OFF ASSUMED, unverified]"
        else
            row factory_mac "factory (image)" "${f:-unreadable}  [offset $BAK_OFF]"
        fi
    else
        # An image captured by an older version has no offset sidecar; the MTK
        # layout puts the MAC at offset 4, so report that but label it assumed.
        _legacy=$(mac_at "$BAK" 4)
        row factory_mac "factory (image)" "${_legacy:-unreadable}  [offset 4 ASSUMED, no $BAK.offset]"
    fi

    # The NVRAM value comes from the offset the captured image recorded, else
    # from wherever the live runtime MAC is found - never from an assumed
    # offset and never by re-reading a write's own output.
    o=
    runtime=$(get_runtime)
    if valid_mac "$runtime"; then
        scan_mac "$NV" "$(echo "$runtime" | tr -d ':')"
        [ "$SCAN_N" -gt 0 ] && o=$(echo "$SCAN_OFFS" | $BB awk '{ print $1 }')
    fi
    [ -n "$o" ] || { read_bak_offset && o=$BAK_OFF; }
    if [ -n "$o" ]; then
        row nvram_mac nvram "$(mac_at "$NV" "$o")  [offset $o]"
        jfield '"nvram_mac_offset"' "$o"
    else
        row nvram_mac nvram "(unknown: the live runtime MAC is not in $NV)"
    fi
    row runtime_mac runtime "${runtime:-(unreadable)}"
    if [ -z "$runtime" ] && [ -n "$IF" ]; then
        row runtime_note "" "($NET/$IF/address is empty - the interface may be down)"
    fi
    jclose
    return 0
}

# --- doctor -----------------------------------------------------------------
# The read-only report, meant to be the first thing a user runs.  It writes
# nothing at all - no temp file, no probe file, no capture: every check below is
# a read, a stat, /proc, or a pipe.  A failing probe is reported as a failing
# probe rather than aborting the report, because the whole point is to see the
# state of a device that may be damaged.

# Mode of a path ("700"), or "-" when it cannot be read.
mode_of() { $BB stat -c '%a' "$1" 2>/dev/null || echo "-"; }
# Mount flags (rw/ro) of the filesystem holding a path, from /proc/mounts.  The
# longest matching mount point wins, so /mnt/vendor being its own read-only
# mount is reported as ro even when / is rw.
mount_flags() { # $1 = path
    [ -r /proc/mounts ] || { echo "?"; return 0; }
    $BB awk -v p="$1" '
        { m = $2; prefix = (m == "/") ? "/" : m "/";
          if (p == m || index(p, prefix) == 1) { if (length(m) > length(best)) { best = m; opt = $4 } } }
        END{
            if (best == "") { print "?"; exit }
            n = split(opt, f, ",");
            for (i = 1; i <= n; i++) if (f[i] == "ro" || f[i] == "rw") { print f[i]; exit }
            print "?"
        }' /proc/mounts
}

# Candidate NVRAM paths, $NV first.  Only paths this CLI actually knows about:
# the MTK ones.  Guessing another vendor's calibration path as root is worse
# than not supporting it, so nothing else is listed - and nothing in the
# environment can add to this list.
nv_candidates() {
    echo "$NV"
    for _p in $NV_LEGACY; do
        [ "$_p" = "$NV" ] && continue
        echo "$_p"
    done
}

vendor_of() { # $1 = path
    case "$1" in
        /mnt/vendor/nvdata/APCFG/APRDEB/WIFI|/data/nvram/APCFG/APRDEB/WIFI) echo "mediatek";;
        *) echo "unknown (not a path this CLI knows)";;
    esac
}

cmd_doctor() {
    _uid=$(id -u)
    jopen
    jfield '"command"' "doctor"
    row test_overrides "overrides" "none: $DIR, $NV and $NET are constants of this script and no environment variable can redirect them"

    # -- tools: the probes are pipes and /dev/null, never a scratch file, and
    # they check the exact behaviours the write path depends on.
    if [ "$_uid" = 0 ]; then
        _root="yes (uid 0)"
    else
        _root="no (uid $_uid): every writing command will refuse"
    fi
    row root root "$_root"
    _bbver=$($BB 2>&1 | sed -n '1p')
    row busybox busybox "$BB${_bbver:+ ($_bbver)}"
    _bad=
    _p=$($BB printf "%b" '\101\102' 2>/dev/null | $BB od -An -tx1 2>/dev/null | tr -d ' \n')
    [ "$_p" = 4142 ] || _bad="$_bad printf%b"
    _p=$($BB printf 'ABCD' 2>/dev/null | $BB hexdump -v -e '1/1 "%02x"' 2>/dev/null)
    [ "$_p" = 41424344 ] || _bad="$_bad hexdump"
    _p=$($BB printf "%b" "$(mac_bytes '04:f9:93:11:36:bf')" 2>/dev/null \
        | $BB od -An -tx1 2>/dev/null | tr -d ' \n')
    [ "$_p" = 04f9931136bf ] || _bad="$_bad awk-octal"
    _p=$(printf '' | $BB sha256sum 2>/dev/null | $BB awk '{ print $1 }')
    [ "$_p" = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 ] \
        || _bad="$_bad sha256sum"
    $BB cmp -s /dev/null /dev/null 2>/dev/null; _c0=$?
    $BB cmp -s /dev/null /dev/zero 2>/dev/null; _c1=$?
    $BB cmp -s "$BAK.no-such-probe" /dev/null 2>/dev/null; _c2=$?
    [ "$_c0" = 0 ] && [ "$_c1" = 1 ] && [ "$_c2" = 2 ] || _bad="$_bad cmp"
    _p=$($BB dd if=/dev/zero bs=1 count=6 2>/dev/null | $BB wc -c | tr -d ' ')
    [ "$_p" = 6 ] || _bad="$_bad dd-count"
    # conv=notrunc,fsync cannot be exercised without writing, so a busybox that
    # does not advertise them in --help is reported as unverifiable rather than
    # as broken: a false "unusable" alarm would send the user after busybox when
    # nothing is wrong.
    _ddconv=
    if $BB dd --help 2>&1 | grep -q 'notrunc'; then
        _p=$($BB dd --help 2>&1 | grep -c 'fsync')
        [ "$_p" -ge 1 ] || _ddconv="build does not list conv=fsync in 'dd --help'"
    else
        _ddconv="conv=notrunc,fsync cannot be verified from 'dd --help' on this build"
    fi
    if [ -z "$_bad" ]; then
        row tools tools "ok (awk octal -> printf %b, hexdump 1/1, sha256sum, cmp 0/1/2, dd bs=1 count=6 conv=notrunc,fsync)"
    else
        row tools tools "UNUSABLE:$_bad"
        row tool_note "note" "a broken applet above is a tool problem, not an NVRAM problem: fix busybox (BB=) before any write"
    fi
    [ -n "$_ddconv" ] && row tool_ddconv "note" "$_ddconv - the write path relies on them"

    # -- interface
    pick_iface
    _cands=$(iface_candidates)
    if [ -n "$IF" ]; then
        row iface interface "$IF"
    else
        row iface interface "(not resolved: $IF_ERR)"
    fi
    row iface_candidates "candidates" "${_cands:-none found under $NET}"
    _rt=$(get_runtime)
    row runtime_mac "runtime MAC" "${_rt:-(unreadable or interface down)}"

    # -- NVRAM candidates
    _i=0
    _nv_ok=
    _mount=
    for _p in $(nv_candidates); do
        _k="nvram.$_i"
        row "$_k.path" "nvram.$_i.path" "$_p"
        row "$_k.vendor" "nvram.$_i.vendor" "$(vendor_of "$_p")"
        if [ -e "$_p" ]; then
            if [ -f "$_p" ]; then
                _sz=$(wc -c <"$_p" 2>/dev/null)
                _mount=$(mount_flags "$_p")
                row "$_k.exists" "nvram.$_i.exists" "yes (regular file, $_sz bytes, mode $(mode_of "$_p"), mount $_mount)"
                row "$_k.size" "nvram.$_i.size" "$_sz"
                row "$_k.mode" "nvram.$_i.mode" "$(mode_of "$_p")"
                row "$_k.mount" "nvram.$_i.mount" "$_mount"
                if [ -r "$_p" ]; then
                    _hits=
                    if valid_mac "$_rt"; then
                        scan_mac "$_p" "$(echo "$_rt" | tr -d ':')"
                        [ "$SCAN_N" -gt 0 ] && _hits="$SCAN_OFFS"
                    fi
                    if [ -n "$_hits" ]; then
                        row "$_k.runtime_mac_offsets" "nvram.$_i.mac" "runtime MAC $_rt found at offset(s)$_hits"
                    elif valid_mac "$_rt"; then
                        row "$_k.runtime_mac_offsets" "nvram.$_i.mac" "runtime MAC $_rt NOT found in it"
                    else
                        row "$_k.runtime_mac_offsets" "nvram.$_i.mac" "not scanned: no usable runtime MAC"
                    fi
                    if read_bak_offset && [ "$_p" = "$NV" ]; then
                        row "$_k.recorded_offset" "nvram.$_i.rec" "$(mac_at "$_p" "$BAK_OFF") [offset $BAK_OFF]"
                    elif [ "$_p" = "$NV" ]; then
                        row "$_k.recorded_offset" "nvram.$_i.rec" "no offset recorded ($BAK.offset absent)"
                    fi
                else
                    row "$_k.readable" "nvram.$_i.readable" "NO - unreadable"
                fi
                [ "$_p" = "$NV" ] && _nv_ok=1
            else
                row "$_k.exists" "nvram.$_i.exists" "yes, but NOT a regular file - this CLI refuses to touch it"
            fi
        else
            row "$_k.exists" "nvram.$_i.exists" "absent"
        fi
        _i=$((_i + 1))
    done
    row vendor vendor "$(vendor_of "$NV")"
    row supported supported "$([ -n "$_nv_ok" ] && echo "yes - MTK NVRAM path present" || echo "no - $NV is missing (another vendor, or a different layout)")"

    # -- factory record
    _fp=
    _has_bak=
    bak_digest_state
    if [ ! -f "$BAK" ]; then
        row factory_image "factory image" "(none at $BAK - run '$0 backup' while the NVRAM holds the MAC you want to keep)"
    else
        _has_bak=1
        row factory_image "factory image" "$BAK ($(wc -c <"$BAK") bytes, mode $(mode_of "$BAK"))"
        case "$BAK_DIGEST" in
            ok)       _d="matches $BAK.sha256 ($(cat "$BAK.sha256" 2>/dev/null))";;
            missing)  _d="NOT RECORDED - the image cannot be proven intact";;
            mismatch) _d="MISMATCH - $BAK is damaged; no command will write it back";;
            *)        _d="(no image)";;
        esac
        row factory_digest "digest" "$_d"
        # Whether the record can actually be written back is a separate fact
        # from whether it is intact: 'restore' needs the image as long as the
        # target and 'panic' refuses a shorter one, so a short image is a record
        # that NEITHER command can put back - the verdict below says so instead
        # of calling this device ready.
        bak_writeback_state
        row factory_writeback "write back" "$BAK_NOTE"
        if read_bak_offset; then
            _fp=$(mac_at "$BAK" "$BAK_OFF")
            # "factory MAC" is an evidential claim, and this row used to make it
            # for any value found in the image - including one the same report
            # calls a probable spoof a few lines further down.  The label now
            # follows the evidence: a value LOCATED by scanning for the live MAC
            # that is also a plausible factory burn-in.
            if [ -n "$BAK_ASSUMED" ]; then
                row factory_mac "recorded MAC" "${_fp:-unreadable}  [offset $BAK_OFF ASSUMED, unverified]"
                row factory_offset "provenance" "offset $BAK_OFF was assumed, not located (--assume-factory)"
            elif valid_mac "$_fp" && ! usable_factory_target "$_fp"; then
                row factory_mac "recorded MAC" "${_fp:-unreadable}  [offset $BAK_OFF; NOT a usable station address]"
                row factory_offset "provenance" "offset $BAK_OFF located by scanning the captured image"
            elif valid_mac "$_fp" && locally_administered "$_fp"; then
                row factory_mac "recorded MAC" "${_fp:-unreadable}  [offset $BAK_OFF; locally administered]"
                row factory_offset "provenance" "offset $BAK_OFF located by scanning the captured image"
            else
                row factory_mac "factory MAC" "${_fp:-unreadable}  [offset $BAK_OFF, located in the image]"
                row factory_offset "provenance" "offset $BAK_OFF located by scanning the captured image"
            fi
            # The note explains the VALUE; it must not invent a provenance for it.
            # A LOCATED locally administered value cannot come from this CLI's own
            # capture path - capture_decide records '?N' for that case - so naming
            # --assume-factory as the cause was a guess printed as fact.
            if valid_mac "$_fp" && locally_administered "$_fp"; then
                row factory_note "note" "$_fp is locally administered: it looks like a spoof, not a \
factory burn-in. This CLI's own capture never records a LOCATED locally administered value (it writes \
'?N' for those), so this image was written by something else"
            fi
            if valid_mac "$_fp" && ! usable_factory_target "$_fp"; then
                row factory_note "note" "$_fp can never be a factory burn-in (multicast/broadcast, \
all-zero or all-0xFF): no provenance makes such a value writable, and this record should be replaced"
            fi
        else
            row factory_mac "factory MAC" "(offset not recorded: no $BAK.offset; reported as offset 4 by 'show')"
        fi
        _foreign=
        if _src=$(bak_source_path); then
            [ "$_src" != "$NV" ] && _foreign=$_src
            row factory_source "recorded source" "$_src$( \
[ "$_src" != "$NV" ] && printf '%s' "  [NOT $NV - a foreign image, refused by 'panic' and 'set'])")"
        else
            row factory_source "recorded source" "(none - no $BAK.path)"
        fi
        # The cross-check that matters before a write: does the image's MAC agree
        # with the live runtime MAC, i.e. is the NVRAM currently unspoofed?  Two
        # things stop it from asserting "not spoofed" for a record that cannot
        # support that claim: a value that is not a factory candidate, and an
        # offset that was assumed rather than located.
        if valid_mac "$_fp" && valid_mac "$_rt"; then
            if [ "$_fp" != "$_rt" ]; then
                row spoof_state "spoof state" "the NVRAM holds $_rt, not the recorded value $_fp \
(spoofed, or this record is stale)"
            elif [ -n "$BAK_ASSUMED" ] || ! usable_factory_target "$_fp" || locally_administered "$_fp"; then
                row spoof_state "spoof state" "the NVRAM holds $_fp - the value this record puts in the \
image, which this record does not establish as a factory MAC (see the row above), so 'not spoofed' \
cannot be concluded from it"
            else
                row spoof_state "spoof state" "the NVRAM holds the recorded factory MAC (not spoofed)"
            fi
        elif valid_mac "$_rt" && locally_administered "$_rt"; then
            row spoof_state "spoof state" "the NVRAM holds the locally administered $_rt and there is \
no usable factory record: the true factory MAC may already be lost"
        fi
        if [ -f "$DIR/factory.txt" ]; then
            # The app reads this file: a truncated or non-MAC first line is
            # reported as damaged rather than printed as an unvalidated fact.
            _ft=$(sed -n '1p' "$DIR/factory.txt" 2>/dev/null)
            if valid_mac "$_ft"; then
                row factory_txt "app record" "$_ft (src: $(sed -n '2p' "$DIR/factory.txt" 2>/dev/null))"
            else
                row factory_txt "app record" "'${_ft:-<empty>}' is NOT a MAC address: $DIR/factory.txt \
is damaged and the app will refuse to use it - delete it to have it captured again"
            fi
        fi
    fi

    # -- backup directory
    if [ -d "$DIR" ]; then
        row backup_dir "backup dir" "$DIR (mode $(mode_of "$DIR"))"
        if [ -L "$DIR" ]; then
            row backup_dir_note "note" "$DIR is a symlink"
        fi
        case "$(mode_of "$DIR")" in
            700|0700) ;;
            *) row backup_dir_perms "note" "$DIR mode is $(mode_of "$DIR"), not 700: the factory \
image inside is exposed to other apps";;
        esac
    else
        row backup_dir "backup dir" "$DIR (absent - nothing has been captured yet)"
    fi
    for _f in "$BAK" "$BAK.offset" "$BAK.sha256" "$BAK.path" "$DIR/factory.txt"; do
        if [ -f "$_f" ]; then
            row "file.$(echo "$_f" | tr '/.' '__')" "file" "$_f ($(wc -c <"$_f") bytes, mode $(mode_of "$_f"))"
        fi
    done

    # -- verdict: what would stop a write, and what is merely worth knowing.
    _blk=
    _wrn=
    [ "$_uid" = 0 ] || _blk="$_blk|not root (uid $_uid)"
    [ -z "$_bad" ] || _blk="$_blk|unusable busybox applet(s):$_bad"
    [ -n "$_nv_ok" ] || _blk="$_blk|$NV is missing or not a regular file"
    [ -n "$IF" ] || _blk="$_blk|$IF_ERR"
    [ -n "$_has_bak" ] || _blk="$_blk|no factory image at $BAK"
    [ -z "$_has_bak" ] || [ "$BAK_DIGEST" = ok ] \
        || _blk="$_blk|the factory image cannot be verified ($BAK_DIGEST digest)"
    # "ready" has to mean the record can be written back, not merely that a file
    # exists: an image shorter than $NV is refused by 'restore' AND by 'panic',
    # so a write made now would have no way back at all.
    [ "$BAK_WB" = short ] && _blk="$_blk|$BAK_NOTE"
    if [ -n "$_has_bak" ] && [ -n "$BAK_ASSUMED" ]; then
        _blk="$_blk|the factory image was captured without confirming its MAC field"
    fi
    # A record whose value could not have come from a factory - locally
    # administered, multicast, all-zero or all-0xFF - is not a way back to a
    # factory MAC, and the app's restoreRefusal() refuses to write one.  'ready'
    # must not be reported for a device whose only recorded value is that.
    if [ -n "$_has_bak" ] && valid_mac "$_fp" \
       && { ! usable_factory_target "$_fp" || locally_administered "$_fp"; }; then
        _blk="$_blk|the recorded value $_fp is not a factory burn-in, so 'restore' would put a value \
that was never the factory MAC back into $NV"
    fi
    # A record captured from a different calibration path proves nothing about
    # this one: 'panic' and 'set' both refuse it, so a device whose only record
    # is foreign has no way back that this tool will use.
    [ -z "$_foreign" ] \
        || _blk="$_blk|the recorded image came from $_foreign, not from $NV: 'panic' and 'set' refuse \
it unless --allow-foreign is given"
    [ "$_mount" = ro ] && _wrn="$_wrn|$NV is on a read-only mount: a write would fail"
    [ "$BAK_DIGEST" = missing ] && _wrn="$_wrn|no digest recorded for $BAK"
    [ "$BAK_WB" = panic-only ] && _wrn="$_wrn|$BAK_NOTE"
    if valid_mac "$_rt" && valid_mac "$_fp" && [ "$_rt" != "$_fp" ]; then
        _wrn="$_wrn|the NVRAM currently holds $_rt, not the recorded factory MAC $_fp"
    fi
    if [ -z "$_blk" ]; then
        if [ "$BAK_WB" = panic-only ]; then
            row verdict verdict "yes - root, usable busybox, a resolved interface, an NVRAM path and a \
verified factory image that only '$0 panic' can write back"
        else
            row verdict verdict "yes - root, usable busybox, a resolved interface, an NVRAM path and a \
verified factory image '$0 restore' can write back"
        fi
    else
        row verdict verdict "no"
        _i=0
        echo "$_blk" | tr '|' '\n' | while read -r _b; do
            [ -n "$_b" ] || continue
            row "blocker.$_i" "blocker" "$_b"
            _i=$((_i + 1))
        done
    fi
    _i=0
    echo "$_wrn" | tr '|' '\n' | while read -r _b; do
        [ -n "$_b" ] || continue
        row "warning.$_i" "warning" "$_b"
        _i=$((_i + 1))
    done
    jfield '"ready"' "$([ -z "$_blk" ] && echo yes || echo no)"
    jclose
    return 0
}

usage() {
    cat <<EOF
macchanger - persistent WiFi MAC changer (MTK NVRAM method)

usage:
  $0 doctor               read-only report: root, busybox, interface, every
                          candidate NVRAM path, the recorded factory MAC and its
                          provenance, and the backup directory.  Writes nothing.
                          Run this first.  Alias: selftest
  $0 show                 show interface / factory image / nvram / live MAC
  $0 backup [--assume-factory]
                          capture the factory NVRAM image (needed before
                          spoofing; refuses to overwrite an existing one)
  $0 set AA:BB:CC:DD:EE:FF   set a specific MAC (captures the factory image if
                          it is missing, writes NVRAM in place and restarts
                          WiFi so the driver picks it up)
  $0 random               set a random locally-administered MAC
  $0 wifi                 just restart WiFi (applies current NVRAM MAC)
  $0 restore              write the factory image back, in place (needs it to be
                          the same size as the NVRAM)
  $0 panic                put the recorded factory image back when the NVRAM is
                          corrupt, unreadable or truncated, and refuse rather
                          than guess when the record cannot be trusted.
                          Alias: undo
  $0 help                 this text

options:
  --iface=NAME            use this WiFi interface (required when more than one
                          wlan* exists, or when detection finds none)
  --assume-factory        capture the factory image even when the NVRAM cannot
                          be shown to hold the live runtime MAC (the image is
                          then recorded as unverified)
  -n, --dry-run           print the file, the offset and the bytes that would
                          change, change nothing, and exit with the status the
                          real run would have for the checks it evaluates: the
                          plan, a recorded image that fails its digest check,
                          and a recorded image too short to be written back
                          (both exit 7).  It does not run the capture a real
                          'set' performs, so a refusal that only arises there
                          (locally administered live MAC, MAC field not found)
                          is not predicted - the dry run exits 0 where the real
                          run exits 6
  --no-reinit             restore/panic: write the image but do not restart
                          WiFi (reboot instead)
  --allow-damaged-backup  set/random: proceed even though the recorded factory
                          image does not match its digest
  --allow-undigested      panic: restore an image that has no recorded digest
                          and therefore cannot be proven intact
  --allow-foreign         panic/set/random: use an image recorded as captured
                          from a different calibration path
  --allow-nonfactory-image
                          restore/panic: write back an image whose recorded MAC
                          is locally administered, i.e. a recorded spoof rather
                          than a factory burn-in.  Without this, such an image
                          is refused (exit 7): an intact image is not the same
                          thing as a factory image, and 'doctor' already reports
                          the same record as a blocker
  -q, --quiet             hide progress lines ('[*] ...'); results and warnings
                          are still printed
  --json                  show/doctor: one JSON object on stdout instead of the
                          human lines (all other output goes to stderr)
  -h, --help              this text

aliases (the canonical spelling is preferred; these exist for muscle memory):
  selftest, check, diag = doctor     status = show    save = backup
  rand = random                      reset  = restore undo = panic

exit codes:
  0 ok   1 usage/bad MAC   2 not root   3 busybox unusable   4 NVRAM path
  missing or not a file   5 WiFi interface not resolved   6 precondition on the
  target failed (refusing to write blind), or another macchanger operation holds
  the lock   7 factory image missing, damaged,
  foreign, or refusing to overwrite   8 the write or its verification failed
  9 set/random: written and verified, but the driver is not using the MAC.
  wifi: the restart was issued but could not be observed on this ROM, so the
  caller is not told 'reloaded' (a ROM that reports no WiFi state and a driver
  that publishes nothing leaves the outcome unknown)

notes:
  * persists across reboots (writes MTK NVRAM in place, keeping the inode).
  * changing the MAC drops your whitelisted WiFi connection.
  * the MAC field is found by scanning for the live MAC, not assumed to be at
    offset 4; a write is refused unless the bytes there are that same MAC.
  * a factory image is only captured while the NVRAM still holds the MAC the
    driver is using, and a locally administered value there is refused as
    evidence of a spoof unless --assume-factory is given; 'show', 'doctor' and
    'restore' never capture one, and 'restore'/'panic' need one.  'set'
    captures through that same decision, so it is refused on a device whose
    live MAC is already locally administered - the state 'random' leaves
    behind - until --assume-factory is given (which records that spoof as the
    factory image), or a known-good image of this path is put in the backup
    directory first.  A capture that located the MAC field also writes the app's
    factory.txt record there (line 1 the MAC, line 2 '<path>@0x<offset>'), so
    the app can restore what this CLI captured; an --assume-factory value is
    never written there.
  * the WiFi restart is polled, not slept through: the state this ROM reports
    (or the MAC the driver publishes) is read back within a bounded wait, and
    'set'/'wifi' say whether the restart failed, did not toggle at all, or could
    not be observed on this ROM, instead of blaming the MAC for a slow reload.
  * a recorded image shorter than the NVRAM is not a way back: 'restore' demands
    equal sizes and 'panic' refuses a short image, so 'doctor' reports that as a
    blocker and 'set' refuses to write on such a record.
  * 'restore' and 'panic' report the verified state of the file, not the
    driver's: when the runtime MAC is not the restored value they say so and
    still exit 0, because the image is provably back in place.  'set'/'random'
    exit 9 when the change is not in effect, and 'wifi' exits 9 when the restart
    it issued could not be observed at all - each says whether the WiFi restart
    failed, did not toggle, or could not be observed on this ROM, so an
    unobservable outcome is never reported as success.
  * 'panic' is the recovery command: use it when the NVRAM is damaged or the
    runtime MAC is unknown.  It works with no interface and no readable MAC,
    and it refuses when the image is damaged, foreign, undigested (unless
    --allow-undigested) or shorter than the file it would be written over.
  * every recorded image is written in place with dd conv=notrunc,fsync and
    verified with cmp and a size check; nothing is ever cp'd or mv'd over a
    calibration file.
  * the device paths are constants of this script - /data/adb/macchanger,
    /mnt/vendor/nvdata/APCFG/APRDEB/WIFI and /sys/class/net - and no environment
    variable can redirect them: there is no test hook, so a stray (or hostile)
    environment cannot point a calibration write anywhere else.  Off-device runs
    redirect the paths with a private mount namespace instead (tools/clitest),
    which the script needs to know nothing about.
  * every writing command (backup, set, random, restore, panic) takes the same
    cross-process lock as the Android app - mkdir /data/adb/macchanger/lock,
    abandoned after 300 s - before it reads the NVRAM, and releases it only
    while it still owns it.  While the app holds it the CLI refuses with exit 6
    and writes nothing, and the app refuses while the CLI holds it, so the two
    front ends cannot rewrite the same calibration file at the same time.
    --dry-run writes nothing and therefore takes no lock.
EOF
}

CMD=
ARG=
IF_FORCED=
ASSUME=
DRY=
QUIET=
JSON=
NO_REINIT=
ALLOW_DAMAGED=
ALLOW_UNDIGESTED=
ALLOW_FOREIGN=
ALLOW_NONFACTORY=
want_iface=
for a in "$@"; do
    if [ -n "$want_iface" ]; then
        IF_FORCED=$a
        want_iface=
        continue
    fi
    case "$a" in
        --iface)          want_iface=1 ;;
        --iface=*)        IF_FORCED=${a#--iface=} ;;
        --assume-factory) ASSUME=1 ;;
        -n|--dry-run)     DRY=1 ;;
        -q|--quiet)       QUIET=1 ;;
        --json)           JSON=1 ;;
        --no-reinit)      NO_REINIT=1 ;;
        --allow-damaged-backup) ALLOW_DAMAGED=1 ;;
        --allow-undigested)     ALLOW_UNDIGESTED=1 ;;
        --allow-foreign)        ALLOW_FOREIGN=1 ;;
        --allow-nonfactory-image) ALLOW_NONFACTORY=1 ;;
        -h|--help)        CMD=help ;;
        --*)              die "$EX_USAGE" "unknown option: $a (try '$0 help')" ;;
        "")               ;;
        # At most one positional exists (the MAC for 'set').  A second one used
        # to overwrite the first, so 'set GOOD-MAC EXTRA' reported a bad MAC
        # named EXTRA and 'show a b' was accepted silently.  Refuse instead.
        *) if [ -z "$CMD" ]; then CMD=$a
           elif [ -z "$ARG" ]; then ARG=$a
           else die "$EX_USAGE" "unexpected extra argument: $a (at most one argument; try '$0 help')"
           fi ;;
    esac
done
[ -n "$want_iface" ] && die "$EX_USAGE" "--iface needs an interface name (--iface=NAME)"
if [ -n "$IF_FORCED" ]; then
    case "$IF_FORCED" in
        *[!A-Za-z0-9._-]*) die "$EX_USAGE" "invalid interface name: $IF_FORCED";;
    esac
fi

case "$CMD" in
    doctor|selftest|check|diag) cmd_doctor ;;
    show|status)   cmd_show ;;
    backup|save)   cmd_backup ;;
    set)           cmd_set "$ARG" ;;
    random|rand)   cmd_random ;;
    wifi)          cmd_wifi ;;
    restore|reset) cmd_restore ;;
    panic|undo)    cmd_panic ;;
    ""|help)       usage ;;
    *) usage; exit "$EX_USAGE" ;;
esac
