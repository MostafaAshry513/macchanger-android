#!/bin/sh
#
# stubcompile/check.sh - offline syntax and type gate for the Android UI source.
#
# There is no Android SDK on this machine, so nothing else here can prove that
# app/src/com/macchanger/MainActivity.java even compiles. This script compiles the
# hand-written android.* stubs under stubs/ into a temp directory, then compiles the
# target source against them with the Java 8 source/target levels the project requires.
#
#   sh tools/stubcompile/check.sh [path/to/MainActivity.java]
#
# With no argument it checks ../../app/src/com/macchanger/MainActivity.java relative to
# this script. Every javac diagnostic is printed verbatim, the last line is exactly
# "STUBCOMPILE: PASS" or "STUBCOMPILE: FAIL", and the exit status is 0 only on PASS.
#
# Requires: a JDK (javac that still accepts -source 8, i.e. 8..21), POSIX sh, find, sort,
# mktemp, grep. No network, no Android SDK, no Gradle.

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
STUBS_DIR="$SCRIPT_DIR/stubs"
DEFAULT_TARGET="$SCRIPT_DIR/../../app/src/com/macchanger/MainActivity.java"
TARGET=${1:-$DEFAULT_TARGET}
JAVAC=${JAVAC:-javac}

# fail() always leaves the verdict line last on stdout and exits non-zero.
fail() {
    echo "stubcompile: $1" >&2
    echo "STUBCOMPILE: FAIL"
    exit 1
}

if ! command -v "$JAVAC" >/dev/null 2>&1; then
    fail "javac not found (tried '$JAVAC'); put a JDK on PATH or set JAVAC=/path/to/javac"
fi
JAVAC_PATH=$(command -v "$JAVAC")

[ -d "$STUBS_DIR" ] || fail "stub source tree is missing: $STUBS_DIR"
[ -f "$TARGET" ] || fail "no such Java source to check: $TARGET"

STUB_SOURCES=$(find "$STUBS_DIR" -name '*.java' | sort)
[ -n "$STUB_SOURCES" ] || fail "no stub sources under $STUBS_DIR (refusing to report a vacuous PASS)"
STUB_COUNT=$(printf '%s\n' "$STUB_SOURCES" | wc -l | tr -d ' ')

TMP_ROOT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "$TMP_ROOT/stubcompile.XXXXXX") || fail "mktemp -d failed under $TMP_ROOT"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

STUB_CLASSES="$TMP/stub-classes"
MAIN_CLASSES="$TMP/main-classes"
mkdir -p "$STUB_CLASSES" "$MAIN_CLASSES"

echo "stubcompile: javac   $JAVAC_PATH"
echo "stubcompile: stubs   $STUBS_DIR ($STUB_COUNT source files)"
echo "stubcompile: target  $TARGET"

# shellcheck disable=SC2086  # newline-separated, space-free paths
"$JAVAC" -source 8 -target 8 -nowarn -d "$STUB_CLASSES" $STUB_SOURCES > "$TMP/stubs.log" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
    echo "stubcompile: compiling the android.* stubs failed; the gate itself is broken and no verdict about the target is trustworthy"
    cat "$TMP/stubs.log"
    if grep -q "invalid source release\|release version 8 not supported" "$TMP/stubs.log"; then
        echo "stubcompile: this javac no longer accepts -source 8; use a JDK that does (8..21)" >&2
    fi
    fail "stub compilation failed (javac exit $rc)"
fi
if [ ! -f "$STUB_CLASSES/android/app/Activity.class" ]; then
    fail "stub compilation produced no android/app/Activity.class; the stub tree is not usable"
fi

# The real check: exactly the invocation the project's build would use for this file.
# shellcheck disable=SC2086
"$JAVAC" -source 8 -target 8 -nowarn -d "$MAIN_CLASSES" -classpath "$STUB_CLASSES" "$TARGET" \
    > "$TMP/main.log" 2>&1
rc=$?

if [ -s "$TMP/main.log" ]; then
    cat "$TMP/main.log"
fi

if [ "$rc" -ne 0 ]; then
    fail "javac rejected $TARGET (exit $rc)"
fi

# javac exiting 0 is not by itself proof that anything was type-checked.
MAIN_CLASS=$(find "$MAIN_CLASSES" -name 'MainActivity.class' | head -n 1)
if [ -z "$MAIN_CLASS" ]; then
    OTHER=$(find "$MAIN_CLASSES" -name '*.class' | head -n 1)
    if [ -n "$OTHER" ]; then
        fail "javac emitted ${OTHER#$MAIN_CLASSES/} but no MainActivity.class: this gate checks a source whose public class is MainActivity (point it at that file)"
    fi
    fail "javac reported success but emitted no class file at all, so nothing was verified"
fi

CLASS_COUNT=$(find "$MAIN_CLASSES" -name '*.class' | wc -l | tr -d ' ')
echo "stubcompile: ok      $CLASS_COUNT class file(s) emitted, including ${MAIN_CLASS#$MAIN_CLASSES/}"
echo "STUBCOMPILE: PASS"
exit 0
