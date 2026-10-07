#!/bin/sh
#
# checks/checks.sh - regression checks for the claims, hazards and doc/code
# matches that this audit corrected.
#
#   sh tools/checks/checks.sh [ROOT]
#
# ROOT defaults to the repository root (two levels above this script).  Every
# check prints exactly one line:
#
#   PASS [PROOF]     <name> - <evidence>
#   FAIL [PROOF]     <name> - <evidence>
#   PASS [HEURISTIC] <name> - <evidence>
#   INFO [PROOF]     <name> - <evidence>
#
# PROOF means a deterministic fact about the files this script scanned: a file
# does or does not exist, a literal does or does not occur, two numbers taken
# from two different files agree.  It means "this exact pattern is absent from
# the files that were scanned" - never "this hazard is absent", because a scan
# reads text and text can express the same write in a shape no pattern lists.
# HEURISTIC means a grep a determined author could evade or that a human still
# has to judge - each heuristic line says what it cannot see.  The exit status
# is 0 when no check FAILs, 1 when any check FAILs, and 2 on a usage or harness
# error.  The summary counts PASS, INFO and FAIL separately: an INFO line is
# not a check that passed, it is a line that asserted nothing.
#
# What this script is NOT: it is not a behavioural test.  It cannot run the app,
# it cannot prove the NVRAM write path is correct (see tools/clitest/), it cannot
# rebuild or sign the APK (no Android SDK here), and a heuristic PASS is not a
# promise.  It exists so that the specific mistakes this audit found cannot come
# back unnoticed.
#
# The checks, and the audit issue each one guards:
#
#   1  no signing key in the tree (C5)
#   2  no 'pass:android' anywhere in the shipped tree (C5)
#   3  no shell redirection into a calibration path (C1)          [HEURISTIC]
#   4  no cp/mv/install onto a calibration path (C1, C4)          [HEURISTIC]
#   5  every seek= carries a count=, so no write is unbounded (C1, C3, H6)
#   6  no fixed sleep without a state poll on the WiFi-restart path (H7)
#   7  the manifest and the shipped APK request zero permissions (design)
#   8  the docs do not claim what the code does not do (L1, M5, C5, H5, H8):
#      no 'head -3' positional parsing, the CLI's MediaTek-only scope matches
#      the code, the CLI's writable paths are MTK paths only, the sidecar names
#      it documents are the ones it writes, the documented targetSdk matches the
#      manifest, the documented zero-permission claim matches the manifest, the
#      documented prebuilt APK hash matches the shipped APK, and the two claims
#      that need a human (non-persistent ip-link fallback, Android 12+ SSID
#      limitation) are still present.
#
# Requires: POSIX sh, grep, find, sed, sha256sum, awk, and (for the APK check)
# unzip.  No network, no Android SDK, no Gradle, no busyloop.
#
# Environment:
#   CHECK_EXCLUDES   extra grep -r arguments, e.g. CHECK_EXCLUDES='--exclude-dir=foo'

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=${1:-$SCRIPT_DIR/../..}
ROOT=$(CDPATH= cd -- "$ROOT" && pwd) || {
    printf 'checks: no such directory: %s\n' "${1:-$ROOT}" >&2
    exit 2
}

FAILS=0
PASSES=0
INFOS=0
LINES=0

report() { # $1 = name, $2 = PROOF|HEURISTIC, $3 = pass|fail|info, $4 = evidence
    LINES=$((LINES + 1))
    case "$3" in
        pass) PASSES=$((PASSES + 1)); printf 'PASS [%s] %s - %s\n' "$2" "$1" "$4" ;;
        fail) FAILS=$((FAILS + 1)); printf 'FAIL [%s] %s - %s\n' "$2" "$1" "$4" ;;
        *)    INFOS=$((INFOS + 1)); printf 'INFO [%s] %s - %s\n' "$2" "$1" "$4" ;;
    esac
}

section() { printf '\n--- %s\n' "$1"; }

[ -d "$ROOT/app" ] || { printf 'checks: %s does not look like the project root (no app/)\n' "$ROOT" >&2; exit 2; }

# grep -r arguments used by every source scan.  AUDIT.md and audit/ are the
# audit's own artifacts: they quote the very strings this script hunts for (the
# old 'pass:android', the shipped keystore), so scanning them would make the
# checks fail forever on a correct tree.  They are also not shipped by
# tools/package.sh.  .git and dist/ are not source either.  checks.sh excludes
# itself: it necessarily contains its own patterns and their names as literals,
# and a scanner that reports its own source as a finding is pure noise.
SCAN_EXCLUDES="--exclude-dir=.git --exclude-dir=dist --exclude-dir=audit --exclude=AUDIT.md --exclude=checks.sh"
if [ -n "${CHECK_EXCLUDES:-}" ]; then
    SCAN_EXCLUDES="$SCAN_EXCLUDES $CHECK_EXCLUDES"
fi

