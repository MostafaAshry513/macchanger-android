#!/bin/sh
#
# clitest/clitest.sh - offline behavioural test for cli/macchanger.sh.
#
# What this proves
# ----------------
# It runs the REAL cli/macchanger.sh - never a copy with rewritten constants -
# against synthetic NVRAM images under /tmp, with every device path the script
# uses redirected and the WiFi restart driven by a stub `svc`.  What executes is
# the shipped script text, byte for byte.
#
# Two redirect mechanisms, neither visible to the script's logic:
#
#   override   the CLI's own documented testing hook:
#              MACCHANGER_TEST=1 MACCHANGER_DIR=... MACCHANGER_NV=... MACCHANGER_NET=...
#              (used automatically when the script supports it).  The hook is
#              gated on MACCHANGER_TEST and announces itself on stderr; assertion
#              A0 checks that gate: setting MACCHANGER_NV alone must NOT redirect
#              anything.
#   namespace  for revisions with no hook at all (e.g. the pristine snapshot):
#              a private mount namespace binds throwaway directories over
#              /mnt/vendor/nvdata/APCFG/APRDEB, /data/adb/macchanger and
#              /sys/class/net.  Nothing outside the namespace is written, and the
#              mounts vanish when the run ends.
#
# Neither mechanism is a copy of the script and neither edits cli/, which is
# owned by another stage.  Where the suite needs a fact about the CLI (its
# exit-code classes), it reads that fact out of the CLI itself.
#
# Assertions (each prints PASS or FAIL; the suite exits non-zero if any FAILs):
#
#   A  safety of the read-only and refusal paths
#      A0  the test override is gated on MACCHANGER_TEST (override mode only)
#      A0b MACCHANGER_TEST=0 (any non-1 value) must not arm that override
#          either (override mode only: the shipped revision has no hook at all)
#      A1  `show` creates and modifies nothing (sha256 + size + mtime + file
#          listing of the whole device-visible tree, before and after)
#      A1b `show` really read the sandbox device, so A1 is not vacuous
#      A2  negative control: the very same assertion FAILS for `set`, which does
#          write - proving A1 can detect a modification
#      A3  `restore` with no factory image: refusal exit, nothing created
#      A4  `restore` with a size-mismatched image: refusal exit, target left
#          byte-identical and the same length (the truncation hazard)
#      A5  `set` whose live MAC is nowhere in the NVRAM: refusal exit, target
#          byte-identical, and no bogus "factory" image captured
#      A6  `set` while the NVRAM holds a locally administered (already spoofed)
#          MAC: refusal exit without --assume-factory, target byte-identical
#      A7  a live multicast (and all-zero) MAC is not factory evidence: no
#          capture, no image, no app record, target byte-identical - with and
#          without --assume-factory, which cannot make such a value writable
#      A8  the CLI honours the cross-process lock the app takes (M2): while a
#          fresh lock is present, `set` exits non-zero and writes nothing
#   B  the write is located, never assumed
#      B1  live MAC at offset 40 and a decoy at offset 4: the write lands at 40,
#          the decoy is untouched
#      B2  the write is 6 bytes in place: length unchanged, and the stub driver
#          re-read the MAC from the found offset (evidence in the svc log)
#   C  verdicts and exit codes
#      C1  driver ignores the change: the apply-class exit status, while the
#          NVRAM byte image does hold the new MAC (the verdict failed, not the
#          write)
#      C2  `restore` of a good image: exit 0, target == image byte-for-byte,
#          length unchanged, runtime MAC back to the factory value
#      C3  the documented exit codes, compared against the classes the CLI
#          defines for itself (EX_USAGE/EX_PRECOND/EX_NOBACKUP/EX_APPLY/...):
#          usage and help are 0, every refusal is its own class
#      C4  every one of C3's refusals left the target byte-identical
#   D  honesty of a recorded image, and the argument parser
#      D1  `restore` of an intact image whose recorded MAC is locally
#          administered (a recorded spoof): refused with EX_NOBACKUP and the
#          target left byte-identical - an intact image is not a factory image
#      D2  the same image with --allow-nonfactory-image: written, exit 0, so the
#          flag is an acknowledgement rather than a bypass of the other checks
#      D3  `panic` refuses that image the same way
#      D4  a second positional is a usage error instead of silently replacing
#          the first, which used to make 'set GOOD-MAC EXTRA' report EXTRA as
#          the invalid MAC and let 'show a b' through with exit 0
#
# Usage
# -----
#   sh tools/clitest/clitest.sh                 run the suite
#   sh tools/clitest/clitest.sh --redirect=namespace
#                                               force the mount-namespace
#                                               redirect (older CLI revisions)
#   sh tools/clitest/clitest.sh --redirect=override
#                                               force the CLI's own hook
#   sh tools/clitest/clitest.sh --self-test     prove the suite can fail: run it
#                                               with one deliberately false
#                                               assertion and require a non-zero
#                                               exit status
#   sh tools/clitest/clitest.sh --keep          keep the /tmp scenarios
#
# The last line of a normal run is exactly "CLITEST: PASS" or "CLITEST: FAIL".
# A harness or setup problem prints "CLITEST: ERROR" and exits 2 - it is never
# reported as a PASS.
#
# Requirements: root (the CLI refuses to write otherwise), a POSIX shell,
# busybox (the CLI hard-requires it) and coreutils dd/od/stat/sha256sum/cmp.
# The namespace redirect additionally needs util-linux unshare and mount
# privileges.  No network, no Android device, no Android SDK, no Gradle.
#
# Run this on a host or in a container, NOT on the phone: the redirect is what
# keeps the run off the real calibration file, and on a phone the stub `svc`
# cannot shadow the real one.
#
# Environment:
#   CLI=/path/to/cli/macchanger.sh   script under test (default: ../../cli/...)
#   CLITEST_SHELL=/path/to/sh        shell used to run it (default: sh)
#   BUSYBOX=/path/to/busybox         default: busybox on PATH
#   CLITEST_INJECT_FAIL=1            add one deliberately failing assertion
#                                    (used by --self-test; never in a normal run)

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT_FILE="$SCRIPT_DIR/$(basename -- "$0")"
CLI=${CLI:-$SCRIPT_DIR/../../cli/macchanger.sh}
SANDBOX=$SCRIPT_DIR/sandbox.sh
STUB_SVC=$SCRIPT_DIR/stubs/svc
RUNSHELL=${CLITEST_SHELL:-sh}
BUSYBOX=${BUSYBOX:-}
[ -n "$BUSYBOX" ] || BUSYBOX=$(command -v busybox 2>/dev/null)

