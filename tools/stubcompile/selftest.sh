#!/bin/sh
#
# stubcompile/selftest.sh - proves that check.sh can actually fail.
#
# A gate that always prints PASS is worse than no gate: it launders an unverified file
# into an "ok". This script therefore feeds check.sh deliberately broken sources and
# fails loudly if the gate still reports PASS.
#
#   sh tools/stubcompile/selftest.sh
#
# Controls run, in order:
#   1. the working-tree MainActivity.java            -> must PASS (exit 0)
#   2. the .pristine baseline MainActivity.java      -> must PASS (exit 0)
#   3. an unmodified *copy* in a temp dir            -> must PASS (so a later failure
#                                                       cannot be blamed on the copy)
#   4. the copy + a stray '}'   (syntax error)       -> must FAIL, mentioning error:
#   5. the copy + an undefined symbol                -> must FAIL, naming that symbol
#   6. the copy + a method no stub declares          -> must FAIL, naming that method
#                                                       (proves members are resolved
#                                                        against the stub API, not just
#                                                        counted for braces)
#   7. the copy + a stub method called with the
#      wrong argument type                           -> must FAIL, naming that method
#                                                       (proves signatures are checked,
#                                                        not only names)
#   8. the copy + a removed statement terminator     -> must FAIL, mentioning error:
#   9. a path that does not exist                    -> must FAIL with a clear message
#
# The last line is exactly "SELFTEST: PASS" or "SELFTEST: FAIL"; exit status is 0 only
# on PASS. Requires a JDK and POSIX sh.

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CHECK="$SCRIPT_DIR/check.sh"
WORKING_TARGET="$SCRIPT_DIR/../../app/src/com/macchanger/MainActivity.java"
PRISTINE_TARGET="$SCRIPT_DIR/../../../.pristine/app/src/com/macchanger/MainActivity.java"

FAILURES=0
LOG_N=0

ok() { echo "selftest: ok    - $1"; }

bad() {
    echo "selftest: FAIL  - $1"
    FAILURES=$((FAILURES + 1))
}

dump() {
    if [ -s "$1" ]; then
        sed 's/^/selftest:   | /' "$1"
    else
        echo "selftest:   | (no output captured)"
    fi
}

# expect_pass <label> <file>
expect_pass() {
    LOG_N=$((LOG_N + 1))
    _log="$TMP/check.$LOG_N.log"
    sh "$CHECK" "$2" > "$_log" 2>&1
    _rc=$?
    _last=$(tail -n 1 "$_log")
    if [ "$_rc" -eq 0 ] && [ "$_last" = "STUBCOMPILE: PASS" ]; then
        ok "$1  [rc=0, last='$_last']"
    else
        bad "$1 -- expected PASS with exit 0, got rc=$_rc and last line '$_last'"
        dump "$_log"
    fi
}

# expect_fail <label> <file> <marker that must appear in the diagnostics>
expect_fail() {
    LOG_N=$((LOG_N + 1))
    _log="$TMP/check.$LOG_N.log"
    sh "$CHECK" "$2" > "$_log" 2>&1
    _rc=$?
    _last=$(tail -n 1 "$_log")
    if [ "$_rc" -eq 0 ]; then
        bad "$1 -- the gate reported PASS (exit 0) on a file that cannot compile"
        dump "$_log"
        return
    fi
    if [ "$_last" != "STUBCOMPILE: FAIL" ]; then
        bad "$1 -- expected the last line to be 'STUBCOMPILE: FAIL', got '$_last'"
        dump "$_log"
        return
    fi
    if ! grep -qF -- "$3" "$_log"; then
        bad "$1 -- failed, but the diagnostics never mention '$3', so it failed for another reason"
        dump "$_log"
        return
    fi
    ok "$1  [rc=$_rc, last='$_last', diagnostics mention '$3']"
}

if [ ! -f "$CHECK" ]; then
    echo "selftest: check.sh not found at $CHECK" >&2
    echo "SELFTEST: FAIL"
    exit 1
fi

TMP_ROOT=${TMPDIR:-/tmp}
TMP=$(mktemp -d "$TMP_ROOT/stubcompile-selftest.XXXXXX") || {
    echo "selftest: mktemp -d failed under $TMP_ROOT" >&2
    echo "SELFTEST: FAIL"
    exit 1
}
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

echo "selftest: check.sh   $CHECK"
echo "selftest: scratch    $TMP"

# ---------------------------------------------------------------- 1 and 2: baselines
if [ -f "$WORKING_TARGET" ]; then
    expect_pass "working-tree MainActivity.java compiles" "$WORKING_TARGET"
