#!/bin/sh
#
# package.sh - build the distributable archive of the project into dist/.
#
#   sh tools/package.sh [--out DIR] [--name NAME] [--root DIR] [--no-verify] [--quiet]
#   sh tools/package.sh --check PATH [--check PATH ...] [--allow-audit] [--no-freshness]
#
# Produces, in $ROOT/../dist by default:
#
#   MacChanger-<versionName>-vc<versionCode>.tar.gz          the archive
#   MacChanger-<versionName>-vc<versionCode>.tar.gz.sha256   its SHA-256
#   MacChanger-<versionName>-vc<versionCode>.inventory.txt   every file inside,
#                                                            with its size, mode
#                                                            and SHA-256, plus
#                                                            the archive's own
#                                                            hash at the top
#
# so a user can verify what they received without trusting this script or the
# network it came over: sha256sum the archive, then compare the inventory line by
# line against the extracted tree.
#
# What goes in
#   README.md, LICENSE, SECURITY.md, CHANGELOG.md, .gitignore, docs/ (the
#   long-form documentation the README links to: SAFETY, CLI, APP, DEVICES,
#   HOW-IT-WORKS, DEVELOPING), app/ (source, manifest, build.sh),
#   cli/macchanger.sh, prebuilt/MacChanger.apk, tools/ (the offline verification
#   harnesses).
#
# What stays out, and why
#   .git/                       not a release artifact
#   dist/                       the output directory itself
#   AUDIT.md, audit/            internal audit: it quotes the removed keystore
#                               password and the vulnerable code, and it is not
#                               written for users
#   keystore material (*.jks, *.keystore, *.bks, *.p12, *.pfx, *.pk8, *.pem,
#   *.der, *.key)               a signing key must never ship - and the build
#                               REFUSES to package at all while one is present
#   app/build/, *.class, *.dex, *-unsigned.apk, *-aligned.apk, *-signed.apk
#                               build outputs; only prebuilt/MacChanger.apk is
#                               shipped, because the README and CHANGELOG
#                               publish its hash
#   tool scratch (__pycache__, *.pyc, *.log, *.tmp, .pytest_cache, work/,
#   node_modules/)              nothing a user needs
#
# Refusals (exit status 1, nothing written):
#   * a keystore or key file anywhere in the tree - by name or by keystore
#     magic bytes, in every file including the ones the payload excludes
#     (except under .git/ and the output directory)
#   * the string 'pass:android' anywhere in the files that would be packaged
#   * a non-reproducible archive (two builds of the same tree differing)
#   * an archive that itself fails the hand-off check (see below)
#
# The deliverable, and what must never be one
#   The deliverable is exactly this tree ($ROOT), or the .tar.gz this script
#   writes.  It is NOT the directory that contains the tree.  On an audit or
#   development machine that directory also holds the audit artifacts, the
#   pristine snapshot and often a signing keystore, so a hand-off produced with
#   'zip -r ../handoff.zip .' or 'cp -a' of the enclosing directory re-ships
#   the private key next to the APK - which is how the key leaked the first
#   time.  Two things guard against it: this script never reads or copies
#   anything outside $ROOT, and it scans what is around the tree and says so
#   (a warning, not a refusal: the tree itself is clean and is a legitimate
#   deliverable).  Verify any artifact you are about to hand over, whatever
#   produced it, with
#
#       sh tools/package.sh --check PATH
#
#   which accepts a directory, a .tar.gz/.tgz/.tar or a .zip and fails on any
#   signing key (by name, by keystore magic bytes, or in a git history), on any
#   AUDIT.md or audit/ entry, on an archive whose member paths escape the
#   extraction root, and on an artifact that is missing the files a complete
#   deliverable has.  An archive is also compared member by member, by content,
#   against the tree this script lives in: an archive that no longer matches it
#   is a superseded revision, and passing it off as the current deliverable is
#   how a user ends up installing code nobody verified, so that is a FAIL too
#   (--no-freshness opts out for an artifact received from elsewhere, and the
#   verdict then says so).  Exit status 0 = safe to hand off (the
#   audit-carriage warning below also exits 0), 1 = do not hand this over,
#   2 = usage error.  The last line is "HANDOFF: PASS|WARN|FAIL <path> - <why>".
#
#   --check finds key material three ways, because a name is not a promise:
#   the file name (*.jks, *.keystore, *.p12, *.pem, ...), the first bytes (JKS
#   fe ed fe ed, JCEKS ce ce ce ce, a PKCS#12 PFX header 30 82 .. .. 02 01 03
#   30 82, a PEM private-key header at offset 0), and 'git log --all
#   --name-only' for a directory that carries a .git, where a scan of the
#   working files cannot see into packed objects.  --allow-audit downgrades
#   audit carriage (AUDIT.md, audit/) from FAIL to WARN for the deliberate case
#   of handing the tree to an auditor; it never downgrades a key.
#
# Reproducibility: the archive is built with 'tar --sort=name --owner=0
# --group=0 --numeric-owner --mtime=@0' piped through 'gzip -n', so the same
# tree always produces the same bytes, and this script proves it by building
# twice and comparing the hashes before it reports success.  On a tar without
# those GNU options the archive is still built, but the script says so and does
# not claim byte-reproducibility.
#
# Exit status, packaging: 0 packaged, 1 refused or verification failed, 2 usage
# error.  The last line is "PACKAGE: OK <archive> <sha256>", "PACKAGE: REFUSED
# ..." or "PACKAGE: FAILED ...".  In --check mode the last line is
# "HANDOFF: PASS|WARN|FAIL <path> - <why>" (see above).
#
# Requires: POSIX sh, find, grep, sha256sum, tar, gzip, awk, sed, cmp, sort, od,
# dd.  unzip and git are used in --check mode when they are installed.  No
# network, no Android SDK, no Gradle, no zip (the archive is a .tar.gz so it
# needs nothing but tar/gzip on the receiving end).

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

OUT=
NAME=
VERIFY=yes
QUIET=no
FRESHNESS=yes
CHECK_PATHS=
ALLOW_AUDIT=no