SETUP_FAILED=91
MODE=run
KEEP=no
WANT_REDIRECT=auto
REDIRECT=

usage() {
    cat <<EOF
usage: sh $0 [--self-test] [--keep] [--redirect=auto|override|namespace]

  (no options)   run the CLI behavioural suite against ../../cli/macchanger.sh
  --redirect=X   how the CLI's device paths are sent to the sandbox:
                 auto      use the CLI's MACCHANGER_TEST hook if it has one,
                           else a private mount namespace (default)
                 override  require the CLI's MACCHANGER_TEST hook
                 namespace require unshare -m (works on old revisions too)
  --self-test    run the suite twice: unmodified (must exit 0) and with one
                 deliberately false assertion (must exit non-zero)
  --keep         keep the scenario directories under \$TMPDIR for inspection

environment: CLI=<script under test> CLITEST_SHELL=<shell> BUSYBOX=<busybox>
EOF
}

for _a in "$@"; do
    case "$_a" in
        --self-test)   MODE=selftest ;;
        --keep)        KEEP=yes ;;
        --redirect=*)  WANT_REDIRECT=${_a#--redirect=} ;;
        -h|--help)     usage; exit 0 ;;
        *) usage >&2; echo "clitest: unknown argument: $_a" >&2; exit 2 ;;
    esac
done

fatal() {
    printf 'clitest: %s\n' "$1" >&2
    printf 'CLITEST: ERROR\n'
    exit 2
}

# --- preflight --------------------------------------------------------------
# Everything here fails loudly: a harness that cannot run must never be
# mistaken for a suite that passed.
[ -f "$CLI" ] || fatal "no CLI script to test: $CLI"
[ -f "$SANDBOX" ] || fatal "sandbox.sh is missing: $SANDBOX"
[ -f "$STUB_SVC" ] || fatal "the stub svc is missing: $STUB_SVC"
[ "$(id -u)" = 0 ] || fatal "must run as root: the CLI refuses to write otherwise (EX_NOTROOT), so every write assertion would test the refusal path instead"
[ -n "$BUSYBOX" ] && [ -x "$BUSYBOX" ] || fatal "busybox not found: the CLI hard-requires it (the namespace sandbox also symlinks it at the Magisk default path); set BUSYBOX="
case "$RUNSHELL" in
    */*) [ -x "$RUNSHELL" ] || fatal "shell is not executable: $RUNSHELL" ;;
    *)   command -v "$RUNSHELL" >/dev/null 2>&1 || fatal "shell not found: $RUNSHELL" ;;
esac

case "$WANT_REDIRECT" in
    auto|override|namespace) ;;
    *) fatal "--redirect=$WANT_REDIRECT is not one of auto, override, namespace" ;;
esac

# Does this revision of the CLI actually honour its own testing hook?
#
# This is asked by RUNNING the script, not by grepping its text for the variable
# names: a revision that mentions the variables only to document that they are no
# longer honoured (a comment, a help line, a refusal) must not be mistaken for one
# that redirects.  A revision with the hook announces the override on stderr on
# every run, including 'help' - which needs no root, no device and writes nothing.
HAS_OVERRIDE=no
if MACCHANGER_TEST=1 MACCHANGER_DIR=/nonexistent-clitest-probe \
   MACCHANGER_NV=/nonexistent-clitest-probe MACCHANGER_NET=/nonexistent-clitest-probe \
   "$RUNSHELL" "$CLI" help 2>&1 >/dev/null | grep -q 'TEST OVERRIDES ACTIVE'; then
    HAS_OVERRIDE=yes
fi

case "$WANT_REDIRECT" in
    override)
        [ "$HAS_OVERRIDE" = yes ] || fatal "$CLI has no MACCHANGER_TEST/MACCHANGER_NV override, so --redirect=override cannot work (use --redirect=namespace)"
        REDIRECT=override
        ;;
    namespace) REDIRECT=namespace ;;
    auto)      if [ "$HAS_OVERRIDE" = yes ]; then REDIRECT=override; else REDIRECT=namespace; fi ;;
esac

if [ "$REDIRECT" = namespace ]; then
    command -v unshare >/dev/null 2>&1 || fatal "unshare not found (util-linux), and $CLI has no test override: the device paths cannot be redirected"
    command -v mount >/dev/null 2>&1 || fatal "mount not found"
    if ! unshare -m "$RUNSHELL" -c 'mount -t tmpfs tmpfs /mnt' >/dev/null 2>&1; then
        fatal "cannot create a mount namespace with mount privileges (unshare -m + mount -t tmpfs /mnt failed); run as root with CAP_SYS_ADMIN"
    fi
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/clitest.XXXXXX") || fatal "mktemp -d failed under ${TMPDIR:-/tmp}"
cleanup() {
    if [ "$KEEP" = yes ]; then
        echo "clitest: kept the scenario tree at $TMP"
    else
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

# --- what the CLI says its exit codes are -----------------------------------
# Read out of the CLI itself, so a renumbering cannot make this suite assert a
# stale literal.  A revision with no per-class codes (e.g. the shipped snapshot)
# falls back to "refusals are non-zero", which is all such a revision promises.
ex_code() {
    sed -n "s/^$1=\([0-9][0-9]*\).*/\1/p" "$CLI" 2>/dev/null | head -n 1
}
EX_USAGE=$(ex_code EX_USAGE)
EX_PRECOND=$(ex_code EX_PRECOND)
EX_NOBACKUP=$(ex_code EX_NOBACKUP)
EX_APPLY=$(ex_code EX_APPLY)
CODES_MODE=documented
if [ -z "$EX_USAGE" ] || [ -z "$EX_PRECOND" ] || [ -z "$EX_NOBACKUP" ] || [ -z "$EX_APPLY" ]; then
    CODES_MODE=non-zero-only
    EX_USAGE=1
    EX_PRECOND=1
    EX_NOBACKUP=1
    EX_APPLY=1