else
    bad "working-tree MainActivity.java not found at $WORKING_TARGET"
fi

if [ -f "$PRISTINE_TARGET" ]; then
    expect_pass "pristine baseline MainActivity.java compiles" "$PRISTINE_TARGET"
else
    bad "pristine baseline not found at $PRISTINE_TARGET"
fi

# ---------------------------------------------------------------- 3: unmodified copy
mkdir -p "$TMP/copy"
if [ -f "$WORKING_TARGET" ]; then
    cat "$WORKING_TARGET" > "$TMP/copy/MainActivity.java"

    if [ ! -s "$TMP/copy/MainActivity.java" ]; then
        bad "the temp copy of MainActivity.java is empty"
    else
        expect_pass "unmodified temp copy compiles (the copy path itself is not the problem)" \
            "$TMP/copy/MainActivity.java"

        # ------------------------------------------------------------ 4: broken syntax
        mkdir -p "$TMP/broken-brace"
        cat "$TMP/copy/MainActivity.java" > "$TMP/broken-brace/MainActivity.java"
        printf '\n}\n' >> "$TMP/broken-brace/MainActivity.java"
        expect_fail "injected stray '}' is rejected" \
            "$TMP/broken-brace/MainActivity.java" "error:"

        # -------------------------------------------------------- 5: undefined symbol
        mkdir -p "$TMP/broken-symbol"
        cat "$TMP/copy/MainActivity.java" > "$TMP/broken-symbol/MainActivity.java"
        cat >> "$TMP/broken-symbol/MainActivity.java" <<'JAVA'

class StubCompileNegativeControlSymbol {
    static final Object BROKEN = __stubcompile_negative_control_symbol__;
}
JAVA
        expect_fail "injected undefined symbol is rejected" \
            "$TMP/broken-symbol/MainActivity.java" "__stubcompile_negative_control_symbol__"

        # --------------------------------------------------- 6: member missing from stubs
        mkdir -p "$TMP/broken-api"
        cat "$TMP/copy/MainActivity.java" > "$TMP/broken-api/MainActivity.java"
        cat >> "$TMP/broken-api/MainActivity.java" <<'JAVA'

class StubCompileNegativeControlApi {
    static void probe(android.widget.TextView t) {
        t.setStubCompileNegativeControlApi(1);
    }
}
JAVA
        expect_fail "a method no android.* stub declares is rejected (types are really resolved)" \
            "$TMP/broken-api/MainActivity.java" "setStubCompileNegativeControlApi"

        # ------------------------------------------- 7: stub method called with wrong type
        mkdir -p "$TMP/broken-args"
        cat "$TMP/copy/MainActivity.java" > "$TMP/broken-args/MainActivity.java"
        cat >> "$TMP/broken-args/MainActivity.java" <<'JAVA'

class StubCompileNegativeControlArgs {
    static void probe(android.widget.TextView t) {
        t.setTextSize("not a number");
    }
}
JAVA
        expect_fail "a stub method called with the wrong argument type is rejected" \
            "$TMP/broken-args/MainActivity.java" "setTextSize"

        # ------------------------------------------------ 8: removed statement terminator
        mkdir -p "$TMP/broken-semicolon"
        LINE_NO=$(awk '!/^[[:space:]]*(\/\/|\*|\/\*)/ \
                       && !/^[[:space:]]*(package|import)[[:space:]]/ \
                       && /;[[:space:]]*$/ { print NR; exit }' \
            "$TMP/copy/MainActivity.java")
        if [ -z "$LINE_NO" ]; then
            bad "could not find a statement-terminating line to break for the semicolon control"
        else
            sed "${LINE_NO}s/;[[:space:]]*$//" "$TMP/copy/MainActivity.java" \
                > "$TMP/broken-semicolon/MainActivity.java"
            echo "selftest: note  - semicolon control removed ';' from line $LINE_NO"
            expect_fail "injected missing ';' is rejected" \
                "$TMP/broken-semicolon/MainActivity.java" "error:"
        fi
    fi
else
    bad "cannot run the copy-based controls: $WORKING_TARGET is missing"
fi

# ---------------------------------------------------------------- 8: nonexistent target
expect_fail "a nonexistent target is refused" \
    "$TMP/does-not-exist/MainActivity.java" "no such Java source"

# ---------------------------------------------------------------------------- verdict
if [ "$FAILURES" -eq 0 ]; then
    echo "selftest: all controls behaved as expected"
    echo "SELFTEST: PASS"
    exit 0
fi

echo "selftest: $FAILURES control(s) failed"
echo "SELFTEST: FAIL"
exit 1