usage() {
    cat <<EOF
usage: sh $0 [--out DIR] [--name NAME] [--root DIR] [--no-verify] [--quiet]
       sh $0 --check PATH [--check PATH ...] [--allow-audit] [--no-freshness] [--quiet]

  --out DIR     write the archive into DIR (default: <root>/../dist)
  --name NAME   archive base name (default: MacChanger-<versionName>-vc<versionCode>)
  --root DIR    package this tree instead of the one this script lives in
                (used to prove the refusal paths against another checkout)
  --no-verify   skip the reproducibility and extraction checks (faster, weaker)
  --quiet       print only the final verdict line and the findings
  --check PATH  do not build anything: verify that PATH - a directory, a
                .tar.gz/.tgz/.tar or a .zip - is safe and complete enough to
                hand off (no signing key by name or by magic bytes and none in
                a git history, no AUDIT.md, no audit/, no member path escaping
                the extraction root).  An archive is additionally compared,
                member by member and byte for byte, against the tree this
                script belongs to, so a superseded archive cannot pass as the
                current one.  Repeatable.
  --no-freshness
                with --check: skip that comparison.  Use it to check the key
                hygiene of an artifact that came from somewhere else; the
                verdict then says freshness was not checked.
  --allow-audit with --check: report AUDIT.md/audit/ carriage as a warning
                instead of a failure (handing the tree to an auditor)

builds a distributable .tar.gz of $ROOT, refusing to package while a signing
key or a literal keystore password is still present in the tree.  The
deliverable is this tree or that archive, never a copy of the directory that
contains the tree; --check is what proves it before a hand-off.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --out)       [ $# -ge 2 ] || { usage >&2; exit 2; }; OUT=$2; shift 2 ;;
        --out=*)     OUT=${1#--out=}; shift ;;
        --name)      [ $# -ge 2 ] || { usage >&2; exit 2; }; NAME=$2; shift 2 ;;
        --name=*)    NAME=${1#--name=}; shift ;;
        --root)      [ $# -ge 2 ] || { usage >&2; exit 2; }; ROOT=$2; shift 2 ;;
        --root=*)    ROOT=${1#--root=}; shift ;;
        --check)     [ $# -ge 2 ] || { usage >&2; exit 2; }
                     case "$2" in
                         -*) usage >&2
                             printf 'package: --check needs a PATH, not an option: %s\n' "$2" >&2
                             exit 2 ;;
                     esac
                     CHECK_PATHS="$CHECK_PATHS$2
"; shift 2 ;;
        --check=*)   CHECK_PATHS="$CHECK_PATHS${1#--check=}
"; shift ;;
        --allow-audit) ALLOW_AUDIT=yes; shift ;;
        --no-freshness) FRESHNESS=no; shift ;;
        --no-verify) VERIFY=no; shift ;;
        --quiet)     QUIET=yes; shift ;;
        -h|--help)   usage; exit 0 ;;
        *) usage >&2; printf 'package: unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

say()  { [ "$QUIET" = yes ] || printf '%s\n' "$*"; }
refuse() { printf 'package: refusing to package: %s\n' "$1" >&2; printf 'PACKAGE: REFUSED %s\n' "$1"; exit 1; }
die()    { printf 'package: %s\n' "$1" >&2; printf 'PACKAGE: FAILED %s\n' "$1"; exit 2; }

# =============================================================================
# The hand-off scanner
#
# One set of rules, used by three callers: --check on whatever the operator
# points at, the gate on the archive this script has just built, and the
# warning about what sits around the tree.  A file is key material if its name
# says so OR its contents say so; a name is not a promise, and the key this
# project leaked was a PKCS#12 file that kept the .jks extension.
#
# Every finding is one line of "CATEGORY<TAB>reason<TAB>path<TAB>sha256":
# SECRET (a signing key), HISTORY (a key in a git history), AUDIT (an audit
# artifact) or UNSAFE (an archive member path that escapes the extraction
# root).  Categories are compared as whole words, never grepped as substrings,
# because a path can contain the word "AUDIT".
# =============================================================================

SCAN_SKIP=   # space-separated path prefixes scan_tree must not descend