fi

# --- reporting --------------------------------------------------------------
TOTAL=0
PASS=0
FAIL=0

check() { # $1 = assertion, $2 = 0/1, $3 = detail printed on failure
    TOTAL=$((TOTAL + 1))
    if [ "$2" -eq 0 ]; then
        PASS=$((PASS + 1))
        printf 'PASS: %s\n' "$1"
    else
        FAIL=$((FAIL + 1))
        printf 'FAIL: %s\n' "$1"
        if [ -n "${3:-}" ]; then printf '      %s\n' "$3"; fi
    fi
}

info() { printf 'INFO: %s\n' "$1"; }

section() { printf '\n--- %s\n' "$1"; }

info "cli under test    : $CLI"
info "shell             : $RUNSHELL"
info "path redirect     : $REDIRECT"
info "exit-code classes : $CODES_MODE (EX_USAGE=$EX_USAGE EX_PRECOND=$EX_PRECOND EX_NOBACKUP=$EX_NOBACKUP EX_APPLY=$EX_APPLY)"

# --- --self-test: prove the suite itself can fail ---------------------------
# This is the exit-status proof: the same suite is run twice as a child process,
# once unmodified (must exit 0) and once with one deliberately false assertion
# (must exit non-zero and must print that FAIL).  A suite that cannot fail is
# worth nothing, so the proof runs here instead of being asserted in prose.
self_test() {
    _green="$TMP/selftest-green.out"
    _broken="$TMP/selftest-broken.out"
    info "self-test: running the suite unmodified (expect exit 0)"
    "$RUNSHELL" "$SCRIPT_FILE" --redirect="$REDIRECT" >"$_green" 2>&1
    _rc_green=$?
    info "self-test: running the suite with CLITEST_INJECT_FAIL=1 (expect a non-zero exit)"
    CLITEST_INJECT_FAIL=1 "$RUNSHELL" "$SCRIPT_FILE" --redirect="$REDIRECT" >"$_broken" 2>&1
    _rc_broken=$?
    if [ "$_rc_green" -eq 0 ]; then
        check "self-test A: an unmodified run of the suite exits 0" 0
    else
        check "self-test A: an unmodified run of the suite exits 0" 1 \
            "exit $_rc_green; last lines: $(tail -4 "$_green" | tr '\n' '|')"
    fi
    if [ "$_rc_broken" -ne 0 ]; then
        check "self-test B: a run with one failed assertion exits non-zero" 0
    else
        check "self-test B: a run with one failed assertion exits non-zero" 1 \
            "exit $_rc_broken (must be non-zero): the suite would report a false PASS"
    fi
    if grep -q '^FAIL: INJECTED FAILURE' "$_broken"; then
        check "self-test C: the failing run prints the FAIL line and ends in CLITEST: FAIL" 0
    else
        check "self-test C: the failing run prints the FAIL line and ends in CLITEST: FAIL" 1 \
            "no 'FAIL: INJECTED FAILURE' line; last lines: $(tail -4 "$_broken" | tr '\n' '|')"
    fi
}

if [ "$MODE" = selftest ]; then
    section "self-test (does the suite fail when it should?)"
    self_test
    printf '\n%s\n' "clitest: self-test: $PASS of $TOTAL checks passed, $FAIL failed"
    if [ "$FAIL" -eq 0 ]; then echo "CLITEST: PASS"; exit 0; fi
    echo "CLITEST: FAIL"
    exit 1
fi

# --- scenario helpers -------------------------------------------------------
SCN=
RUN=
MAC_OFFSET=4
NORELOAD=
LAST_RC=

scenario() { # $1 = name
    SCN="$TMP/$1"
    RUN="$TMP/$1.run"
    rm -rf "$SCN" "$RUN"
    mkdir -p "$SCN/nvram" "$SCN/net/wlan0" "$SCN/record" "$SCN/bin" "$RUN" \
        || fatal "cannot create the scenario directory $SCN"
    cp "$STUB_SVC" "$SCN/bin/svc" || fatal "cannot install the stub svc in the scenario"
    chmod 755 "$SCN/bin/svc"
    MAC_OFFSET=4
    NORELOAD=
    : >"$RUN/svc.log"
}

octal_bytes() { # aa:bb:cc:dd:ee:ff -> \ooo\ooo... (POSIX %b escapes, as the CLI does)
    echo "$1" | awk -F: 'BEGIN { split("0123456789abcdef", d, "") } {
        for (i = 1; i <= NF; i++) {
            hi = index("0123456789abcdef", tolower(substr($i, 1, 1)));
            lo = index("0123456789abcdef", tolower(substr($i, 2, 1)));
            if (hi == 0 || lo == 0) { print "bad octet: " $i > "/dev/stderr"; exit 1 }
            printf "\\%03o", (hi - 1) * 16 + (lo - 1)
        }
    }'
}

put_bytes() { # $1 = file, $2 = mac, $3 = offset
    printf '%b' "$(octal_bytes "$2")" \
        | dd of="$1" bs=1 seek="$3" count=6 conv=notrunc 2>/dev/null \
        || fatal "cannot write $2 at offset $3 of $1"
}

mtk_header() { # $1 = file: the 4-byte header the real MTK image starts with
    printf '\001\000\010\000' | dd of="$1" bs=1 seek=0 count=4 conv=notrunc 2>/dev/null \
        || fatal "cannot write the header of $1"
}

make_nvram() { # $1 = size, $2 = MAC in the file at $3, $3 = offset (also the runtime MAC)
    [ "$3" -ge 4 ] || fatal "scenario offset $3 would overlap the 4-byte header"
    dd if=/dev/zero of="$SCN/nvram/WIFI" bs=1 count="$1" 2>/dev/null \
        || fatal "cannot create the synthetic NVRAM image"
    mtk_header "$SCN/nvram/WIFI"
    put_bytes "$SCN/nvram/WIFI" "$2" "$3"
    printf '%s\n' "$2" >"$SCN/net/wlan0/address"
    MAC_OFFSET=$3
}

