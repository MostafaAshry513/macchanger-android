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
# Checks 8b, 8c, 8d, 8e, 8f and 8g are the doc-versus-code checks, and they read
# a FILE LIST rather than one hard-coded path: README.md plus every docs/*.md.
# README.md was restructured into a short front page with the depth in docs/, and
# a check still reading README.md alone stops verifying its claim the moment that
# claim moves into docs/ - which is exactly what happened to the WIFI.factory.*
# sidecar names, whose 'offset' half left the README and took check 8c's third
# name with it.  Two branches that used to report INFO when the claim was absent
# (8c's "no sidecar named anywhere", 8e's "no zero-permission claim") are FAILs
# now: an INFO line asserts nothing and still exits 0, so a claim that moved out
# of the README went quiet instead of red.  A docs/ file that is missing counts
# as the claim being missing - the pre-fix revision has no docs/ directory at all
# and must still fail here, not skip.  README.md stays in every one of those
# lists, so a load-bearing sentence that leaves the front page still fails
# loudly; check 8d2 (the tool names) is deliberately README-only, because a tool
# nobody can find from the front page is a tool nobody runs.
#
# 8c asserts the full three-name set, not just the names that happen to survive:
# deleting a sidecar name from every document used to leave the loop with nothing
# to test for it and the check still printed PASS, which is the same silent
# coverage loss in a new shape.  A name the CLI writes that no document states is
# a FAIL that names it.
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

# =============================================================================
# The documentation set the doc-versus-code checks (8b-8g) read.
#
# README.md is the front page a beginner reads top to bottom, and it must keep
# saying the load-bearing things itself; docs/*.md is where the restructure moved
# the depth.  A claim is allowed to live in either, so the checks read the whole
# set.  A file that is not there contributes nothing, and the checks that depend
# on a particular docs/ file say so and FAIL when it is gone: a missing file is a
# missing claim, never a skip.  README.md itself is not optional either.
DOC_SET="$ROOT/README.md"
DOC_SET_DOCS=
for _ds_f in "$ROOT"/docs/*.md; do
    [ -f "$_ds_f" ] || continue
    DOC_SET="$DOC_SET $_ds_f"
    DOC_SET_DOCS="$DOC_SET_DOCS $_ds_f"
done

# doc_count ERE [FILE...] - matching lines, default the whole documentation set.
# Files that do not exist count as zero matches.
doc_count() {
    _doc_pat=$1; shift
    [ $# -gt 0 ] || set -- $DOC_SET
    _doc_n=0
    for _doc_f in "$@"; do
        [ -f "$_doc_f" ] || continue
        _doc_c=$(grep -cE "$_doc_pat" "$_doc_f" 2>/dev/null)
        _doc_n=$((_doc_n + ${_doc_c:-0}))
    done
    printf '%s\n' "$_doc_n"
}

# doc_homes ERE - the documentation files that state it, repo-relative and
# space-separated, so an evidence line says where a claim was actually found
# instead of asking the reader to trust a label.
doc_homes() {
    for _dh_f in $DOC_SET; do
        if grep -qE "$1" "$_dh_f" 2>/dev/null; then printf '%s ' "${_dh_f#"$ROOT/"}"; fi
    done
}

# docs_file NAME - the path of a docs/ file this script names, empty when gone.
docs_file() { [ -f "$ROOT/docs/$1" ] && printf '%s\n' "$ROOT/docs/$1"; }

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
# The scope claim now lives in two places: README.md states it on the front page
# (twice: in the vendor sentence and in the checklist), and the CLI's own
# documentation, docs/CLI.md, states it in its opening line and again under
# "MediaTek only".  Both halves are verified; docs/DEVICES.md carries the path
# table for the same claim but is not required to repeat the phrase.
_doc_scope=$(doc_count 'MediaTek only|MediaTek-only' "$ROOT/README.md")
_docs_scope=$(doc_count 'MediaTek only|MediaTek-only' "$ROOT/docs/CLI.md")
_cli_refuse=$(grep -c '\[ -f "\$NV" \] || die' "$ROOT/cli/macchanger.sh" 2>/dev/null)
_nv_assigns=$(grep -hE '^NV[A-Z_]*=' "$ROOT/cli/macchanger.sh" 2>/dev/null | sed 's/[[:space:]]*#.*//; s/^[A-Z_]*=//' | grep -v '^$' | LC_ALL=C sort -u)
_nv_other=
for _p in $_nv_assigns; do
    case "$_p" in
        /mnt/vendor/nvdata/APCFG/APRDEB/WIFI|/data/nvram/APCFG/APRDEB/WIFI) ;;
        *) _nv_other="$_nv_other $_p" ;;
    esac