# keyish_name NAME - prints a reason when the NAME looks like key material or an
# audit artifact.  Case is folded with tr so KS.JKS cannot slip through.
keyish_name() {
    _kn=$(printf '%s' "$1" | tr 'A-Z' 'a-z')
    case "$_kn" in
        *.jks|*.keystore|*.bks|*.p12|*.pfx|*.pk8|*.pem|*.der|*.key)
            printf 'signing key material by name (*.%s)' "${_kn##*.}"; return 0 ;;
        audit.md|*/audit.md)
            printf 'audit artifact by name (AUDIT.md quotes the removed keystore password)'; return 0 ;;
        audit/*|*/audit/*)
            printf 'audit artifact by path (audit/ is the audit, not the product)'; return 0 ;;
    esac
    return 1
}

# sniff_key FILE - prints a reason when the bytes look like a keystore or a PEM
# block.  dd/od are used rather than 'head -c' (not POSIX) and rather than a
# command substitution over the bytes (dash warns about NULs and would put that
# warning on stderr for every binary file it scanned).
sniff_key() {
    [ -f "$1" ] || return 1
    [ -s "$1" ] || return 1
    _sk=$(dd if="$1" bs=16 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
    case "$_sk" in
        feedfeed*) printf 'JKS keystore contents (magic fe ed fe ed)'; return 0 ;;
        cececece*) printf 'JCEKS keystore contents (magic ce ce ce ce)'; return 0 ;;
        3082*0201033082*) printf 'PKCS#12 keystore contents (PFX header 30 82 .. .. 02 01 03 30 82)'; return 0 ;;
    esac
    if dd if="$1" bs=4096 count=1 2>/dev/null | grep -q '^-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----'; then
        printf 'PEM private key at offset 0 (a key file named something else)'; return 0
    fi
    return 1
}

# leak_reason FILE REPORTED_PATH - name first, then bytes.
leak_reason() {
    if _lr=$(keyish_name "$2"); then printf '%s' "$_lr"; return 0; fi
    if _lr=$(sniff_key "$1"); then printf '%s' "$_lr"; return 0; fi
    return 1
}

# emit_finding REASON PATH SHA - one findings line, with the category decided by
# the reason.  An audit artifact is not a secret: it is a file that must not go
# to a user because it quotes the old keystore and the vulnerable code, and
# --allow-audit may deliberately downgrade it.  A key is never downgraded.
emit_finding() {
    case "$1" in
        "audit artifact"*) printf 'AUDIT\t%s\t%s\t%s\n' "$1" "$2" "$3" ;;
        *)                 printf 'SECRET\t%s\t%s\t%s\n' "$1" "$2" "$3" ;;
    esac
}

# scan_tree DIR [PREFIX] - findings under DIR.  .git is pruned: its objects are
# compressed, so a content scan cannot see into them; the history check in
# check_one covers that case properly.
scan_tree() {
    _st_dir=$1
    _st_pfx=${2:-}
    find "$_st_dir" \( -name .git -o -name node_modules -o -name __pycache__ \) -prune -o \
        -type f -print 2>/dev/null | LC_ALL=C sort | while IFS= read -r _st_f; do
            _st_skip=no
            for _st_p in $SCAN_SKIP; do
                case "$_st_f" in "$_st_p"/*) _st_skip=yes ;; esac
            done
            [ "$_st_skip" = yes ] && continue
            _st_rel="$_st_pfx${_st_f#"$_st_dir"/}"
            if _st_r=$(leak_reason "$_st_f" "$_st_rel"); then
                emit_finding "$_st_r" "$_st_rel" \
                    "$(sha256sum <"$_st_f" 2>/dev/null | awk '{ print $1 }')"
            fi
        done
}

# =============================================================================
# The payload list: what a deliverable contains.
#
# Defined here, above both the --check block and the packaging path, because
# two callers need the same answer: packaging (to stage the payload) and
# --check (to prove that an archive is the current output of the tree this
# script lives in).  Two copies of these rules would drift, and a freshness
# test that used different rules would report a difference that is not one.
# excl() is a no-op in --check mode, where nothing is being excluded from a
# package and there is no exclusion log to write to.
# =============================================================================
excl() { [ -n "${EXCL_LOG:-}" ] || return 0; printf '%s\t%s\n' "$2" "$1" >>"$EXCL_LOG"; }

keep() { # $1 = path relative to ROOT -> 0 = include
    case "$1" in
        AUDIT.md|audit/*|.git/*|dist/*) excl "$1" "audit or vcs artifact"; return 1 ;;
        */.git/*|*/dist/*)              excl "$1" "nested vcs/audit artifact"; return 1 ;;
        tools/checks/checks.sh)         return 0 ;;   # explicitly kept: the scanner itself
        *.jks|*.keystore|*.bks|*.p12|*.pfx|*.pk8|*.pem|*.der|*.key) excl "$1" "keystore material"; return 1 ;;
        *.class|*.dex|*.pyc|*.log|*.tmp|.DS_Store) excl "$1" "build output or scratch"; return 1 ;;
        prebuilt/*.apk)                 return 0 ;;
        *.apk)                          excl "$1" "build output (only prebuilt/MacChanger.apk ships)"; return 1 ;;
        *)                              return 0 ;;
    esac
}

# payload_list ROOT [SKIP_DIR_NAME] - the relative paths a package of ROOT
# holds, sorted.  SKIP_DIR_NAME is this script's own archive directory name,
# so packaging never picks up its own output; --check mode passes nothing.
payload_list() {
    find "$1" \
        \( -name .git -o -name dist -o -name __pycache__ -o -name .pytest_cache \
           -o -name node_modules -o -name build -o -name work \) -prune -o \
        -type f -print 2>/dev/null \
      | LC_ALL=C sort \
      | while IFS= read -r _pl_f; do
            _pl_rel=${_pl_f#"$1"/}
            if [ -n "${2:-}" ]; then
                case "$_pl_rel" in "$2"/*) continue ;; esac
            fi
            if keep "$_pl_rel"; then
                printf '%s\n' "$_pl_rel"
            fi
        done
}

# check_one PATH - verify one candidate deliverable.  Returns 0 for PASS, 3 for
# WARN (audit carriage, allowed with --allow-audit) and 1 for FAIL.  Prints the
# verdict line itself.
check_one() {
    _c_path=$1
    _c_find="$CK_TMP/findings.txt"
    _c_note="$CK_TMP/notes.txt"
    _c_members=
    _c_members_rel=
    _c_ok=no
    : >"$_c_find"
    : >"$_c_note"

    case "$_c_path" in
        */) _c_path=${_c_path%/} ;;
    esac

    if [ -d "$_c_path" ]; then
        # A directory hand-off: what it holds, and - because the working tree
        # carries a .git whose packed objects no file scan can read - what its
        # history once held.
        scan_tree "$_c_path" >>"$_c_find"
        if [ -d "$_c_path/.git" ]; then
            if command -v git >/dev/null 2>&1; then
                _c_hist=$(git -C "$_c_path" log --all --name-only --pretty=format: 2>/dev/null \
                          | LC_ALL=C sort -u | grep -E '\.(jks|keystore|bks|p12|pfx|pk8|pem|der|key|crt|cer)$')
                if [ -n "$_c_hist" ]; then
                    printf '%s\n' "$_c_hist" | while IFS= read -r _c_h; do
                        printf 'HISTORY\tsigning key material committed to the git history\t%s\t-\n' "$_c_h"
                    done >>"$_c_find"
                else
                    printf 'every path of every commit in %s/.git was listed and none is key material (a packed object can still hold one under a harmless name - this is a name check on history, not a byte scan of it)\n' "$_c_path" >>"$_c_note"
                fi
            else
                printf '%s carries a .git but git is not installed, so its history was NOT checked\n' "$_c_path" >>"$_c_note"
            fi
        fi
        _c_members=
    elif [ -f "$_c_path" ]; then
        _c_members="$CK_TMP/members.txt"
        _c_arc=
        case "$_c_path" in
            *.tar.gz|*.tgz)    _c_arc=tar.gz ;;
            *.tar)             _c_arc=tar ;;
            *.zip|*.apk|*.jar) _c_arc=zip ;;
            *)                 _c_arc= ;;
        esac
        if [ -n "$_c_arc" ]; then
            case "$_c_arc" in
                tar.gz) tar -tzf "$_c_path" >"$_c_members" 2>/dev/null || _c_arc=unreadable ;;
                tar)    tar -tf  "$_c_path" >"$_c_members" 2>/dev/null || _c_arc=unreadable ;;
                zip)    if command -v unzip >/dev/null 2>&1; then
                            unzip -Z1 "$_c_path" >"$_c_members" 2>/dev/null || _c_arc=unreadable
                        else
                            _c_arc=nounzip
                        fi ;;
            esac
        fi
        case "$_c_arc" in
            unreadable)
                printf '%s is named like an archive but cannot be listed, so its contents are unknown\n' "$_c_path" >>"$_c_note" ;;
            nounzip)
                printf '%s is a .zip and unzip is not installed, so only its name could be checked\n' "$_c_path" >>"$_c_note"
                if _c_r=$(leak_reason "$_c_path" "$(basename -- "$_c_path")"); then
                    emit_finding "$_c_r" "$(basename -- "$_c_path")" \
                        "$(sha256sum <"$_c_path" | awk '{ print $1 }')" >>"$_c_find"
                fi
                _c_members= ;;
            "")
                # A plain file that is not an archive: judge the file itself.
                if _c_r=$(leak_reason "$_c_path" "$(basename -- "$_c_path")"); then
                    emit_finding "$_c_r" "$(basename -- "$_c_path")" \
                        "$(sha256sum <"$_c_path" | awk '{ print $1 }')" >>"$_c_find"
                fi
                _c_members= ;;
        esac
        if [ -n "$_c_members" ]; then
            # Names inside the archive, before anything is unpacked.  Directory
            # entries are skipped: an empty directory carries nothing, and its
            # files are reported individually either here or from the bytes.
            while IFS= read -r _c_m; do
                _c_m=${_c_m#./}
                [ -n "$_c_m" ] || continue
                case "$_c_m" in */) continue ;; esac
                if _c_r=$(keyish_name "$_c_m"); then
                    emit_finding "$_c_r" "$_c_m" "-" >>"$_c_find"
                fi
            done <"$_c_members"
            # Member paths that would write outside the extraction root: the
            # archive is not unpacked at all, and the fact is reported.
            _c_esc=$(grep -E '(^|/)\.\.(/|$)|^/|^[A-Za-z]:' "$_c_members" 2>/dev/null | head -5)
            if [ -n "$_c_esc" ]; then
                printf '%s\n' "$_c_esc" | while IFS= read -r _c_e; do
                    printf 'UNSAFE\tarchive member path escapes the extraction root\t%s\t-\n' "$_c_e"
                done >>"$_c_find"
                printf '%s was NOT extracted (its member paths escape the extraction root), so its file contents were not scanned\n' "$_c_path" >>"$_c_note"
            else
                # Bytes inside the archive: unpack into the check scratch and
                # run the same file scan over the result.
                mkdir -p "$CK_TMP/x" || printf 'cannot create %s\n' "$CK_TMP/x" >>"$_c_note"
                _c_ok=no
                case "$_c_arc" in
                    tar.gz) tar -xzf "$_c_path" -C "$CK_TMP/x" 2>/dev/null && _c_ok=yes ;;
                    tar)    tar -xf  "$_c_path" -C "$CK_TMP/x" 2>/dev/null && _c_ok=yes ;;
                    zip)    unzip -qq "$_c_path" -d "$CK_TMP/x" >/dev/null 2>&1 && _c_ok=yes ;;
                esac
                if [ "$_c_ok" = yes ]; then
                    SCAN_SKIP= scan_tree "$CK_TMP/x" >>"$_c_find"
                else
                    printf '%s could not be extracted, so its file contents were not scanned\n' "$_c_path" >>"$_c_note"
                fi
            fi
            # Completeness: rel paths with the single top-level directory removed.
            _c_members_rel="$CK_TMP/members_rel.txt"
            sed 's|^[^/]*/||' "$_c_members" | grep -v '/$' >"$_c_members_rel" 2>/dev/null
            # ... which is only meaningful when there IS a single top-level
            # directory.  Every archive this script writes has one; a tarball
            # made by hand may not, and stripping its first path component would
            # compare the wrong files and report nonsense, so freshness is
            # skipped (with a note) for those instead of raising a false STALE.
            _c_tops=$(sed 's|^\./||' "$_c_members" | grep -v '/$' | grep '/' \
                      | awk -F/ '{ print $1 }' | LC_ALL=C sort -u | wc -l | tr -d ' ')

            # --- freshness ---------------------------------------------------
            # Is this archive the CURRENT output of the tree this script lives
            # in?  A key hygiene gate cannot answer that: the archive in dist/
            # was self-consistent (its own .sha256 matched it, its own
            # inventory matched it, --check passed it) while being twelve files
            # behind the tree, so a green PASS was read as an endorsement of a
            # superseded revision - the very artifact a user would install.
            # Both directions are compared, by content and not by presence:
            # every member against the file of that name in the tree, and every
            # file the tree would package against the members.  The tree is the
            # reference because it is the only thing that knows what "current"
            # means; --no-freshness opts out for the case of checking an
            # artifact received from somewhere else.
            if [ "$FRESHNESS" = yes ] && [ "$_c_ok" = yes ] && [ -d "$ROOT/app" ] && [ -n "$_c_members_rel" ] \
               && [ "$_c_tops" = 1 ]; then
                _c_tree="$CK_TMP/payload_tree.txt"
                payload_list "$ROOT" "${NAME:-}" >"$_c_tree"
                _c_mrels="$CK_TMP/members_rel_sorted.txt"
                LC_ALL=C sort "$_c_members_rel" >"$_c_mrels"
                _c_stale_n=0
                _c_mn=0
                while IFS= read -r _c_m; do
                    case "$_c_m" in */) continue ;; esac
                    _c_rel=${_c_m#./}
                    _c_rel=$(printf '%s\n' "$_c_rel" | sed 's|^[^/]*/||')
                    [ -n "$_c_rel" ] || continue
                    _c_mn=$((_c_mn + 1))
                    if [ ! -f "$ROOT/$_c_rel" ]; then
                        printf 'STALE\tthis tree has no file of that name\t%s\t-\n' "$_c_rel" >>"$_c_find"
                        _c_stale_n=$((_c_stale_n + 1))
                        continue
                    fi
                    if [ -f "$CK_TMP/x/$_c_m" ] \
                       && [ "$(sha256sum <"$CK_TMP/x/$_c_m" | awk '{ print $1 }')" \
                            != "$(sha256sum <"$ROOT/$_c_rel" | awk '{ print $1 }')" ]; then
                        printf 'STALE\tdiffers from the file in this tree\t%s\t%s\n' \
                            "$_c_rel" "$(sha256sum <"$ROOT/$_c_rel" | awk '{ print $1 }')" >>"$_c_find"
                        _c_stale_n=$((_c_stale_n + 1))
                    fi
                done <"$_c_members"
                while IFS= read -r _c_rel; do
                    if ! grep -qxF "$_c_rel" "$_c_mrels"; then
                        printf 'STALE\tthis tree has it, the archive does not\t%s\t-\n' "$_c_rel" >>"$_c_find"
                        _c_stale_n=$((_c_stale_n + 1))
                    fi
                done <"$_c_tree"
                if [ "$_c_stale_n" -eq 0 ]; then
                    printf 'all %s member(s) of %s match %s byte for byte, and the tree holds no payload file the archive lacks, so the archive is this tree current\n' \
                        "$_c_mn" "$(basename -- "$_c_path")" "$ROOT" >>"$_c_note"
                fi
            elif [ "$FRESHNESS" = no ]; then
                printf 'freshness against %s was NOT checked (--no-freshness): this artifact may be a superseded revision\n' "$ROOT" >>"$_c_note"
            elif [ "$_c_ok" != yes ] && [ -n "$_c_members_rel" ]; then
                printf 'freshness against %s could not be checked: the archive was not extracted\n' "$ROOT" >>"$_c_note"
            elif [ "$FRESHNESS" = yes ] && [ "$_c_tops" != 1 ] && [ -n "$_c_members_rel" ]; then
                printf 'freshness against %s could not be checked: this archive has %s distinct top-level directories, so it is not one of the archives tools/package.sh writes (those hold a single directory) and its members cannot be lined up with the tree\n' \
                    "$ROOT" "$_c_tops" >>"$_c_note"
            fi
        fi
    else
        printf 'HANDOFF: FAIL %s - no such file or directory\n' "$_c_path"
        return 1
    fi

    # --- required files: an artifact that is missing them is not a deliverable
    # docs/*.md are required for the same reason README.md is: the front page
    # links every one of them as the depth behind a warning, so an artifact
    # without them is a deliverable with dead links and the caveats cut off.  The
    # list is the six files README.md points at, not "all of docs/": a new docs
    # file is documentation the archive carries, but only these six are promised
    # by the front page.
    _c_missing=
    for _c_req in README.md app/build.sh app/AndroidManifest.xml \
                  app/src/com/macchanger/MainActivity.java cli/macchanger.sh \
                  prebuilt/MacChanger.apk tools/package.sh \
                  docs/SAFETY.md docs/CLI.md docs/APP.md docs/DEVICES.md \
                  docs/HOW-IT-WORKS.md docs/DEVELOPING.md; do
        if [ -d "$_c_path" ]; then
            [ -f "$_c_path/$_c_req" ] || _c_missing="$_c_missing $_c_req"
        elif [ -n "$_c_members_rel" ]; then
            grep -qxF "$_c_req" "$_c_members_rel" 2>/dev/null || _c_missing="$_c_missing $_c_req"
        else
            _c_missing="$_c_missing $_c_req"
        fi
    done

    # --- classify.  An archive is scanned twice (member names, then the
    # extracted bytes), so the same file can be reported by both; one finding
    # per path is kept, preferring the one that carries a SHA-256.
    awk -F'\t' '
        {
            key = $3
            if (!(key in best)) { best[key] = $0; bsha[key] = $4; next }
            if (bsha[key] == "-" && $4 != "-") { best[key] = $0; bsha[key] = $4 }
        }
        END { for (k in best) print best[k] }
    ' "$_c_find" | LC_ALL=C sort -t"	" -k3 >"$_c_find.d"
    mv "$_c_find.d" "$_c_find"

    _c_keys=$(awk -F'\t' '$1 == "SECRET" { print }' "$_c_find")
    _c_hist=$(awk -F'\t' '$1 == "HISTORY" { print }' "$_c_find")
    _c_unsafe=$(awk -F'\t' '$1 == "UNSAFE" { print }' "$_c_find")
    _c_audit=$(awk -F'\t' '$1 == "AUDIT" { print }' "$_c_find")
    _c_stale=$(awk -F'\t' '$1 == "STALE" { print }' "$_c_find")
    _c_nkey=$(printf '%s\n' "$_c_keys" | grep -c . )
    _c_nhist=$(printf '%s\n' "$_c_hist" | grep -c . )
    _c_naudit=$(printf '%s\n' "$_c_audit" | grep -c . )
    _c_nunsafe=$(printf '%s\n' "$_c_unsafe" | grep -c . )
    _c_nstale=$(printf '%s\n' "$_c_stale" | grep -c . )

    printf '\nhand-off check: %s\n' "$_c_path"
    if [ "$CK_VERBOSE" = yes ]; then
        while IFS= read -r _c_n; do printf '  note: %s\n' "$_c_n"; done <"$_c_note"
    fi
    if [ "$_c_nkey" -gt 0 ]; then
        printf '  found key material:\n'
        printf '%s\n' "$_c_keys" | while IFS='	' read -r _c_c _c_r _c_p _c_s; do
            printf '    %s\n      path   %s\n      sha256 %s\n' "$_c_r" "$_c_p" "$_c_s"
        done
    fi
    if [ "$_c_nhist" -gt 0 ]; then
        printf '  found key material in the git history:\n'
        printf '%s\n' "$_c_hist" | while IFS='	' read -r _c_c _c_r _c_p _c_s; do printf '    %s\n' "$_c_p"; done
    fi
    if [ "$_c_nunsafe" -gt 0 ]; then
        printf '  found member paths that escape the extraction root (%s):\n' "$_c_nunsafe"
        printf '%s\n' "$_c_unsafe" | while IFS='	' read -r _c_c _c_r _c_p _c_s; do printf '    %s\n' "$_c_p"; done
    fi
    if [ "$_c_nstale" -gt 0 ]; then
        printf '  stale: %s member(s) do not match %s:\n' "$_c_nstale" "$ROOT"
        printf '%s\n' "$_c_stale" | head -n 10 | while IFS='	' read -r _c_c _c_r _c_p _c_s; do
            printf '    %s (%s)\n' "$_c_p" "$_c_r"
        done
        [ "$_c_nstale" -le 10 ] || printf '    ... and %s more\n' "$((_c_nstale - 10))"
        printf '    this archive is not the current output of %s: rebuild it with\n' "$ROOT"
        printf "    'sh tools/package.sh' before handing it over (a stale archive carries a\n"
        printf '    revision nobody verified).  --no-freshness checks key hygiene only.\n'
    fi
    if [ "$_c_naudit" -gt 0 ]; then
        printf '  carries the audit (%s entry/entries), which is not written for users:\n' "$_c_naudit"
        printf '%s\n' "$_c_audit" | while IFS='	' read -r _c_c _c_r _c_p _c_s; do printf '    %s\n' "$_c_p"; done
        printf '    the archive tools/package.sh writes excludes these; hand off that archive, or\n'
        printf '    copy the tree without AUDIT.md and audit/ (--allow-audit downgrades this to a warning)\n'
    fi
    if [ -n "$_c_missing" ]; then
        printf '  not a complete deliverable at its root (a deliverable has these there):%s\n' "$_c_missing"
    fi

    if [ "$_c_nkey" -gt 0 ] || [ "$_c_nhist" -gt 0 ] || [ "$_c_nunsafe" -gt 0 ]; then
        printf 'HANDOFF: FAIL %s - %s key finding(s), %s history finding(s), %s unsafe member path(s); do not hand this over\n' \
            "$_c_path" "$_c_nkey" "$_c_nhist" "$_c_nunsafe"
        return 1
    fi
    if [ -n "$_c_missing" ]; then
        printf 'HANDOFF: FAIL %s - not a complete deliverable, missing:%s\n' "$_c_path" "$_c_missing"
        return 1
    fi
    if [ "$_c_nstale" -gt 0 ]; then
        printf 'HANDOFF: FAIL %s - no key material, but the archive is stale: %s of its member(s) do not match the tree this script belongs to (%s); rebuild it with '"'"'sh tools/package.sh'"'"' rather than handing off a revision nobody verified\n' \
            "$_c_path" "$_c_nstale" "$ROOT"
        return 1
    fi
    if [ "$_c_naudit" -gt 0 ]; then
        if [ "$ALLOW_AUDIT" = yes ]; then
            printf 'HANDOFF: WARN %s - no key material, but it carries AUDIT.md/audit/ (allowed by --allow-audit); do not give this to a user\n' "$_c_path"
            return 3
        fi
        printf 'HANDOFF: FAIL %s - no key material, but it carries AUDIT.md/audit/; hand off the tools/package.sh archive instead, or pass --allow-audit deliberately\n' "$_c_path"
        return 1
    fi
    if [ "$FRESHNESS" = no ] && [ -n "$_c_members_rel" ]; then
        printf 'HANDOFF: PASS %s - no signing key by name, by keystore magic bytes or in a git history, no AUDIT.md, no audit/, no escaping member path, and every required file present.  Freshness against %s was NOT checked (--no-freshness): this may be a superseded revision\n' \
            "$_c_path" "$ROOT"
        return 0
    fi
    printf 'HANDOFF: PASS %s - no signing key by name, by keystore magic bytes or in a git history, no AUDIT.md, no audit/, no escaping member path, every required file present, and every member byte-identical to %s\n' \
        "$_c_path" "$ROOT"
    return 0
}