# shellcheck disable=SC2086  # word-split on purpose: these are grep arguments
scan() { grep -rn $SCAN_EXCLUDES "$@" "$ROOT" 2>/dev/null; }

# Checks 3 and 4 read CODE, not prose.  A sentence that quotes a hazard is not a
# write, and this project deliberately quotes these shapes in its README, in
# tools/checks/README.md and in the audit (AUDIT.md and audit/ are excluded
# above for the same reason).  Scanning prose made the checks fire on correct
# documentation, and - worse - let a documentation example such as
# 'DIR=/mnt/vendor/nvdata' put DIR into the alias vocabulary below, which then
# turned an unrelated 'mv ... "$DIR/factory.txt"' in the CLI into a finding.
# Documentation is therefore out of scope for the two pattern checks, and only
# for them: check 2 (a password in a README is still a leak) still reads every
# packaged file.
# shellcheck disable=SC2086
scan_code() {
    grep -rn $SCAN_EXCLUDES --exclude='*.md' --exclude='*.txt' --exclude='*.json' "$@" "$ROOT" 2>/dev/null
}

printf 'checks: root %s\n' "$ROOT"

# =============================================================================
section "trust and secrets"

# --- 1. no signing key in the tree ------------------------------------------
_keys=$(find "$ROOT" -path "$ROOT/.git" -prune -o -path "$ROOT/dist" -prune -o -type f \
        \( -name '*.jks' -o -name '*.keystore' -o -name '*.bks' -o -name '*.p12' \
           -o -name '*.pfx' -o -name '*.pk8' -o -name '*.pem' -o -name '*.der' \
           -o -name '*.key' \) -print 2>/dev/null | LC_ALL=C sort)
_tracked=
if [ -d "$ROOT/.git" ] && command -v git >/dev/null 2>&1; then
    _tracked=$(git -C "$ROOT" ls-files 2>/dev/null \
        | grep -E '\.(jks|keystore|bks|p12|pfx|pk8|pem|der)$')
fi
if [ -n "$_keys" ]; then
    report "no signing key in the tree (C5)" PROOF fail "found: $(printf '%s' "$_keys" | tr '\n' ' ')"
elif [ -n "$_tracked" ]; then
    report "no signing key in the tree (C5)" PROOF fail "committed signing material: $(printf '%s' "$_tracked" | tr '\n' ' ')"
else
    report "no signing key in the tree (C5)" PROOF pass "no *.jks/*.keystore/*.bks/*.p12/*.pfx/*.pk8/*.pem/*.der/*.key under $ROOT (outside .git/ and dist/), and none tracked by git"
fi

# --- 2. no keystore password in the shipped tree ----------------------------
# The known literal, plus any password *value* written out in the source.  A
# variable reference such as build.sh's '-storepass "$KS_PASS"' is correct and
# must not be flagged, and neither is the documented placeholder KS_PASS='...'
# (a quoted or bracketed value is a placeholder, not a secret), so the pattern
# requires the character after '=' to be a bare literal.
_hits=$(scan -e 'pass: *android' \
              --exclude=package.sh \
              -E -e '(KS_PASS|STORE_PASS|STOREPASS|KEY_PASS|KEYPASS|PASSWORD)=[^"'"'"'$<[:space:]]' \
              -E -e '(storepass|keypass|storePassword|keyPassword)[[:space:]=]+["'"'"']?[^"'"'"'$[:space:]]')
_audit_quotes=$(grep -rl -e 'pass: *android' "$ROOT/AUDIT.md" "$ROOT/audit" 2>/dev/null | tr '\n' ' ')
if [ -n "$_hits" ]; then
    report "no keystore password anywhere in the shipped tree (C5)" PROOF fail "found: $(printf '%s' "$_hits" | head -5 | tr '\n' ' ')"
else
    report "no keystore password anywhere in the shipped tree (C5)" PROOF pass "no 'pass:android' literal and no literal password value assigned to or passed to keytool/apksigner (a '\$KS_PASS' reference is not flagged)"
    if [ -n "$_audit_quotes" ]; then
        report "the audit artifacts quote the old password and are excluded (and are not packaged)" PROOF info "$(printf '%s' "$_audit_quotes" | sed "s|$ROOT/||g")"
    fi
fi

# =============================================================================
section "the deliverable, and the directory around it"