# The calibration path as the CLI under test sees it.  In override mode that is
# the scenario file; under the mount namespace it is the device path, because the
# scenario directory is bound over it.  The fixtures below record this in
# WIFI.factory.path, and the CLI (and the app) treat a record that names another
# path as foreign - recording the host path here made every fixture image look
# foreign to the CLI in namespace mode, which is a defect in the fixture, not in
# the CLI.
dev_nv() {
    if [ "$REDIRECT" = override ]; then
        printf '%s' "$SCN/nvram/WIFI"
    else
        printf '%s' /mnt/vendor/nvdata/APCFG/APRDEB/WIFI
    fi
}

factory_from_nvram() { # $1 = factory MAC, $2 = offset: image identical to NVRAM except the MAC
    cp "$SCN/nvram/WIFI" "$SCN/record/WIFI.factory" || fatal "cannot copy the NVRAM image"
    put_bytes "$SCN/record/WIFI.factory" "$1" "$2"
    printf '%s\n' "$2" >"$SCN/record/WIFI.factory.offset"
    printf '%s\n' "$(dev_nv)" >"$SCN/record/WIFI.factory.path"
    sha256sum <"$SCN/record/WIFI.factory" | cut -d' ' -f1 >"$SCN/record/WIFI.factory.sha256"
}

make_factory_image() { # $1 = size, $2 = factory MAC, $3 = offset
    dd if=/dev/zero of="$SCN/record/WIFI.factory" bs=1 count="$1" 2>/dev/null \
        || fatal "cannot create the synthetic factory image"
    mtk_header "$SCN/record/WIFI.factory"
    put_bytes "$SCN/record/WIFI.factory" "$2" "$3"
    printf '%s\n' "$3" >"$SCN/record/WIFI.factory.offset"
    printf '%s\n' "$(dev_nv)" >"$SCN/record/WIFI.factory.path"
    sha256sum <"$SCN/record/WIFI.factory" | cut -d' ' -f1 >"$SCN/record/WIFI.factory.sha256"
}

hex_at() { # $1 = file, $2 = offset, $3 = length -> plain hex
    dd if="$1" bs=1 skip="$2" count="$3" 2>/dev/null | od -An -tx1 | tr -d ' \n'
}

mac_at() { # $1 = file, $2 = offset -> aa:bb:cc:dd:ee:ff
    hex_at "$1" "$2" 6 | sed 's/\(..\)/\1:/g; s/:$//'
}

snapshot() { # $1 = directory: one line per entry, with content hash and size:mtime
    find "$1" | LC_ALL=C sort | while IFS= read -r _f; do
        if [ -f "$_f" ]; then
            printf 'F %s %s %s\n' "${_f#"$1"/}" \
                "$(sha256sum <"$_f" | cut -d' ' -f1)" \
                "$(stat -c '%s:%Y' "$_f")"
        else
            printf 'D %s\n' "${_f#"$1"/}"
        fi
    done
}

rec_listing() { find "$SCN/record" | LC_ALL=C sort; }

run_cli() { # argv... -> the CLI's exit status in $LAST_RC; output in $RUN
    if [ "$REDIRECT" = override ]; then
        MACCHANGER_TEST=1 \
        MACCHANGER_DIR="$SCN/record" \
        MACCHANGER_NV="$SCN/nvram/WIFI" \
        MACCHANGER_NET="$SCN/net" \
        PATH="$SCN/bin:$PATH" \
        TEST_SVC_LOG="$RUN/svc.log" \
        TEST_NV_FILE="$SCN/nvram/WIFI" \
        TEST_NET_DIR="$SCN/net" \
        TEST_MAC_OFFSET="$MAC_OFFSET" \
        TEST_SVC_NORELOAD="$NORELOAD" \
        TEST_BUSYBOX="$BUSYBOX" \
        "$RUNSHELL" "$CLI" "$@" >"$RUN/out" 2>"$RUN/err"
        LAST_RC=$?
    else
        TEST_SVC_LOG="$RUN/svc.log" \
        TEST_NV_FILE="$SCN/nvram/WIFI" \
        TEST_NET_DIR="$SCN/net" \
        TEST_MAC_OFFSET="$MAC_OFFSET" \
        TEST_SVC_NORELOAD="$NORELOAD" \
        TEST_BUSYBOX="$BUSYBOX" \
        CLITEST_SHELL="$RUNSHELL" \
        unshare -m --propagation private "$RUNSHELL" "$SANDBOX" "$SCN" "$CLI" "$@" \
            >"$RUN/out" 2>"$RUN/err"
        LAST_RC=$?
        if [ "$LAST_RC" -eq "$SETUP_FAILED" ]; then
            printf 'clitest: the sandbox could not be set up for scenario %s:\n' "$SCN" >&2
            cat "$RUN/err" >&2
            printf 'CLITEST: ERROR\n'
            exit 2
        fi
    fi
    return 0
}

out_has() { grep -q -- "$1" "$RUN/out"; }
err_has() { grep -q -- "$1" "$RUN/err"; }

# A run that never reached the stub driver would make the write assertions
# vacuous, so every reinit-dependent assertion also asks the log.
svc_reloaded() { grep -q 'driver reloaded MAC' "$RUN/svc.log" 2>/dev/null; }

# A1/A2 share this body: run a command and report whether the device-visible
# tree and the exit status came out unchanged.  RO_WHY explains a failure.
RO_WHY=
ro_intact() { # argv... -> 0 = exit 0 and nothing changed
    _before=$(snapshot "$SCN")
    run_cli "$@"
    _after=$(snapshot "$SCN")
    RO_WHY=
    if [ "$LAST_RC" -ne 0 ]; then
        RO_WHY="exit status $LAST_RC (expected 0): $(sed -n '1p' "$RUN/err")"
        return 1
    fi
    if [ "$_before" != "$_after" ]; then
        RO_WHY="the device-visible tree changed ($(printf '%s\n' "$_before" | wc -l | tr -d ' ') -> $(printf '%s\n' "$_after" | wc -l | tr -d ' ') entries)"
        return 1
    fi
    return 0
}