# =============================================================================
# --check: verify existing artifacts and exit.  Nothing is built, nothing
# outside the scratch directory is written.
# =============================================================================
if [ -n "$CHECK_PATHS" ]; then
    if [ "$QUIET" = yes ]; then CK_VERBOSE=no; else CK_VERBOSE=yes; fi
    CK_TMP=$(mktemp -d "${TMPDIR:-/tmp}/macchanger-handoff.XXXXXX") || {
        printf 'package: mktemp -d failed\n' >&2; printf 'HANDOFF: FAIL - no scratch directory\n'; exit 2; }
    trap 'rm -rf "$CK_TMP"' EXIT
    CK_FILES="$CK_TMP/paths"
    printf '%s' "$CHECK_PATHS" >"$CK_FILES"
    CK_FAILS=0
    CK_WARNS=0
    CK_CHECKED=0
    while IFS= read -r _ck_p; do
        [ -n "$_ck_p" ] || continue
        # A fresh extraction directory per artifact: leftovers from the
        # previous one must not be scanned again as if they were in this one.
        rm -rf "$CK_TMP/x"
        CK_CHECKED=$((CK_CHECKED + 1))
        check_one "$_ck_p"
        case $? in
            0) ;;
            3) CK_WARNS=$((CK_WARNS + 1)) ;;
            *) CK_FAILS=$((CK_FAILS + 1)) ;;
        esac
    done <"$CK_FILES"
    rm -rf "$CK_TMP"
    if [ "$CK_CHECKED" -eq 0 ]; then
        printf 'package: --check needs a path\n' >&2
        printf 'HANDOFF: FAIL - no artifact was named\n'
        exit 2
    fi
    if [ "$CK_FAILS" -eq 0 ] && [ "$CK_WARNS" -eq 0 ]; then
        printf '\nHANDOFF: PASS (%s of %s artifact(s) safe to hand off)\n' "$CK_CHECKED" "$CK_CHECKED"
        exit 0
    fi
    if [ "$CK_FAILS" -eq 0 ]; then
        printf '\nHANDOFF: WARN (%s of %s artifact(s) carry the audit, allowed by --allow-audit; no key material anywhere)\n' \
            "$CK_WARNS" "$CK_CHECKED"
        exit 0
    fi
    printf '\nHANDOFF: FAIL (%s of %s artifact(s) must not be handed off)\n' "$CK_FAILS" "$CK_CHECKED"
    exit 1