# --- 2b. the hand-off boundary: what sits NEXT TO the tree ------------------
# Check 1 above can only see inside the tree, and it passed while a
# byte-identical copy of the very same compromised key sat one directory up
# (_private/ks.jks, next to .pristine/app/ks.jks).  Anything that zips, tars or
# copies the enclosing directory - the hand-off pattern that leaked the key the
# first time - re-ships it beside the APK.  That directory is not this tree's to
# police, and the pristine snapshot must not be modified, so the finding is
# reported rather than failed; the gate that refuses such an artifact is
# 'sh tools/package.sh --check PATH', run on whatever actually leaves the
# machine.  Both halves are stated here because a check that silently ignored
# the boundary is what let this stand.
_parent=$(CDPATH= cd -- "$ROOT/.." && pwd)
_root_base=$(basename -- "$ROOT")
_around=
if [ -d "$_parent" ] && [ "$_parent" != "$ROOT" ]; then
    _around=$(find "$_parent" -maxdepth 3 \
        \( -name "$_root_base" -o -name dist -o -name .git -o -name node_modules \) -prune -o \
        -type f -print 2>/dev/null | LC_ALL=C sort | while IFS= read -r _af; do
            _areason=
            case "$_af" in
                *.jks|*.keystore|*.bks|*.p12|*.pfx|*.pk8|*.pem|*.der|*.key)
                    _areason="by name" ;;
            esac
            if [ -z "$_areason" ]; then
                _am=$(dd if="$_af" bs=16 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
                case "$_am" in
                    feedfeed*)        _areason="JKS magic" ;;
                    cececece*)        _areason="JCEKS magic" ;;
                    3082*0201033082*) _areason="PKCS#12 magic" ;;
                esac
            fi
            [ -n "$_areason" ] || continue
            printf '%s [%s, sha256 %s] ' "$_af" "$_areason" \
                "$(sha256sum <"$_af" 2>/dev/null | awk '{ print substr($1, 1, 16) }')"
        done)
fi
if [ -n "$_around" ]; then
    report "the directory around the tree carries no signing key (C5, hand-off boundary)" PROOF info \
        "$_around- a hand-off that zips/tars/copies $_parent re-ships the key beside the APK. Reported, not fixed: that directory and the pristine snapshot are outside this tree, and .pristine must not be modified. Deliver the tree or the tools/package.sh archive and prove the artifact with 'sh tools/package.sh --check PATH'."
else
    report "the directory around the tree carries no signing key (C5, hand-off boundary)" PROOF pass \
        "$_parent holds no keystore, by name or by keystore magic bytes, outside $ROOT (dist/ and the tree itself are excluded; the tree is check 1)"
fi