# =============================================================================
section "A: read-only and refusal paths"

# A0: the CLI's own testing hook must be gated, or this suite would be proving
# things about a redirected process while the device could still be poked.
if [ "$REDIRECT" = override ]; then
    scenario override_gate
    make_nvram 64 04:f9:93:11:36:bf 4
    _before=$(snapshot "$SCN")
    MACCHANGER_DIR="$SCN/record" MACCHANGER_NV="$SCN/nvram/WIFI" MACCHANGER_NET="$SCN/net" \
    PATH="$SCN/bin:$PATH" \
        "$RUNSHELL" "$CLI" show >"$RUN/out" 2>"$RUN/err"
    _rc=$?
    if ! grep -q 'TEST OVERRIDES ACTIVE' "$RUN/out" 2>/dev/null \
       && ! grep -q -- "$SCN" "$RUN/out" "$RUN/err" 2>/dev/null \
       && [ "$(snapshot "$SCN")" = "$_before" ]; then
        check "A0 the test override is gated on MACCHANGER_TEST: without it, MACCHANGER_NV/DIR/NET redirect nothing" 0
    else
        check "A0 the test override is gated on MACCHANGER_TEST: without it, MACCHANGER_NV/DIR/NET redirect nothing" 1 \
            "exit $_rc; the sandbox path appeared in the output or the tree changed - an unguarded override could silently redirect a real device"
    fi

    # A0b: a revision that does ship a hook must gate it on the literal value.
    # '[ -n "$MACCHANGER_TEST" ]' arms the redirect for MACCHANGER_TEST=0 - a
    # documented "off" that means "on" - and for 'false', 'off' and 'no'.  The
    # shipped revision no longer has a hook at all, so this case only runs when the
    # suite is pointed at a revision that still has one, where getting it wrong is
    # exactly the defect that must not be reported green.
    scenario override_gate_zero
    make_nvram 64 04:f9:93:11:36:bf 4
    _before=$(snapshot "$SCN")
    MACCHANGER_TEST=0 MACCHANGER_DIR="$SCN/record" MACCHANGER_NV="$SCN/nvram/WIFI" MACCHANGER_NET="$SCN/net" \
    PATH="$SCN/bin:$PATH" \
        "$RUNSHELL" "$CLI" show >"$RUN/out" 2>"$RUN/err"
    _rc=$?
    if ! grep -q 'TEST OVERRIDES ACTIVE' "$RUN/out" "$RUN/err" 2>/dev/null \
       && [ "$(snapshot "$SCN")" = "$_before" ]; then
        check "A0b the hook gate requires the literal value: MACCHANGER_TEST=0 (and any other non-1 value) redirects nothing" 0
    else
        check "A0b the hook gate requires the literal value: MACCHANGER_TEST=0 (and any other non-1 value) redirects nothing" 1 \
            "exit $_rc; MACCHANGER_TEST=0 armed the redirect ('[ -n \"\$MACCHANGER_TEST\" ]' accepts any non-empty value), so an environment that says 'off' turns it on"
    fi
else
    info "A0/A0b skipped: this redirect ($REDIRECT) does not use the CLI's own hook"
fi

scenario readonly
make_nvram 64 04:f9:93:11:36:bf 4
if ro_intact show; then
    check "A1 show is read-only: exit 0, whole device tree byte-identical (sha256+size+mtime+listing)" 0
else
    check "A1 show is read-only: exit 0, whole device tree byte-identical (sha256+size+mtime+listing)" 1 "$RO_WHY"
fi
if out_has '^interface      : wlan0$' && out_has '04:f9:93:11:36:bf'; then
    check "A1b show really read the sandbox device (wlan0 and the synthetic NVRAM MAC were reported)" 0
else
    check "A1b show really read the sandbox device (wlan0 and the synthetic NVRAM MAC were reported)" 1 \
        "stdout was: $(tr '\n' '|' <"$RUN/out")"
fi

# A2: the negative control for A1.  `set` does write, so the A1 assertion must
# report a change - otherwise A1 is blind and its PASS above means nothing.
scenario readonly_neg
make_nvram 64 04:f9:93:11:36:bf 4
if ro_intact set 02:11:22:33:44:55; then
    check "A2 negative control: the A1 assertion FAILS for a command that writes (set)" 1 \
        "the assertion called the tree unchanged after 'set' - it cannot detect a modification"
elif printf '%s' "$RO_WHY" | grep -q 'tree changed'; then
    check "A2 negative control: the A1 assertion FAILS for a command that writes (set)" 0
    info "A2: the assertion reported '$RO_WHY' - it detects the write"
else
    check "A2 negative control: the A1 assertion FAILS for a command that writes (set)" 1 \
        "it failed for the wrong reason ($RO_WHY), so the control proves nothing"
fi

# A3: restore with no image must refuse and create nothing.
scenario restore_missing
make_nvram 64 02:11:22:33:44:55 4
_nv_before=$(snapshot "$SCN/nvram")
run_cli restore
if [ "$LAST_RC" -eq "$EX_NOBACKUP" ] && [ "$(rec_listing)" = "$SCN/record" ] \
   && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ]; then
    check "A3 restore with no factory image: exit $LAST_RC (=EX_NOBACKUP), nothing created, target untouched" 0
else
    check "A3 restore with no factory image: exit $LAST_RC (=EX_NOBACKUP), nothing created, target untouched" 1 \
        "record=$(rec_listing | tr '\n' ' '), target changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES)"
fi

# A4: restore with a size-mismatched image must refuse, not truncate.
scenario restore_size
make_nvram 64 02:11:22:33:44:55 4
make_factory_image 32 04:f9:93:11:36:bf 4
_nv_before=$(snapshot "$SCN/nvram")
run_cli restore
if [ "$LAST_RC" -eq "$EX_NOBACKUP" ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ]; then
    check "A4 restore of a 32-byte image over a 64-byte NVRAM: exit $LAST_RC (=EX_NOBACKUP), target byte-identical, still 64 bytes" 0