fi

ROOT=$(CDPATH= cd -- "$ROOT" 2>/dev/null && pwd) || die "no such directory: $ROOT"
[ -d "$ROOT/app" ] || die "$ROOT does not look like the project root (no app/)"
[ -n "$OUT" ] || OUT=$(CDPATH= cd -- "$ROOT/.." && pwd)/dist
case "$OUT" in
    /*) ;;
    *) OUT=$(CDPATH= cd -- "$(dirname -- "$OUT")" 2>/dev/null && pwd)/$(basename -- "$OUT") ;;
esac

# --- version ----------------------------------------------------------------
_manifest="$ROOT/app/AndroidManifest.xml"
_VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$_manifest" 2>/dev/null | head -n 1)
_VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' "$_manifest" 2>/dev/null | head -n 1)
[ -n "$_VERSION_NAME" ] || _VERSION_NAME=unversioned
[ -n "$_VERSION_CODE" ] || _VERSION_CODE=0
[ -n "$NAME" ] || NAME="MacChanger-$_VERSION_NAME-vc$_VERSION_CODE"
case "$NAME" in
    */*|.|..) die "--name must be a plain file name, not a path: $NAME" ;;
esac

TMP=$(mktemp -d "${TMPDIR:-/tmp}/macchanger-package.XXXXXX") || die "mktemp -d failed"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