done
if [ "$_doc_scope" -eq 0 ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "README.md no longer says the CLI is MediaTek only, while the CLI knows MTK paths only (this branch greps the front page alone; docs/CLI.md is checked separately below, and a claim that moved must be re-pointed, not dropped)"
elif [ ! -f "$ROOT/docs/CLI.md" ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "docs/CLI.md is missing, and that file is where the CLI's own documentation states its MediaTek-only scope in full: a file that should carry the claim and is not there counts as the claim being absent, not as a skip"
elif [ "$_docs_scope" -eq 0 ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "docs/CLI.md no longer says the CLI is MediaTek only, while cli/macchanger.sh still knows MTK paths only (the README line alone is a pointer now, not the statement)"
elif [ "$_cli_refuse" -eq 0 ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "cli/macchanger.sh no longer refuses a missing \$NV, so the documented refusal is not in the code"
elif [ -n "$_nv_other" ]; then
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF fail "the CLI also names non-MTK paths as writable:$_nv_other"
else
    report "the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)" PROOF pass "README.md says 'MediaTek only' $_doc_scope time(s) and docs/CLI.md $_docs_scope time(s); cli/macchanger.sh refuses a missing \$NV in $_cli_refuse place(s); every writable NV path it names is an MTK calibration path"
fi

# --- 8c. documented sidecar names are the ones the CLI writes ---------------
# This is the check the restructure actually broke.  README.md's front page names
# WIFI.factory.path and WIFI.factory.sha256, and WIFI.factory.offset moved into
# docs/CLI.md ("where the MAC field sits in the image") and docs/SAFETY.md (the
# record table).  Reading README.md alone therefore stopped verifying 'offset'
# without saying so: three documented-and-written names silently became two, and
# the check still printed PASS.  It reads the whole documentation set now, so a
# name documented anywhere in the docs is verified, and it FAILs when nothing
# documents them at all instead of going quiet on an INFO line - the sidecar
# names are what a user has to copy off the phone before a wipe.
#
# All THREE names are required, not merely verified when present.  The loop used
# to `continue` on a name no document mentions, so deleting WIFI.factory.offset
# from every document (or offset and sha256 together, leaving only path) still
# exited 0: the check asserted "every documented name is one the CLI writes" while
# its name and its PASS line claimed the three-name set.  A name the CLI writes
# and no document states is now a FAIL that names it, which is the assertion the
# restructure claimed to have restored.
_side_bad=
_side_ok=
_side_missing=
_side_written=
for _s in offset sha256 path; do
    _s_homes=$(doc_homes "WIFI\.factory\.$_s")
    if grep -q "\$BAK\.$_s" "$ROOT/cli/macchanger.sh" 2>/dev/null; then
        _side_written="$_side_written WIFI.factory.$_s"
    fi
    if [ -z "$_s_homes" ]; then
        _side_missing="$_side_missing WIFI.factory.$_s"
        continue
    fi
    if grep -q "\$BAK\.$_s" "$ROOT/cli/macchanger.sh" 2>/dev/null; then
        _side_ok="$_side_ok WIFI.factory.$_s [$(printf '%s' "$_s_homes" | sed 's/ $//')]"
    else
        _side_bad="$_side_bad WIFI.factory.$_s [$(printf '%s' "$_s_homes" | sed 's/ $//')]"
    fi
done
if [ -n "$_side_bad" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF fail "documented but never written by cli/macchanger.sh:$_side_bad"
elif [ -n "$_side_missing" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF fail "cli/macchanger.sh writes$_side_written beside every captured image, but no file in the documentation set names:$_side_missing (read from README.md and every docs/*.md). All three names are required: the image and its sidecars are copied off the phone as a set, so a name no document states is a file the user does not know to keep"
elif [ -n "$_side_ok" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF pass "all three names the CLI writes ($_side_written ) are documented and each has a matching \$BAK suffix in the CLI:$_side_ok (read from README.md and every docs/*.md, so a name that moved into docs/ is still verified)"
# The two branches below are the pre-8c-fix ones.  They are unreachable now, because
# a documentation set that names none of the three names leaves _side_missing full
# and FAILs above; they are kept as a fallback for an edit that changes the loop's
# shape, and they are FAILs, never INFO.
elif [ -z "$DOC_SET_DOCS" ]; then
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF fail "no documentation file names any WIFI.factory.* sidecar and there is no docs/ directory: the CLI writes \$BAK.offset, \$BAK.path and \$BAK.sha256 beside every captured image, and neither the front page nor the docs say which files those are. A missing file counts as the claim being absent, not as a skip"
else
    report "the sidecar files the README documents are the ones the CLI writes (L1)" PROOF fail "no file in the documentation set (README.md, $(printf '%s' "$DOC_SET_DOCS" | sed "s|$ROOT/||g; s|^ ||")) names any WIFI.factory.* sidecar, while cli/macchanger.sh writes three of them (\$BAK.offset, \$BAK.path, \$BAK.sha256)"
fi

# --- 8d. the documented targetSdk is the manifest's ------------------------
# Both halves are still checked, and both are read from the whole documentation
# set: README.md keeps its one-line mention (and the pointer), docs/APP.md is
# where "why targetSdkVersion 30 is deliberate" moved, and docs/DEVICES.md states
# it again in the vendor table.  Every value stated anywhere in the docs must be
# the manifest's value, so a doc that drifts to another number FAILs instead of
# going unread.
_m_sdk=$(sed -n 's/.*android:targetSdkVersion="\([0-9][0-9]*\)".*/\1/p' "$_manifest" 2>/dev/null | head -n 1)
_rm_sdks=$(grep -oE 'targetSdkVersion [0-9][0-9]*' "$ROOT/README.md" 2>/dev/null | awk '{ print $2 }' | LC_ALL=C sort -u | tr '\n' ' ')
_doc_sdks=$(grep -hoE 'targetSdkVersion [0-9][0-9]*' $DOC_SET 2>/dev/null | awk '{ print $2 }' | LC_ALL=C sort -u | tr '\n' ' ')
if [ -z "$_m_sdk" ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "app/AndroidManifest.xml has no android:targetSdkVersion"
elif [ -z "$_rm_sdks" ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "the README no longer states any targetSdkVersion (the manifest sets $_m_sdk), and this check reads README.md and every docs/*.md, so the statement is gone from all of them - re-point the check if the claim moved, do not drop the half that greps the README"
elif [ ! -f "$ROOT/docs/APP.md" ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "docs/APP.md is missing: it is where the reason targetSdkVersion $_m_sdk is deliberate now lives, and where README.md sends the reader. A file that should carry the claim and is not there counts as the claim being absent, not as a skip"
elif [ "$_doc_sdks" = "$_m_sdk " ]; then
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF pass "manifest $_m_sdk; every targetSdkVersion stated in $(doc_homes 'targetSdkVersion [0-9]')is $_m_sdk"
else
    report "the targetSdk the README states is the one the manifest sets (M5)" PROOF fail "manifest $_m_sdk, the documentation states '$_doc_sdks' in $(doc_homes 'targetSdkVersion [0-9]')"
fi

# --- 8d2. every tool under tools/ is named in the README --------------------
# A tool nobody can find is a tool nobody runs.  Files and directories directly
# under tools/ (other than README.md) are the entry points.  This check stays
# scoped to README.md on purpose, alone among the doc-versus-code checks: the
# front page is the one a beginner reads, README.md links every gate from its
# "Where to read more" table, and a tool that is only named in docs/DEVELOPING.md
# is a tool the reader who never opens docs/ never finds.  If a restructure moves
# that table, this check should go red - not be widened to the docs set.
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
# README.md still carries the claim; docs/DEVELOPING.md repeats it in the
# repository layout, and no doc may contradict it.  The old "the README does not
# make the claim any more" branch was an INFO, which asserts nothing and still
# exits 0, so a claim that drifted out of the front page went quiet: it is a FAIL
# now, and a missing docs/ directory is a FAIL too, on the same rule as 8c.
_r_permclaim=$(doc_count 'zero <uses-permission>' "$ROOT/README.md")
_m_perm=$(grep -c '<uses-permission' "$_manifest" 2>/dev/null | tr -d ' ')
_doc_permfiles=$(doc_homes '<uses-permission')
_doc_perm_bad=
for _pf in $_doc_permfiles; do
    grep -q 'zero <uses-permission>' "$ROOT/$_pf" 2>/dev/null || _doc_perm_bad="$_doc_perm_bad $_pf"
done
if [ "$_r_permclaim" -eq 0 ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF fail "the README does not make the zero-permission claim any more (no 'zero <uses-permission>' in README.md). The claim is a design constraint of this project and the manifest still has $_m_perm <uses-permission> element(s), so the front page has to state it"
elif [ -z "$DOC_SET_DOCS" ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF fail "there is no docs/ directory: the README links its depth there, and a documentation file that should carry a claim and is not there counts as the claim being absent, not as a skip"
elif [ "$_m_perm" != 0 ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF fail "the documentation claims zero <uses-permission> elements but app/AndroidManifest.xml has $_m_perm"
elif [ -n "$_doc_perm_bad" ]; then
    report "the README's zero-permission claim matches the manifest (design)" PROOF fail "these documentation files mention <uses-permission but do not state that the count is zero:$_doc_perm_bad"
else
    report "the README's zero-permission claim matches the manifest (design)" PROOF pass "README.md claims 'zero <uses-permission> elements', each documentation file that mentions <uses-permission also states that the count is zero ($_doc_permfiles), and app/AndroidManifest.xml has $_m_perm. What this branch does NOT prove: that no file also *contradicts* the claim elsewhere - it tests for the presence of the zero-count sentence in every file that mentions the element, not the absence of a contrary sentence, so 'agrees' would be a stronger word than this check earns"
fi

# --- 8f. the documented prebuilt APK hash matches the shipped APK ----------
# README.md and CHANGELOG.md are still required to carry the real digest
# (unchanged).  What is new is the docs half of the restructure: every file that
# tells the reader to run 'sha256sum prebuilt/MacChanger.apk' must print the value
# they should see, so the copy in docs/APP.md cannot drift away from the APK and
# leave a reader rejecting a good file or accepting a bad one.
if [ -f "$_apk" ]; then
    _real_sha=$(sha256sum <"$_apk" | awk '{ print $1 }')
    _sha_teach=$(doc_homes 'sha256sum prebuilt/MacChanger\.apk')
    _sha_missing=
    grep -q "$_real_sha" "$ROOT/README.md" 2>/dev/null || _sha_missing="$_sha_missing README.md"
    grep -q "$_real_sha" "$ROOT/CHANGELOG.md" 2>/dev/null || _sha_missing="$_sha_missing CHANGELOG.md"
    for _sha_f in $_sha_teach; do
        grep -q "$_real_sha" "$ROOT/$_sha_f" 2>/dev/null || _sha_missing="$_sha_missing $_sha_f"
    done
    if [ -z "$_sha_missing" ]; then
        report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF pass "$_real_sha appears in README.md, in CHANGELOG.md, and in every file that tells the reader to hash the APK ($(printf '%s' "$_sha_teach" | sed 's/ $//')); a user can verify what they received"
    else
        report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF fail "prebuilt/MacChanger.apk hashes to $_real_sha; it is missing or different in:$_sha_missing (README.md and CHANGELOG.md are the record; a file that tells the reader to run sha256sum must carry the same value)"
    fi
else
    report "the prebuilt APK's SHA-256 is the one the docs record (M8, C5)" PROOF info "skipped: no prebuilt/MacChanger.apk"
fi

# --- 8g. the two claims that only a human can judge, checked for presence ---
# HEURISTIC: this proves the sentence is still there, not that it is true.
# README.md must still carry both sentences - they are the two a reader meets
# before doing something irreversible - and docs/HOW-IT-WORKS.md, which the
# README points at for the long version, must carry them too.  A missing
# docs/HOW-IT-WORKS.md is a FAIL: it is where the full statement now lives.
if [ -f "$ROOT/README.md" ]; then
    _how=$(docs_file HOW-IT-WORKS.md)
    _rm_ip=$(doc_count 'ip link' "$ROOT/README.md")
    _rm_np=$(doc_count 'non-persistent' "$ROOT/README.md")
    _rm_nr=$(doc_count 'does not survive a reboot' "$ROOT/README.md")
    if [ "$_rm_ip" -eq 0 ] || [ "$_rm_np" -eq 0 ] || [ "$_rm_nr" -eq 0 ]; then
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC fail "one of 'ip link', 'non-persistent', 'does not survive a reboot' is gone from README.md; this branch greps the front page alone (docs/HOW-IT-WORKS.md is checked separately below), so re-point it if the claim moved, do not drop it"
    elif [ -z "$_how" ]; then
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC fail "docs/HOW-IT-WORKS.md is missing: that is the file README.md sends the reader to for the full statement of this fallback, and a file that should carry it and is not there counts as the claim being absent, not as a skip"
    elif [ "$(doc_count 'ip link' "$_how")" -eq 0 ] || [ "$(doc_count 'non-persistent' "$_how")" -eq 0 ] || [ "$(doc_count 'does not survive a reboot' "$_how")" -eq 0 ]; then
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC fail "docs/HOW-IT-WORKS.md no longer carries one of 'ip link', 'non-persistent', 'does not survive a reboot'"
    else
        report "the README still labels the ip-link fallback non-persistent (H8)" HEURISTIC pass "'ip link' + 'non-persistent' + 'does not survive a reboot' are all still present in README.md and in docs/HOW-IT-WORKS.md. What is NOT checked here: whether the code ever lets that fallback produce a success verdict - only that the sentence survives"
    fi
    _rm_a12=$(doc_count 'Android 12' "$ROOT/README.md")
    _rm_rnd=$(doc_count 'randomiz' "$ROOT/README.md")
    if [ "$_rm_a12" -eq 0 ] || [ "$_rm_rnd" -eq 0 ]; then
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC fail "README.md no longer names Android 12 in its randomization discussion; this branch greps the front page alone (docs/HOW-IT-WORKS.md is checked separately below)"
    elif [ -z "$_how" ]; then
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC fail "docs/HOW-IT-WORKS.md is missing, and it is where the Android 12+ limitation is now written out in full"
    elif [ "$(doc_count 'Android 12' "$_how")" -eq 0 ] || [ "$(doc_count 'randomiz' "$_how")" -eq 0 ]; then
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC fail "docs/HOW-IT-WORKS.md no longer names Android 12 in its randomization discussion"
    else
        report "the README still states the Android 12+ randomization-detection limit (H5)" HEURISTIC pass "the randomization section still names Android 12 in README.md and in docs/HOW-IT-WORKS.md"
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