else
    check "A4 restore of a 32-byte image over a 64-byte NVRAM: exit $LAST_RC (=EX_NOBACKUP), target byte-identical, still 64 bytes" 1 \
        "target is now $(wc -c <"$SCN/nvram/WIFI") bytes, changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES)"
fi

# A5: set whose live MAC is nowhere in the NVRAM must refuse, not write blind.
scenario set_absent
make_nvram 64 aa:bb:cc:dd:ee:ff 4
printf '%s\n' 'de:ad:be:ef:00:01' >"$SCN/net/wlan0/address"
_nv_before=$(snapshot "$SCN/nvram")
run_cli set 02:11:22:33:44:55
if [ "$LAST_RC" -eq "$EX_PRECOND" ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] \
   && [ "$(rec_listing)" = "$SCN/record" ]; then
    check "A5 set with the live MAC absent from the NVRAM: exit $LAST_RC (=EX_PRECOND), target byte-identical, no image captured" 0
else
    check "A5 set with the live MAC absent from the NVRAM: exit $LAST_RC (=EX_PRECOND), target byte-identical, no image captured" 1 \
        "target changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES), record=$(rec_listing | tr '\n' ' ')"
fi

# A6: a locally administered live MAC is evidence of an existing spoof, so it
# must not be enshrined as the factory value - and nothing may be written.
scenario set_laa
make_nvram 64 02:11:22:33:44:55 4
_nv_before=$(snapshot "$SCN/nvram")
run_cli set 02:aa:bb:cc:dd:ee
if [ "$LAST_RC" -eq "$EX_PRECOND" ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] \
   && [ "$(rec_listing)" = "$SCN/record" ]; then
    check "A6 set while the NVRAM holds a locally administered (spoofed) MAC: exit $LAST_RC (=EX_PRECOND), target byte-identical, no 'factory' value fabricated" 0
else
    check "A6 set while the NVRAM holds a locally administered (spoofed) MAC: exit $LAST_RC (=EX_PRECOND), target byte-identical, no 'factory' value fabricated" 1 \
        "target changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES), record=$(rec_listing | tr '\n' ' ')"
fi

# A7: a multicast live MAC can never be a factory burn-in, so it must not be
# captured as one - not even with --assume-factory, which acknowledges a
# plausibly spoofed value, not an address that is not a station address at all.
# The harm this pins: the value would also land in $DIR/factory.txt, the record
# the app gates 'Restore factory' on and refuses such a value in, so the CLI
# would have broken the app's recovery path.
for _a7 in "backup" "backup --assume-factory"; do
    scenario "mcast_$(echo "$_a7" | tr -d ' -')"
    make_nvram 64 01:aa:bb:cc:dd:ee 4
    _nv_before=$(snapshot "$SCN/nvram")
    run_cli $_a7
    if [ "$LAST_RC" -ne 0 ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] \
       && [ "$(rec_listing)" = "$SCN/record" ]; then
        check "A7 '$_a7' on an NVRAM whose live MAC is multicast (01:aa:bb:cc:dd:ee): exit $LAST_RC, target byte-identical, no image and no app record captured" 0
    else
        check "A7 '$_a7' on an NVRAM whose live MAC is multicast (01:aa:bb:cc:dd:ee): exit $LAST_RC, target byte-identical, no image and no app record captured" 1 \
            "a multicast address, which no vendor burns in, was accepted as factory evidence: target changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES), record=$(rec_listing | tr '\n' ' ')"
    fi
done
# The same rule for the degenerate all-zero value.
scenario mcast_zero
make_nvram 64 00:00:00:00:00:00 4
_nv_before=$(snapshot "$SCN/nvram")
run_cli backup
if [ "$LAST_RC" -ne 0 ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] \
   && [ "$(rec_listing)" = "$SCN/record" ]; then
    check "A7b 'backup' on an NVRAM whose live MAC is all-zero: exit $LAST_RC, target byte-identical, nothing captured" 0
else
    check "A7b 'backup' on an NVRAM whose live MAC is all-zero: exit $LAST_RC, target byte-identical, nothing captured" 1 \
        "record=$(rec_listing | tr '\n' ' ')"
fi

# A8: M2's acceptance criterion - the CLI is the app's other writer of the same
# calibration file, so while the app holds /data/adb/macchanger/lock the CLI must
# refuse and write nothing.  The lock directory is $SCN/record/lock in both
# redirect modes ($DIR is the scenario record directory, bound over
# /data/adb/macchanger in namespace mode).
scenario lock_held
make_nvram 64 04:f9:93:11:36:bf 4
mkdir -p "$SCN/record/lock" || fatal "cannot create the scenario lock directory"
date +%s >"$SCN/record/lock/ts"
printf '%s\n' app-token >"$SCN/record/lock/owner"
_nv_before=$(snapshot "$SCN/nvram")
run_cli set 02:11:22:33:44:55
if [ "$LAST_RC" -ne 0 ] && [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] \
   && err_has 'another macchanger operation is running'; then
    check "A8 with a fresh lock held by the app, 'set' exits $LAST_RC without writing (M2: the two front ends cannot write the same file at once)" 0
else
    check "A8 with a fresh lock held by the app, 'set' exits $LAST_RC without writing (M2: the two front ends cannot write the same file at once)" 1 \
        "exit $LAST_RC; target changed=$([ "$(snapshot "$SCN/nvram")" = "$_nv_before" ] && echo no || echo YES); stderr: $(sed -n '1p' "$RUN/err")"
fi
# ...and an abandoned lock (older than the app's own 300 s window) must not lock
# the device out: the write proceeds and the lock is released afterwards.
printf '%s\n' "$(( $(date +%s) - 400 ))" >"$SCN/record/lock/ts"
run_cli set 02:11:22:33:44:55
if [ "$LAST_RC" -eq 0 ] && [ "$(mac_at "$SCN/nvram/WIFI" 4)" = "02:11:22:33:44:55" ] \
   && [ ! -e "$SCN/record/lock" ]; then
    check "A8b an abandoned lock (400 s old) is broken as stale, the write proceeds and the lock is released" 0