STAGE="$TMP/stage"
EXCL_LOG="$TMP/excluded"
mkdir -p "$STAGE/$NAME" || die "cannot create $STAGE/$NAME"
: >"$EXCL_LOG"
# $OUT is created only once the gates below have passed, so a refusal leaves no
# directory (let alone an archive) behind.

# Scratch and verbosity for the hand-off scanner (see check_one); the archive
# built below is itself run through it before this script claims success.
CK_TMP="$TMP/check"
mkdir -p "$CK_TMP" || die "cannot create $CK_TMP"
if [ "$QUIET" = yes ]; then CK_VERBOSE=no; else CK_VERBOSE=yes; fi

# =============================================================================
# What surrounds the tree is not the deliverable, but the audit found a hand-off
# that copied the enclosing directory and re-shipped the removed signing key
# that way.  Say so loudly.  This is a warning, never a refusal: the tree is
# clean and is a legitimate deliverable, and the enclosing directory is not this
# script's to police.
# =============================================================================
_parent=$(CDPATH= cd -- "$ROOT/.." && pwd)
_around=
if [ "$_parent" != "$ROOT" ]; then
    SCAN_SKIP="$ROOT $OUT"
    _around=$(scan_tree "$_parent" | grep '^SECRET')
    SCAN_SKIP=
fi
if [ -n "$_around" ]; then
    printf '\n' >&2
    printf 'package: WARNING - signing material exists OUTSIDE the tree, in %s:\n' "$_parent" >&2
    printf '%s\n' "$_around" | while IFS='	' read -r _a_c _a_r _a_p _a_s; do
        printf '  %s\n    sha256 %s\n' "$_a_p" "$_a_s" >&2
    done
    printf '  The deliverable is this tree (%s) or the archive this script writes - never\n' "$ROOT" >&2
    printf '  the directory around it.  Do not zip, tar or copy %s as a hand-off, and do not\n' "$_parent" >&2
    printf '  pass it to anyone: it holds the key next to the APK.  Verify whatever you do hand\n' >&2
    printf '  over with: sh tools/package.sh --check PATH  (see tools/README.md)\n' >&2
    printf '\n' >&2