# --- 2c. the newest archive in dist/ passes the hand-off gate --------------
# The end-to-end half of the same issue: whatever 'sh tools/package.sh' last
# produced is run through the gate that refuses a key or the audit inside it.
_dist=$(CDPATH= cd -- "$ROOT/.." && pwd)/dist
_arc=$(ls -1t "$_dist"/*.tar.gz 2>/dev/null | head -n 1)
if [ -z "$_arc" ]; then
    report "the newest archive in dist/ passes the hand-off gate (C5)" PROOF info \
        "no .tar.gz in $_dist: run 'sh tools/package.sh' to build one and this check will scan it"
elif [ ! -f "$ROOT/tools/package.sh" ]; then
    report "the newest archive in dist/ passes the hand-off gate (C5)" PROOF info \
        "tools/package.sh is missing, so the gate cannot be run on $_arc"
else
    _hout=$(sh "$ROOT/tools/package.sh" --check "$_arc" 2>&1)
    _hrc=$?
    _hline=$(printf '%s\n' "$_hout" | grep '^HANDOFF: ' | head -n 1)
    # The archive is named with its hash, and package.sh --check now also
    # compares its members against this tree, so a superseded archive cannot be
    # endorsed here as if it were this revision's output.
    _arc_id="$(basename -- "$_arc") sha256 $(sha256sum <"$_arc" 2>/dev/null | awk '{ print $1 }')"
    if [ "$_hrc" -eq 0 ]; then
        report "the newest archive in dist/ passes the hand-off gate (C5)" PROOF pass \
            "$_arc_id - ${_hline:-no verdict line} (tools/package.sh --check, exit 0)"
    else
        report "the newest archive in dist/ passes the hand-off gate (C5)" PROOF fail \
            "$_arc_id - ${_hline:-no verdict line} (tools/package.sh --check, exit $_hrc) - do not hand this archive over"
    fi
fi

# =============================================================================
section "the write path"

# Test scripts (*.test.sh) build synthetic fixtures on purpose: they redirect
# into their own throwaway $NV and move files around inside their sandbox, which
# is exactly what the product must never do.  Checks 3-5 are about the shipped
# writer, so test scripts are excluded and named here rather than silently
# filtered - the behavioural guard for a test script is tools/clitest/.
TEST_FILES=$(find "$ROOT" -path "$ROOT/.git" -prune -o -path "$ROOT/dist" -prune \
             -o -name '*.test.sh' -print 2>/dev/null | sed "s|$ROOT/||" | LC_ALL=C sort | tr '\n' ' ')
no_tests() { grep -v -E '(^|/)[^/]*\.test\.sh:'; }

# --- the calibration-path vocabulary, built once for checks 3 and 4 ---------
# A check that watches only the literal '$NV' is evaded by giving the same file
# another name - CAL=$NV; ... > "$CAL" rewrites exactly the file these checks
# exist to protect.  The variable names are therefore derived from the sources
# scanned instead of being hard-coded, and the path vocabulary is shared by
# both checks: a path one of them watches cannot be missing from the other,
# which is how /efs/ and /persist/ came to be in check 3 and not in check 4.
# The names found are printed in the evidence lines, so a reader can see what
# was actually watched rather than what the label claims.
CAL_DIRS='nvdata|APCFG|APRDEB|/efs/|/persist/|/productinfo/'
# The literal is kept in a single-quoted variable on purpose: inside a
# double-quoted pattern '\$' is the shell's end-of-line anchor, not the regex
# backslash-dollar, and an alias scan written that way silently matches
# nothing - which is how the first version of this block missed CAL=$NV.
_CAL_NV='\$\{?NV\}?'
_cal_names=$(scan_code -oE "[A-Za-z_][A-Za-z0-9_]*=[\"']?($_CAL_NV|/[^\"'[:space:]]*($CAL_DIRS))" \
             | no_tests | sed 's/^.*://; s/=.*//' \
             | grep -E '^[A-Za-z_][A-Za-z0-9_]*$' \
             | grep -vE '^(NV|if|of|in|do|then|else|fi|for|while)$' \
             | LC_ALL=C sort -u | tr '\n' ' ')
_cal_tok=$_CAL_NV
for _v in $_cal_names; do
    _cal_tok="$_cal_tok|\\\$\{?$_v\}?"
done
_cal_tok="$_cal_tok|$CAL_DIRS"
_cal_seen='the canonical name $NV/${NV}'
[ -n "$_cal_names" ] && _cal_seen="$_cal_seen plus the alias(es) $_cal_names"
_cal_blind="what this cannot see: a path produced by a command rather than written out (DIR=\$(dirname \"\$NV\"); > \"\$DIR/WIFI\"), a write performed by a helper command this vocabulary does not name, or a name renamed through two steps.  Documentation (*.md/*.txt/*.json) is not scanned: a sentence that quotes a hazard is not a write"

# --- 3. no shell redirection into a calibration path ------------------------
# HEURISTIC, and labelled as such because that is what the reproduction showed:
# the previous PROOF vocabulary knew '>' with a fixed verb/path list, so
# 'cat "$BAK" | tee "$NV"' and 'CAL=$NV; ... > "$CAL"' were both invisible
# while the line still read "a truncating rewrite cannot be expressed this
# way".  The vocabulary now covers '>' (with '-' before it still excluded: the
# dry-run text legitimately contains "-> $NV in place"), tee and sponge, the
# aliases found above, and the path list check 4 uses.  'dd of=' is the
# sanctioned in-place writer and is deliberately NOT part of this pattern; what
# bounds it is check 5 (every seek= carries a count=).
_redir=$(scan_code -E "(^|[^-])>[[:space:]]*\"?[^[:space:]]*($_cal_tok)|(^|[^[:alnum:]_-])(tee|sponge)[[:space:]]+(-[a-zA-Z]+[[:space:]]+)*\"?[^[:space:]]*($_cal_tok)" | no_tests)
if [ -n "$_redir" ]; then
    report "no shell redirection into a calibration path (C1)" HEURISTIC fail "$(printf '%s' "$_redir" | head -5 | tr '\n' ' ')"
else
    report "no shell redirection into a calibration path (C1)" HEURISTIC pass "no '>' and no tee/sponge in any script or source writes to $_cal_seen or to an nvdata/APCFG/APRDEB//efs//persist//productinfo/ path; 'dd of=' is excluded on purpose (it is the in-place writer, bounded by check 5). $_cal_blind"
fi

# --- 4. no cp/mv/install onto a calibration path ----------------------------
# Same vocabulary as check 3 - including the paths check 3 watches, so the two
# cannot disagree about what a calibration file is - plus rsync, which replaces
# a file's contents just as cp does.
_move=$(scan_code -E "(^|[^[:alnum:]_-])(cp|mv|install|rsync)[[:space:]]+[^|;&]*($_cal_tok)" | no_tests)
if [ -n "$_move" ]; then
    report "no cp/mv/install onto a calibration path (C1, C4)" HEURISTIC fail "$(printf '%s' "$_move" | head -5 | tr '\n' ' ')"
else
    report "no cp/mv/install onto a calibration path (C1, C4)" HEURISTIC pass "no cp/mv/install/rsync line mentions $_cal_seen or an nvdata/APCFG/APRDEB//efs//persist//productinfo/ path (dd in place keeps the inode and the SELinux label). This is a line-level grep: it cannot see the argument order, so a legitimate 'cp \$NV somewhere-else' would be reported too, and $_cal_blind"
fi

# --- 5. every seek= carries a count= ----------------------------------------
# HEURISTIC: it is a line-level grep, restricted to code files.  A write could
# still be unbounded with the count= on a continuation line, or bounded and
# wrong; and prose that merely *discusses* seek= is not a write site, so
# .md/.txt files are filtered out (a check that fires on documentation is noise).
_seek=$(scan 'seek=' | grep -E '\.(sh|java|py|c|h):' | no_tests)
_seek_bad=$(printf '%s\n' "$_seek" | grep -v 'count=')
if [ -z "$_seek" ]; then
    report "every seek= carries a count= (C1, C3, H6)" HEURISTIC info "no seek= anywhere in the tree: nothing writes at an offset at all"
elif [ -n "$_seek_bad" ]; then
    report "every seek= carries a count= (C1, C3, H6)" HEURISTIC fail "unbounded or unstated length on the same line: $(printf '%s' "$_seek_bad" | tr '\n' ' ')"
else
    report "every seek= carries a count= (C1, C3, H6)" HEURISTIC pass "$(printf '%s\n' "$_seek" | wc -l | tr -d ' ') seek= site(s), every one bounded by an explicit count= on the same line: $(printf '%s' "$_seek" | sed "s|$ROOT/||g" | tr '\n' ' ')"
fi

if [ -n "$TEST_FILES" ]; then
    report "test scripts are excluded from checks 3-5 (they build synthetic fixtures on purpose)" PROOF info "excluded: $TEST_FILES (the behavioural guard for those is tools/clitest/clitest.sh)"
fi

# --- 6. no fixed sleep without a state poll on the WiFi-restart path --------
# HEURISTIC: functions are extracted by brace matching, and "a poll" is
# recognised by the presence of a loop plus a state read, not by understanding
# the control flow.
_sleep_fail=
_sleep_pass=
_restart_seen=
if [ -f "$ROOT/cli/macchanger.sh" ]; then
    _fn=$(awk '
        /^[A-Za-z_][A-Za-z0-9_]*\(\)[[:space:]]*\{/ { name = $0; body = ""; inb = 1 }
        inb { body = body "\n" $0 }
        inb && /^\}/ { if (body ~ /svc wifi/) print name body; inb = 0 }
    ' "$ROOT/cli/macchanger.sh")
    if [ -n "$_fn" ]; then
        _restart_seen=1
        if printf '%s' "$_fn" | grep -qE '\bsleep [0-9]+'; then
            if printf '%s' "$_fn" | grep -qE '\b(while|until|for)\b'; then
                _sleep_pass="cli/macchanger.sh: a bounded poll loop is present"
            else
                _sleep_fail="cli/macchanger.sh: $(printf '%s' "$_fn" | grep -nE '\bsleep [0-9]+' | head -3 | tr '\n' ' ')"
            fi
        fi
    fi
fi
if [ -n "$_sleep_fail" ]; then
    report "no fixed sleep without a state poll on the WiFi-restart path (H7)" HEURISTIC fail "blind wait(s) with no state read: $_sleep_fail - a real, unfixed hazard in cli/macchanger.sh (the CLI half of H7: the 'svc wifi enable' exit status is checked, but nothing reads the WiFi state back before assuming 6s is enough; the app half polls via waitWifiEnabled/WIFI_POLL_MS). Reported, not fixed: cli/ belongs to another owner."
elif [ -n "$_sleep_pass" ]; then
    report "no fixed sleep without a state poll on the WiFi-restart path (H7)" HEURISTIC pass "$_sleep_pass"
else
    report "no fixed sleep without a state poll on the WiFi-restart path (H7)" HEURISTIC pass "no shell WiFi-restart function with a fixed sleep outside a poll loop"
fi

# The app side of the same hazard: a fixed Thread.sleep on the restart path.
# Reported as INFO because a Java literal sleep is only distinguishable from a
# named poll interval by reading the surrounding method.
if [ -f "$ROOT/app/src/com/macchanger/MainActivity.java" ]; then
    _jlit=$(grep -nE 'Thread\.sleep\([0-9]' "$ROOT/app/src/com/macchanger/MainActivity.java" | tr '\n' ' ')
    _jpoll=$(grep -cE 'WIFI_POLL_MS|waitWifi|readWifiStatus' "$ROOT/app/src/com/macchanger/MainActivity.java")
    if [ -n "$_jlit" ]; then
        report "the app's restart path waits by polling, not by a literal sleep (H7, app side)" HEURISTIC info "literal Thread.sleep($( printf '%s' "$_jlit" | sed 's/.*Thread.sleep(//;s/).*//')) at $_jlit"
    else
        report "the app's restart path waits by polling, not by a literal sleep (H7, app side)" HEURISTIC pass "no Thread.sleep(<literal>) in MainActivity.java; $_jpoll reference(s) to the poll helpers (WIFI_POLL_MS/waitWifi/readWifiStatus)"
    fi
fi

# =============================================================================
section "permissions"

# --- 7. zero permissions, in the source and in the shipped APK --------------
_manifest="$ROOT/app/AndroidManifest.xml"
if [ -f "$_manifest" ]; then
    _perm=$(grep -c '<uses-permission' "$_manifest" | tr -d ' ')
    _svc=$(grep -c '<service' "$_manifest" | tr -d ' ')
    if [ "$_perm" = 0 ] && [ "$_svc" = 0 ]; then
        report "the manifest requests zero permissions and declares no service (design constraint)" PROOF pass "app/AndroidManifest.xml: $_perm <uses-permission>, $_svc <service>"
    else
        report "the manifest requests zero permissions and declares no service (design constraint)" PROOF fail "app/AndroidManifest.xml: $_perm <uses-permission>, $_svc <service> - the design deliberately requests none and runs no foreground service"
    fi
else
    report "the manifest requests zero permissions and declares no service (design constraint)" PROOF fail "app/AndroidManifest.xml is missing"
fi

_apk="$ROOT/prebuilt/MacChanger.apk"
if [ -f "$_apk" ] && command -v unzip >/dev/null 2>&1; then
    _apkperm=$(unzip -p "$_apk" AndroidManifest.xml 2>/dev/null | grep -ac 'permission' | tr -d ' ')
    if [ "$_apkperm" = 0 ]; then
        report "the shipped APK's own manifest contains no permission string" PROOF pass "unzip -p prebuilt/MacChanger.apk AndroidManifest.xml | grep -ac permission = 0 (the binary string pool would carry it)"
    else
        report "the shipped APK's own manifest contains no permission string" PROOF fail "the shipped APK mentions 'permission' $_apkperm time(s) - permissions may have been added without rebuilding this source"
    fi
else
    report "the shipped APK's own manifest contains no permission string" PROOF info "skipped: no unzip, or no prebuilt/MacChanger.apk"
fi

# =============================================================================
section "build identity (M8)"

# --- 8i. the build stamps a version that changes between builds -------------
# The build half of M8.  The old script packaged with whatever versionCode and
# versionName the manifest happened to carry, so every APK claimed the same
# frozen identity; aapt is now told both, per build, and the build refuses to
# ship an APK whose packaged manifest does not carry them back.  The evidence
# is taken from the gate lines themselves: an earlier version of this check
# asserted "gates on both appearing in aapt dump badging" in its PASS text
# while grepping only for the two flags, so deleting both gates left the
# sentence standing.  Now a missing gate is a FAIL.
_stamp_vc=$(grep -c -- '--version-code' "$ROOT/app/build.sh" 2>/dev/null)
_stamp_vn=$(grep -c -- '--version-name' "$ROOT/app/build.sh" 2>/dev/null)
_vc_gate=$(grep -cF "versionCode='\$VC'" "$ROOT/app/build.sh" 2>/dev/null)
_vn_gate=$(grep -cF "versionName='\$VN'" "$ROOT/app/build.sh" 2>/dev/null)
if [ "$_stamp_vc" -gt 0 ] && [ "$_stamp_vn" -gt 0 ] && [ "$_vc_gate" -gt 0 ] && [ "$_vn_gate" -gt 0 ]; then
    report "the build stamps a per-build versionCode and versionName (M8)" PROOF pass \
        "app/build.sh hands both to aapt and reads them back out of the packaged manifest: $(grep -nF -e "--version-code" -e "versionCode='\$VC'" "$ROOT/app/build.sh" | sed 's|^|app/build.sh:|' | tr '\n' ' ')$(grep -nF "versionName='\$VN'" "$ROOT/app/build.sh" | sed 's|^| app/build.sh:|' | tr '\n' ' ')"
elif [ "$_stamp_vc" -eq 0 ] || [ "$_stamp_vn" -eq 0 ]; then
    report "the build stamps a per-build versionCode and versionName (M8)" PROOF fail \
        "app/build.sh passes --version-code $_stamp_vc time(s) and --version-name $_stamp_vn time(s): the APK would carry the manifest's frozen identity again"
else
    report "the build stamps a per-build versionCode and versionName (M8)" PROOF fail \
        "app/build.sh passes --version-code $_stamp_vc time(s) and --version-name $_stamp_vn time(s) to aapt, but never compares them against 'aapt dump badging' (versionCode gate: $_vc_gate, versionName gate: $_vn_gate): an aapt that ignored the flags would be packaged silently"
fi

# --- 8j. the DEVICE card reports the app's own version (M8, app half) -------
# M8's acceptance asks for a version string ON SCREEN that changes between
# builds.  The card shows model/soc/android and nothing else, and the only
# version anywhere in the app is Build.VERSION.RELEASE (the platform's, not this
# app's).  Reported as INFO rather than FAIL: the file is
# app/src/com/macchanger/MainActivity.java, which this harness deliberately does
# not own, and editing it from here would be exactly the silent cross-scope edit
# the audit complains about.  It becomes a PASS the moment the card reads the
# package's own version.
_selfver=$(grep -cE 'getPackageInfo|getPackageName\(\)' "$ROOT/app/src/com/macchanger/MainActivity.java" 2>/dev/null)
_selfname=$(grep -c 'versionName' "$ROOT/app/src/com/macchanger/MainActivity.java" 2>/dev/null)
if [ "$_selfver" -gt 0 ] && [ "$_selfname" -gt 0 ]; then
    report "the DEVICE card shows the app's own version (M8, app half)" PROOF pass \
        "MainActivity.java reads getPackageManager().getPackageInfo(getPackageName(), ...) and renders versionName ($_selfver reference(s), $_selfname mention(s) of versionName)"
else
    report "the DEVICE card shows the app's own version (M8, app half)" PROOF info \
        "UNMET: the DEVICE card has model/soc/android only and MainActivity.java never calls getPackageManager().getPackageInfo(...)/versionName ($_selfver reference(s)), so the version on screen is Build.VERSION.RELEASE - the platform's. Reported, not fixed: MainActivity.java is outside tools/. The fix belongs to whoever owns that file: the package's versionName plus lastUpdateTime from getPackageInfo(getPackageName(), 0), API 21-safe, no permission and no resource."
fi

# =============================================================================
section "docs versus code"

# --- 8a. no positional parsing of the WiFi status ---------------------------
# Scoped to the two front ends: a 'head -N' in a build or test helper is not
# this hazard, and matching it would make the check cry wolf.  PROOF for the two
# files, and only for the shapes actually used ('head -1', 'head -n 1', '1,3p').
_head=$(grep -rnE '(\|[[:space:]]*head[[:space:]]+(-n[[:space:]]*)?[0-9]|^[[:space:]]*head[[:space:]]+(-n[[:space:]]*)?[0-9]|1,3p)' \
        "$ROOT/app" "$ROOT/cli" 2>/dev/null)
if [ -n "$_head" ]; then
    report "no line-position parsing of the WiFi status in app/ or cli/ (H5)" PROOF fail "$(printf '%s' "$_head" | head -5 | tr '\n' ' ')"
else
    report "no line-position parsing of the WiFi status in app/ or cli/ (H5)" PROOF pass "no '| head -N' pipeline, no bare 'head -N' and no '1,3p' slice in app/ or cli/: the status is parsed by content"
fi

# --- 8b. the CLI's scope claim matches the code -----------------------------
_doc_scope=$(grep -cE 'MediaTek only|MediaTek-only' "$ROOT/README.md" 2>/dev/null)
_cli_refuse=$(grep -c '\[ -f "\$NV" \] || die' "$ROOT/cli/macchanger.sh" 2>/dev/null)
_nv_assigns=$(grep -hE '^NV[A-Z_]*=' "$ROOT/cli/macchanger.sh" 2>/dev/null | sed 's/[[:space:]]*#.*//; s/^[A-Z_]*=//' | grep -v '^$' | LC_ALL=C sort -u)
_nv_other=
for _p in $_nv_assigns; do
    case "$_p" in
        /mnt/vendor/nvdata/APCFG/APRDEB/WIFI|/data/nvram/APCFG/APRDEB/WIFI) ;;
        *) _nv_other="$_nv_other $_p" ;;
    esac