else
    check "A8b an abandoned lock (400 s old) is broken as stale, the write proceeds and the lock is released" 1 \
        "exit $LAST_RC; offset 4 = $(mac_at "$SCN/nvram/WIFI" 4); lock left behind=$( [ -e "$SCN/record/lock" ] && echo YES || echo no)"
fi

# =============================================================================
section "B: the write is located, not assumed"

scenario offset40
make_nvram 64 04:f9:93:11:36:bf 40
put_bytes "$SCN/nvram/WIFI" de:ad:be:ef:00:01 4     # decoy at the "expected" MTK offset
_nv_size=$(wc -c <"$SCN/nvram/WIFI")
run_cli set 02:11:22:33:44:55
if [ "$LAST_RC" -eq 0 ] \
   && [ "$(mac_at "$SCN/nvram/WIFI" 40)" = "02:11:22:33:44:55" ] \
   && [ "$(mac_at "$SCN/nvram/WIFI" 4)" = "de:ad:be:ef:00:01" ]; then
    check "B1 set with the live MAC at offset 40: exit 0, the MAC was written at 40 and the decoy at offset 4 is untouched" 0
else
    check "B1 set with the live MAC at offset 40: exit 0, the MAC was written at 40 and the decoy at offset 4 is untouched" 1 \
        "exit $LAST_RC, offset 40 = $(mac_at "$SCN/nvram/WIFI" 40), offset 4 = $(mac_at "$SCN/nvram/WIFI" 4)"
fi
if [ "$(wc -c <"$SCN/nvram/WIFI")" = "$_nv_size" ] \
   && [ "$(cat "$SCN/net/wlan0/address")" = "02:11:22:33:44:55" ] && svc_reloaded; then
    check "B2 the write is 6 bytes in place at the found offset: length still $_nv_size, stub driver re-read offset 40" 0
else
    check "B2 the write is 6 bytes in place at the found offset: length still $_nv_size, stub driver re-read offset 40" 1 \
        "size now $(wc -c <"$SCN/nvram/WIFI"), runtime now $(cat "$SCN/net/wlan0/address"), svc log: $(tr '\n' '|' <"$RUN/svc.log")"
fi

# =============================================================================
section "C: verdicts and documented exit codes"

# C1: the write lands, the driver ignores it, so the verdict must not be success.
scenario no_reread
make_nvram 64 04:f9:93:11:36:bf 4
NORELOAD=1
run_cli set 02:11:22:33:44:55
if [ "$LAST_RC" -eq "$EX_APPLY" ] && [ "$(mac_at "$SCN/nvram/WIFI" 4)" = "02:11:22:33:44:55" ]; then
    check "C1 driver ignores the change: exit $LAST_RC (=EX_APPLY) while the NVRAM does hold the new MAC" 0
else
    check "C1 driver ignores the change: exit $LAST_RC (=EX_APPLY) while the NVRAM does hold the new MAC" 1 \
        "offset 4 now $(mac_at "$SCN/nvram/WIFI" 4)"
fi
NORELOAD=

# C2: a good restore is verified against the image, not against the runtime MAC.
scenario restore_ok
make_nvram 64 02:11:22:33:44:55 4
factory_from_nvram 04:f9:93:11:36:bf 4
_nv_size=$(wc -c <"$SCN/nvram/WIFI")
run_cli restore
if [ "$LAST_RC" -eq 0 ] && cmp -s "$SCN/record/WIFI.factory" "$SCN/nvram/WIFI" \
   && [ "$(wc -c <"$SCN/nvram/WIFI")" = "$_nv_size" ] \
   && [ "$(cat "$SCN/net/wlan0/address")" = "04:f9:93:11:36:bf" ] && svc_reloaded; then
    check "C2 restore of a good image: exit 0, target == image byte-for-byte, length $_nv_size, runtime back to the factory MAC" 0
else
    check "C2 restore of a good image: exit 0, target == image byte-for-byte, length $_nv_size, runtime back to the factory MAC" 1 \
        "exit $LAST_RC, cmp=$(cmp -s "$SCN/record/WIFI.factory" "$SCN/nvram/WIFI" && echo equal || echo DIFFERENT), size=$(wc -c <"$SCN/nvram/WIFI"), runtime=$(cat "$SCN/net/wlan0/address"), svc log: $(tr '\n' '|' <"$RUN/svc.log")"
fi

# D1/D2/D3: an intact, digest-valid, same-size image is not automatically a
# factory image.  A recorded value that is locally administered is the signature
# of a spoof, so writing it back is not a recovery and must not be reported as
# one; 'doctor' reports the very same record as a blocker.  The flag is the
# caller's acknowledgement, so D2 proves the refusal is a gate, not a wall.
scenario restore_la
make_nvram 64 04:f9:93:11:36:bf 4
factory_from_nvram 02:11:22:33:44:55 4
_la_before=$(snapshot "$SCN/nvram")
run_cli restore
_la_rc=$LAST_RC
_la_after=$(snapshot "$SCN/nvram")
if [ "$_la_rc" -eq "$EX_NOBACKUP" ] && [ "$_la_after" = "$_la_before" ]; then
    check "D1 restore of an intact image whose recorded MAC is locally administered: exit $_la_rc (=EX_NOBACKUP), target byte-identical" 0
else
    check "D1 restore of an intact image whose recorded MAC is locally administered: exit $_la_rc (=EX_NOBACKUP), target byte-identical" 1 \
        "expected rc=$EX_NOBACKUP; NVRAM now $(mac_at "$SCN/nvram/WIFI" 4), image holds 02:11:22:33:44:55"
fi

run_cli panic
_la_panic_rc=$LAST_RC
if [ "$_la_panic_rc" -eq "$EX_NOBACKUP" ] && [ "$(snapshot "$SCN/nvram")" = "$_la_before" ]; then
    check "D3 panic refuses the same locally administered image: exit $_la_panic_rc (=EX_NOBACKUP), target byte-identical" 0
else
    check "D3 panic refuses the same locally administered image: exit $_la_panic_rc (=EX_NOBACKUP), target byte-identical" 1 \
        "expected rc=$EX_NOBACKUP; NVRAM now $(mac_at "$SCN/nvram/WIFI" 4)"