fi

# =============================================================================
# Gate 1: no signing key anywhere in the tree.  This is checked on the whole
# tree (not just the payload) because the point is that the key is gone, not
# that it happens to be excluded.
#
# Both tests are applied here, name AND bytes, because a name is not a promise:
# an earlier version of this gate looked only at file names, so the leaked
# PKCS#12 key copied to app/ks.tmp was invisible to it - and invisible to gate
# 3 as well, because keep() excludes *.tmp from the payload, so the archive the
# gate scans never contained it either.  Packaging printed PACKAGE: OK on a
# tree that still held the key, while 'sh tools/package.sh --check .' on the
# same tree correctly failed it: the two now run the same detector over the
# same files, so they cannot disagree.  Everything is scanned, including files
# keep() would not package, and .git is pruned because its objects are
# compressed (check_one covers a git history by listing paths).
# =============================================================================
SCAN_SKIP="$ROOT/.git $OUT"
_tree_findings=$(scan_tree "$ROOT" 2>/dev/null)
SCAN_SKIP=
_keys=$(printf '%s\n' "$_tree_findings" | grep '^SECRET' | cut -f3)
if [ -n "$_keys" ]; then
    printf 'package: signing material found (by name or by content, anywhere in the tree):\n' >&2
    printf '%s\n' "$_tree_findings" | grep '^SECRET' | while IFS='	' read -r _g_c _g_r _g_p _g_s; do
        printf '  %s\n    %s\n    sha256 %s\n' "$_g_p" "$_g_r" "$_g_s"
    done >&2
    printf '  This is the whole tree, not only the files that would be packaged: a key that is\n' >&2
    printf '  excluded from the payload still means the tree must not be handed over as it is.\n' >&2
    refuse "a keystore or key file is present in the tree (found by name or by keystore magic bytes, in any file, including ones the payload excludes; see SECURITY.md)"
fi

# =============================================================================
# The payload list (keep()/payload_list are defined above, next to the hand-off
# scanner, because --check mode uses the same rules to prove freshness).
# =============================================================================
payload_list "$ROOT" "$NAME" >"$TMP/payload.txt"

_payload_n=$(wc -l <"$TMP/payload.txt" | tr -d ' ')
[ "$_payload_n" -gt 0 ] || die "the payload is empty: refusing to package nothing"
[ "$(grep -c '^cli/macchanger.sh$' "$TMP/payload.txt")" = 1 ] || die "cli/macchanger.sh is not in the payload"
[ "$(grep -c '^app/src/com/macchanger/MainActivity.java$' "$TMP/payload.txt")" = 1 ] || die "the Java source is not in the payload"

# =============================================================================
# Gate 2: no literal keystore password in anything that would be packaged.
# The audit's own artifacts quote the old password; they are excluded above and
# are not scanned here, but the script says so rather than hiding it.  The two
# scanner sources (this script and tools/checks/checks.sh) carry the searched
# literal as a grep pattern, which is why they are named here and skipped: every
# other packaged file is scanned, and gate 1 above is what really protects the
# user (a key file cannot be present at all).
# =============================================================================
_pw_hits=
_pw_skipped=
while IFS= read -r _rel; do
    case "$_rel" in
        tools/checks/checks.sh|tools/package.sh)
            _pw_skipped="$_pw_skipped $_rel"
            continue
            ;;
    esac
    if grep -q 'pass: *android' "$ROOT/$_rel" 2>/dev/null; then
        _pw_hits="$_pw_hits $_rel"
    fi
done <"$TMP/payload.txt"
if [ -n "$_pw_hits" ]; then
    refuse "the packaged files still contain the string 'pass:android':$_pw_hits"
fi
_audit_pw=$(grep -rl 'pass: *android' "$ROOT/AUDIT.md" "$ROOT/audit" 2>/dev/null | sed "s|$ROOT/||g" | tr '\n' ' ')
if [ -n "$_audit_pw" ]; then
    say "package: excluded audit artifacts still quote the string (documenting the old defect, not shipping it): $_audit_pw"
fi
if [ -n "$_pw_skipped" ]; then
    say "package: the password scan skipped the scanner sources themselves (they carry the literal as a pattern):$_pw_skipped"
fi

# =============================================================================
# Stage the payload
# =============================================================================
while IFS= read -r _rel; do
    _dst="$STAGE/$NAME/$_rel"
    mkdir -p "$(dirname -- "$_dst")" || die "cannot create $(dirname -- "$_dst")"
    cp -p "$ROOT/$_rel" "$_dst" || die "cannot copy $_rel"
done <"$TMP/payload.txt"

# =============================================================================
# Build the archive (twice, to prove it is reproducible)
# =============================================================================
if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    TAR_REPRO=yes
else
    TAR_REPRO=no
fi

build_archive() { # $1 = output file
    if [ "$TAR_REPRO" = yes ]; then
        ( cd "$STAGE" && tar --sort=name --owner=0 --group=0 --numeric-owner \
              --mtime='@0' --format=gnu -cf - "$NAME" ) | gzip -n -9 >"$1" \
            || die "tar/gzip failed writing $1"
    else
        ( cd "$STAGE" && tar -cf - "$NAME" ) | gzip -n -9 >"$1" \
            || die "tar/gzip failed writing $1"
    fi
}

ARCHIVE="$OUT/$NAME.tar.gz"
mkdir -p "$OUT" || die "cannot create the output directory $OUT"
build_archive "$ARCHIVE" || exit 2
_sha=$(sha256sum <"$ARCHIVE" | awk '{ print $1 }')
[ -n "$_sha" ] || die "cannot hash $ARCHIVE"

# =============================================================================
# Gate 3: the artifact that is about to be called the deliverable is itself
# scanned - member names, extracted bytes and completeness.  Gates 1 and 2
# check the inputs; this checks the output, so an exclusion rule that silently
# stopped working could not put a key or the audit into the archive unnoticed.
# =============================================================================
check_one "$ARCHIVE"
_ck_rc=$?
if [ "$_ck_rc" -ne 0 ]; then
    rm -f "$ARCHIVE"
    # The inventory and the .sha256 describe the archive that has just been
    # removed.  Leaving a previous run's copies behind would make dist/ look
    # like it holds a verified release while holding nothing at all (the
    # inventory would hash and list a file that is not there), so they go too.
    rm -f "$OUT/$NAME.inventory.txt" "$OUT/$NAME.tar.gz.sha256"
    printf 'package: the built archive failed its own hand-off check; removed %s\n' "$ARCHIVE" >&2
    printf 'package: also removed any %s.inventory.txt / %s.tar.gz.sha256 from an earlier run, because they described it\n' "$NAME" "$NAME" >&2
    printf 'PACKAGE: FAILED the archive is not safe to hand off\n'
    exit 1