done
if [ "$_doc_scope" -gt 0 ] && [ "$_cli_refuse" -gt 0 ] && [ -z "$_nv_other" ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF pass "README says 'MediaTek only' $_doc_scope time(s); cli/macchanger.sh refuses a missing \$NV in $_cli_refuse place(s); every writable NV path it names is an MTK calibration path"
elif [ "$_doc_scope" -eq 0 ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "the README never says the CLI is MediaTek only, but the CLI only knows MTK paths"
elif [ "$_cli_refuse" -eq 0 ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "cli/macchanger.sh no longer refuses a missing \$NV, so the documented refusal is not in the code"
else
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "the CLI also names non-MTK paths as writable:$_nv_other"
fi

# --- 8c. documented sidecar names are the ones the CLI writes ---------------
_side_bad=
_side_ok=
for _s in offset sha256 path; do
    if grep -q "WIFI\.factory\.$_s" "$ROOT/README.md" 2>/dev/null; then
        if grep -q "\$BAK\.$_s" "$ROOT/cli/macchanger.sh" 2>/dev/null; then
            _side_ok="$_side_ok WIFI.factory.$_s"
        else
            _side_bad="$_side_bad WIFI.factory.$_s"
        fi
    fi
done
if [ -n "$_side_bad" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF fail "documented but never written by cli/macchanger.sh:$_side_bad"
elif [ -n "$_side_ok" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF pass "each documented name has a matching \$BAK suffix in the CLI:$_side_ok"
else
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF info "the README no longer names any WIFI.factory.* sidecar"
fi

# --- 8d. the documented targetSdk is the manifest's ------------------------
_m_sdk=$(sed -n 's/.*android:targetSdkVersion="\([0-9][0-9]*\)".*/\1/p' "$_manifest" 2>/dev/null | head -n 1)
_r_sdks=$(grep -o 'targetSdkVersion [0-9][0-9]*' "$ROOT/README.md" 2>/dev/null | awk '{ print $2 }' | LC_ALL=C sort -u | tr '\n' ' ')
if [ -z "$_m_sdk" ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "app/AndroidManifest.xml has no android:targetSdkVersion"
elif [ "$_r_sdks" = "$_m_sdk " ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF pass "manifest $_m_sdk; every targetSdkVersion the README states is $_m_sdk"
else
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "manifest $_m_sdk, README states '$_r_sdks'"
fi

# --- 8d2. every tool under tools/ is named in the README --------------------
# A tool nobody can find is a tool nobody runs.  Files and directories directly
# under tools/ (other than README.md) are the entry points.
_missing_tools=
_listed_tools=
for _t in "$ROOT"/tools/*; do
    [ -e "$_t" ] || continue
    _tn=$(basename -- "$_t")
    [ "$_tn" = "README.md" ] && continue
    if grep -q "tools/$_tn" "$ROOT/README.md" 2>/dev/null; then
        _listed_tools="$_listed_tools tools/$_tn"
    else
        _missing_tools="$_missing_tools tools/$_tn"
    fi
done
if [ -n "$_missing_tools" ]; then
    report "every tool under tools/ is named in the README (discoverability)" PROOF fail "the README never mentions:$_missing_tools"
elif [ -n "$_listed_tools" ]; then
    report "every tool under tools/ is named in the README (discoverability)" PROOF pass "README mentions:$_listed_tools"
else
    report "every tool under tools/ is named in the README (discoverability)" PROOF info "tools/ has no entry points to document"
fi

# --- 8e. the zero-permission claim in the docs matches the manifest ---------
_r_permclaim=$(grep -c 'zero <uses-permission>' "$ROOT/README.md" 2>/dev/null)
_m_perm=$(grep -c '<uses-permission' "$_manifest" 2>/dev/null | tr -d ' ')
if [ "$_r_permclaim" -gt 0 ] && [ "$_m_perm" = 0 ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF pass "README claims 'zero <uses-permission> elements' and app/AndroidManifest.xml has $_m_perm"
elif [ "$_r_permclaim" -gt 0 ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF fail "the README claims zero <uses-permission> elements but the manifest has $_m_perm"
else
    report "the README's zero-permission claim matches the manifest (design)" PROOF info "the README does not make the zero-permission claim any more"
fi

# --- 8f. the documented prebuilt APK hash matches the shipped APK ----------
if [ -f "$_apk" ]; then
    _real_sha=$(sha256sum <"$_apk" | awk '{ print $1 }')
    if grep -q "$_real_sha" "$ROOT/CHANGELOG.md" 2>/dev/null && grep -q "$_real_sha" "$ROOT/README.md" 2>/dev/null; then
        report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF pass "$_real_sha appears in both README.md and CHANGELOG.md; a user can verify what they received"
    else
        report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF fail "prebuilt/MacChanger.apk hashes to $_real_sha; README=$(grep -c "$_real_sha" "$ROOT/README.md" 2>/dev/null) CHANGELOG=$(grep -c "$_real_sha" "$ROOT/CHANGELOG.md" 2>/dev/null) match(es)"
    fi
else
    report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF info "skipped: no prebuilt/MacChanger.apk"
fi

# --- 8g. the two claims that only a human can judge, checked for presence ---
# HEURISTIC: this proves the sentence is still there, not that it is true.
if [ -f "$ROOT/README.md" ]; then
    if grep -q 'non-persistent' "$ROOT/README.md" && grep -q 'does not survive a reboot' "$ROOT/README.md" \
       && grep -q 'ip link' "$ROOT/README.md"; then
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC pass "'ip link' + 'non-persistent' + 'does not survive a reboot' are all still present in README.md. What is NOT checked here: whether the code ever lets that fallback produce a success verdict - only that the sentence survives"
    else
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC fail "one of 'ip link', 'non-persistent', 'does not survive a reboot' is gone from README.md"
    fi
    if grep -q 'Android 12' "$ROOT/README.md" && grep -qi 'randomiz' "$ROOT/README.md"; then
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC pass "the randomization section still names Android 12"
    else
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC fail "the README no longer names Android 12 in its randomization discussion"
    fi
else
    report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC fail "README.md is missing"
fi

# =============================================================================
printf '\nchecks: %s passed, %s informational, %s failed (%s result line(s))\n' \
    "$PASSES" "$INFOS" "$FAILS" "$LINES"
if [ "$FAILS" -eq 0 ]; then
    printf 'CHECKS: PASS (%s passed, %s informational, %s failed)\n' "$PASSES" "$INFOS" "$FAILS"
    exit 0
fi
printf 'CHECKS: FAIL (%s of %s result line(s) failed)\n' "$FAILS" "$LINES"
exit 1