fi

run_cli restore --allow-nonfactory-image
_la_ack_rc=$LAST_RC
if [ "$_la_ack_rc" -eq 0 ] && cmp -s "$SCN/record/WIFI.factory" "$SCN/nvram/WIFI"; then
    check "D2 restore --allow-nonfactory-image writes the acknowledged image: exit 0, target == image byte-for-byte" 0
else
    check "D2 restore --allow-nonfactory-image writes the acknowledged image: exit 0, target == image byte-for-byte" 1 \
        "exit $_la_ack_rc, cmp=$(cmp -s "$SCN/record/WIFI.factory" "$SCN/nvram/WIFI" && echo equal || echo DIFFERENT)"
fi

# D4: at most one positional exists.  The parser used to overwrite CMD's
# argument on every further positional, so 'set 12:34:56:78:9a:bc EXTRA'
# validated EXTRA and 'show a b' was accepted silently.
scenario arg_parser
make_nvram 64 04:f9:93:11:36:bf 4
run_cli set 12:34:56:78:9a:bc EXTRA
_d4_set=$LAST_RC
run_cli show a b
_d4_show=$LAST_RC
if [ "$_d4_set" -eq "$EX_USAGE" ] && [ "$_d4_show" -eq "$EX_USAGE" ]; then
    check "D4 a second positional is a usage error: 'set MAC EXTRA' and 'show a b' both exit $EX_USAGE" 0
else
    check "D4 a second positional is a usage error: 'set MAC EXTRA' and 'show a b' both exit $EX_USAGE" 1 \
        "'set MAC EXTRA' exit $_d4_set, 'show a b' exit $_d4_show"
fi

# C3/C4: the documented exit-code contract.  Help and usage are 0; every
# refusal is its own class, and none of them may touch the target.
scenario exit_codes
make_nvram 64 04:f9:93:11:36:bf 4
_nv_before=$(snapshot "$SCN/nvram")
_help=$(run_cli help; echo "$LAST_RC")
_empty=$(run_cli; echo "$LAST_RC")
_unk_cmd=$(run_cli frobnicate; echo "$LAST_RC")
_unk_opt=$(run_cli --frobnicate; echo "$LAST_RC")
_set_noarg=$(run_cli set; echo "$LAST_RC")
_set_bad=$(run_cli set zz:zz:zz:zz:zz:zz; echo "$LAST_RC")
_set_zero=$(run_cli set 00:00:00:00:00:00; echo "$LAST_RC")
_set_bcast=$(run_cli set ff:ff:ff:ff:ff:ff; echo "$LAST_RC")
_set_mcast=$(run_cli set 01:00:5e:00:00:01; echo "$LAST_RC")
_want="help=0 usage=0 unknown-cmd=$EX_USAGE unknown-opt=$EX_USAGE set-no-arg=$EX_USAGE set-invalid=$EX_USAGE set-all-zero=$EX_USAGE set-broadcast=$EX_USAGE set-multicast=$EX_USAGE"
_got="help=$_help usage=$_empty unknown-cmd=$_unk_cmd unknown-opt=$_unk_opt set-no-arg=$_set_noarg set-invalid=$_set_bad set-all-zero=$_set_zero set-broadcast=$_set_bcast set-multicast=$_set_mcast"
if [ "$_want" = "$_got" ]; then
    check "C3 exit codes match the classes the CLI documents: help/usage 0; unknown command/option, missing argument, invalid, all-zero, broadcast and multicast MACs $EX_USAGE" 0
else
    check "C3 exit codes match the classes the CLI documents: help/usage 0; unknown command/option, missing argument, invalid, all-zero, broadcast and multicast MACs $EX_USAGE" 1 \
        "want: $_want / got: $_got"
fi
if [ "$(snapshot "$SCN/nvram")" = "$_nv_before" ]; then
    check "C4 all nine refusal/usage runs above left the target byte-identical" 0
else
    check "C4 all nine refusal/usage runs above left the target byte-identical" 1 \
        "the synthetic NVRAM changed during a refusal: a rejected MAC was written anyway"
fi

# =============================================================================
section "findings (observed, not owned by this suite)"
if [ "$HAS_OVERRIDE" = yes ]; then
    info "cli/macchanger.sh still ships an environment redirect (MACCHANGER_TEST + MACCHANGER_DIR/NV/NET) and this suite used it. For a tool that rewrites a calibration file as root that is a root-write primitive: whoever controls the environment of the invocation picks the file that is written. The shipped revision removed it (assertion A0b pins the gate for revisions that still have one); this suite drives the paths from outside with a mount namespace instead."
else
    info "cli/macchanger.sh has NO environment redirect for its device paths (DIR/BAK/NV/NET are hard assignments; a comment may still name the variables, which is why the hook probe above runs the script instead of grepping it), so this suite redirects them from outside with a private mount namespace that the script knows nothing about."
fi
if grep -A 4 '^reinit_wifi()' "$CLI" 2>/dev/null | grep -qE '\bsleep [0-9]+'; then
    info "cli/macchanger.sh still restarts WiFi with fixed sleeps and no state poll (reinit_wifi): the app side of H7 polls for the state instead. This suite drives the restart through a stub, so it cannot judge the real reload; tools/checks/checks.sh flags it as a hazard."
fi

# --- deliberately injected failure (--self-test only) -----------------------
if [ "${CLITEST_INJECT_FAIL:-}" = 1 ]; then
    printf '\n--- injected failure\n'
    info "CLITEST_INJECT_FAIL=1: registering one deliberately false assertion"
    check "INJECTED FAILURE (--self-test: a FAIL must make the suite exit non-zero)" 1 "this failure is deliberate"
fi

# --- summary ----------------------------------------------------------------
printf '\n%s\n' "clitest: $PASS of $TOTAL assertions passed, $FAIL failed (redirect=$REDIRECT, shell=$RUNSHELL, cli=$CLI)"
if [ "$FAIL" -eq 0 ]; then
    echo "CLITEST: PASS"
    exit 0
fi
echo "CLITEST: FAIL"
exit 1