fi

if [ "$VERIFY" = yes ]; then
    build_archive "$TMP/rebuild.tar.gz" || exit 2
    _sha2=$(sha256sum <"$TMP/rebuild.tar.gz" | awk '{ print $1 }')
    if [ "$_sha" != "$_sha2" ]; then
        rm -f "$ARCHIVE" "$OUT/$NAME.inventory.txt" "$OUT/$NAME.tar.gz.sha256"
        printf 'package: two builds of the same tree differ (%s vs %s)\n' "$_sha" "$_sha2" >&2
        printf 'PACKAGE: FAILED the archive is not reproducible\n'
        exit 1
    fi
fi

# =============================================================================
# Inventory
# =============================================================================
INVENTORY="$OUT/$NAME.inventory.txt"
_size=$(wc -c <"$ARCHIVE" | tr -d ' ')
{
    printf '# %s\n' "$NAME.tar.gz"
    printf '# built from %s\n' "$ROOT"
    printf '# archive SHA-256 : %s\n' "$_sha"
    printf '# archive bytes   : %s\n' "$_size"
    printf '# version         : versionName %s, versionCode %s (app/AndroidManifest.xml)\n' "$_VERSION_NAME" "$_VERSION_CODE"
    printf '# files           : %s\n' "$_payload_n"
    printf '# reproducible    : %s\n' "$([ "$TAR_REPRO" = yes ] && echo "yes (tar --sort=name --mtime=@0 --owner=0 --group=0, gzip -n; verified by building twice)" || echo "no (this tar lacks the GNU reproducibility options)")"
    printf '#\n'
    printf '# SHA-256%62s SIZE  MODE  PATH\n' ''
    while IFS= read -r _rel; do
        _f="$ROOT/$_rel"
        printf '%s  %6s  %s  %s\n' \
            "$(sha256sum <"$_f" | awk '{ print $1 }')" \
            "$(wc -c <"$_f" | tr -d ' ')" \
            "$(stat -c '%a' "$_f")" \
            "$_rel"
    done <"$TMP/payload.txt"
} >"$INVENTORY" || die "cannot write $INVENTORY"

printf '%s  %s\n' "$_sha" "$NAME.tar.gz" >"$OUT/$NAME.tar.gz.sha256"

# =============================================================================
# Verify the archive really contains what the inventory says
# =============================================================================
if [ "$VERIFY" = yes ]; then
    _x="$TMP/extract"
    mkdir -p "$_x" || die "cannot create $_x"
    tar -xzf "$ARCHIVE" -C "$_x" || die "the archive cannot be extracted"
    _xroot="$_x/$NAME"
    [ -d "$_xroot" ] || die "the archive does not contain the $NAME/ directory"
    _count=$(find "$_xroot" -type f | wc -l | tr -d ' ')
    if [ "$_count" != "$_payload_n" ]; then
        printf 'package: the archive holds %s files, the inventory lists %s\n' "$_count" "$_payload_n" >&2
        printf 'PACKAGE: FAILED archive contents do not match the inventory\n'
        exit 1
    fi
    _bad=
    while IFS= read -r _rel; do
        _a="$ROOT/$_rel"
        _b="$_xroot/$_rel"
        [ -f "$_b" ] || { _bad="$_bad missing:$_rel"; continue; }
        [ "$(sha256sum <"$_a" | awk '{ print $1 }')" = "$(sha256sum <"$_b" | awk '{ print $1 }')" ] \
            || _bad="$_bad differs:$_rel"
        [ "$(stat -c '%a' "$_a")" = "$(stat -c '%a' "$_b")" ] \
            || _bad="$_bad mode:$_rel"
    done <"$TMP/payload.txt"
    if [ -n "$_bad" ]; then
        printf 'package: extracted tree does not match the source tree:%s\n' "$_bad" >&2
        printf 'PACKAGE: FAILED verification of the extracted archive\n'
        exit 1
    fi
    for _must in cli/macchanger.sh app/build.sh tools/clitest/clitest.sh tools/checks/checks.sh prebuilt/MacChanger.apk; do
        [ -f "$_xroot/$_must" ] || die "the archive is missing $_must"
    done
    [ -x "$_xroot/cli/macchanger.sh" ] || die "cli/macchanger.sh is not executable inside the archive"
    [ -x "$_xroot/app/build.sh" ] || die "app/build.sh is not executable inside the archive"
fi

# =============================================================================
# Report
# =============================================================================
say "package: root      $ROOT"
say "package: archive   $ARCHIVE"
say "package: inventory $INVENTORY"
say "package: sha256    $OUT/$NAME.tar.gz.sha256"
say "package: version   versionName $_VERSION_NAME, versionCode $_VERSION_CODE"
say "package: contents  $_payload_n file(s), $(wc -c <"$ARCHIVE" | tr -d ' ') bytes of archive"
if [ "$TAR_REPRO" = yes ]; then
    say "package: reproducible yes - two builds produced byte-identical archives"
else
    say "package: reproducible NO - this tar lacks --sort/--mtime/--owner, so the bytes may vary"
fi
say ""
say "package: inventory (sha256 first 16 chars, size, mode, path)"
while IFS= read -r _rel; do
    _f="$ROOT/$_rel"
    printf '  %s  %6s  %s  %s\n' \
        "$(sha256sum <"$_f" | awk '{ print substr($1, 1, 16) }')" \
        "$(wc -c <"$_f" | tr -d ' ')" \
        "$(stat -c '%a' "$_f")" \
        "$_rel"
done <"$TMP/payload.txt"

if [ -s "$EXCL_LOG" ]; then
    say ""
    say "package: excluded ($(wc -l <"$EXCL_LOG" | tr -d ' ') entries)"
    for _reason in "audit or vcs artifact" "nested vcs/audit artifact" "keystore material" "build output or scratch" "build output (only prebuilt/MacChanger.apk ships)"; do
        _n=$(awk -F'\t' -v r="$_reason" '$1 == r { c++ } END { print c + 0 }' "$EXCL_LOG")
        [ "$_n" -gt 0 ] && say "  $_n x $_reason"
    done
fi

say ""
say "package: hand-off  this archive (or $ROOT itself) is the deliverable.  Never zip,"
say "                   tar or copy $_parent - what surrounds the tree is not part of it"
say "                   and, on a machine like this one, holds the signing key.  Verify"
say "                   any artifact before you hand it over, whatever produced it:"
say "                       sh tools/package.sh --check PATH"

printf '\nPACKAGE: OK %s %s\n' "$ARCHIVE" "$_sha"
exit 0
