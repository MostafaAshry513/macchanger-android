# VERIFICATION.md — independent final sign-off

Verifier: final sign-off stage (seventh agent; authored none of the code verified here).
Date of run: 2026-10-07 13:30–13:50 UTC.
Role: re-run every objective gate, re-decide every critical/high blocker against the **current**
code, resolve the size question, and state the residual risk. No file under this tree was
modified by this verification, and the only file it wrote is this one:
`find . -path ./.git -prune -o -type f -newermt '2026-10-07 13:30' -print` returns
`./VERIFICATION.md` and nothing else. Every experiment ran in `/tmp` on copies. Two consequences of
writing it are recorded rather than hidden: it made the packaged archive stale for exactly one file
until I rebuilt it with the tree's own `tools/package.sh` (§1.8), and that rebuild wrote only under
`/root/macchanger-fixed/dist`, which is outside the frozen tree.

## 0. The revision verified

```
$ sha256sum cli/macchanger.sh app/src/com/macchanger/MainActivity.java README.md SECURITY.md \
    app/build.sh tools/checks/checks.sh tools/clitest/clitest.sh tools/package.sh prebuilt/MacChanger.apk
50c5107320f443dd2119c0ff7c88316e83317920334a6c3923cd58b053117ad3  cli/macchanger.sh
b857176fafbbde0c5a01a87e52fb6cf7a575357391cdf8f15bf2ea77e5f08556  app/src/com/macchanger/MainActivity.java
bd2a905bd2371263e5ab590da4016bd7404afa8d18547feedb6329d9b2ae2ea5  README.md
ed4e11c11cd400016cdde203bfb9ed4ab157ecd42ffee2e5f3aad9ba35269f77  SECURITY.md
eb311b0eaf49bd712bbf18f48470a539f39ba5f71196b911b44d0a8d3041cc61  app/build.sh
5b220c40e7c71680bca9edbeef838ec927a54e184f78611aecd776d1e8d017cb  tools/checks/checks.sh
8d10843d78727af09c739ec26dfd23083380b8340489bd14efe110828fbc1d8c  tools/clitest/clitest.sh
32d6fbc03c11253e045fd7d8f9b1c3ad8cdd6faa030be6c335e2dc7abfc45131  tools/package.sh
1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da  prebuilt/MacChanger.apk
```

Every claim below is against these hashes. `git status` at this revision: HEAD `5b15188`
("pristine MacChanger as shipped"), 10 tracked files, 9 modified/added entries and `tools/`
untracked — see §2.2.

## 1. Objective gates — re-run by this verifier, real output

All five shell gates and the APK identity check **pass**. Nothing below is quoted from a
previous verifier.

### 1.1 `sh tools/stubcompile/check.sh` → PASS (exit 0)

```
stubcompile: javac   /usr/bin/javac
stubcompile: stubs   .../tools/stubcompile/stubs (73 source files)
stubcompile: target  .../app/src/com/macchanger/MainActivity.java
stubcompile: ok      48 class file(s) emitted, including com/macchanger/MainActivity.class
STUBCOMPILE: PASS
```

### 1.2 `sh tools/stubcompile/selftest.sh` → PASS (exit 0)

```
selftest: ok    - working-tree MainActivity.java compiles  [rc=0, last='STUBCOMPILE: PASS']
selftest: ok    - pristine baseline MainActivity.java compiles  [rc=0, ...]
selftest: ok    - injected stray '}' is rejected  [rc=1, last='STUBCOMPILE: FAIL']
selftest: ok    - injected undefined symbol is rejected  [rc=1, ...]
selftest: ok    - a method no android.* stub declares is rejected (types are really resolved)
selftest: ok    - a stub method called with the wrong argument type is rejected  [mentions 'setTextSize']
selftest: ok    - injected missing ';' is rejected  [rc=1, ...]
selftest: ok    - a nonexistent target is refused  [rc=1, mentions 'no such Java source']
selftest: all controls behaved as expected
SELFTEST: PASS
```

Negative controls are genuine: they exercise javac rejection, not a stated intention.

### 1.3 `sh tools/clitest/clitest.sh` → PASS

```
INFO: path redirect     : namespace
INFO: A0/A0b skipped: this redirect (namespace) does not use the CLI's own hook
PASS: A1 show is read-only: exit 0, whole device tree byte-identical
PASS: A2 negative control: the A1 assertion FAILS for a command that writes (set)
PASS: A3 restore with no factory image: exit 7, nothing created, target untouched
PASS: A4 restore of a 32-byte image over a 64-byte NVRAM: exit 7, target byte-identical, still 64 bytes
PASS: A5 set with the live MAC absent from the NVRAM: exit 6, target byte-identical, no image captured
PASS: A6 set while the NVRAM holds a locally administered MAC: exit 6, target byte-identical
PASS: A7 'backup' on a multicast live MAC (01:aa:bb:cc:dd:ee): exit 6, target byte-identical, nothing captured
PASS: A7 'backup --assume-factory' on a multicast live MAC: exit 6, target byte-identical, nothing captured
PASS: A7b 'backup' on an all-zero live MAC: exit 6, target byte-identical, nothing captured
PASS: A8 with a fresh lock held by the app, 'set' exits 6 without writing
PASS: A8b an abandoned lock (400 s old) is broken as stale, the write proceeds and the lock is released
PASS: B1 set with the live MAC at offset 40: exit 0, written at 40, the decoy at offset 4 untouched
PASS: B2 the write is 6 bytes in place at the found offset: length still 64, stub driver re-read offset 40
PASS: C1 driver ignores the change: exit 9 while the NVRAM does hold the new MAC
PASS: C2 restore of a good image: exit 0, target == image byte-for-byte, runtime back to the factory MAC
PASS: C3 exit codes match the classes the CLI documents
PASS: C4 all nine refusal/usage runs above left the target byte-identical
clitest: 18 of 18 assertions passed, 0 failed (redirect=namespace, shell=sh, ...)
CLITEST: PASS
```

`sh tools/clitest/clitest.sh --self-test` → `self-test: 3 of 3 checks passed` / `CLITEST: PASS`,
so the suite's own negative control holds.

**The count in the hand-off brief is stale.** The brief says 14/14; the shipped suite runs 18
assertions (A0b, A7 ×2, A7b, A8, A8b were added by the repair round). 18/18 is the current truth.

### 1.4 `sh tools/checks/checks.sh` → PASS (exit 0)

```
PASS [PROOF] no signing key in the tree (C5) - none under .../MacChanger, and none tracked by git
PASS [PROOF] no keystore password anywhere in the shipped tree (C5)
INFO [PROOF] the audit artifacts quote the old password and are excluded (and are not packaged)
INFO [PROOF] the directory around the tree carries no signing key (C5, hand-off boundary) -
     /root/macchanger-fixed/.pristine/app/ks.jks [by name, sha256 91a447f33ebd648d] / _private/ks.jks
PASS [PROOF] the newest archive in dist/ passes the hand-off gate (C5) - MacChanger-1.0-vc1.tar.gz
     sha256 3471b5efe2f35748... - HANDOFF: PASS ... every member byte-identical to /root/macchanger-fixed/MacChanger
     (this hash is the state before this report was written; see 1.8 for the current one)
PASS [HEURISTIC] no shell redirection into a calibration path (C1)
PASS [HEURISTIC] no cp/mv/install onto a calibration path (C1, C4)
PASS [HEURISTIC] every seek= carries a count= (C1, C3, H6) - 3 seek= site(s), every one bounded
PASS [HEURISTIC] no fixed sleep without a state poll on the WiFi-restart path (H7)
PASS [HEURISTIC] the app's restart path waits by polling, not by a literal sleep (H7, app side)
PASS [PROOF] the manifest requests zero permissions and declares no service
PASS [PROOF] the shipped APK's own manifest contains no permission string
PASS [PROOF] the build stamps a per-build versionCode and versionName (M8) - app/build.sh:293, :332, :335
PASS [PROOF] the DEVICE card shows the app's own version (M8, app half)
PASS [PROOF] no line-position parsing of the WiFi status in app/ or cli/ (H5)
PASS [PROOF] the CLI's documented scope matches its code: MediaTek paths only, missing path refused (L1)
PASS [PROOF] the sidecar files the README documents are the ones the CLI writes (L1)
PASS [PROOF] the targetSdk the README states is the one the manifest sets (M5)
PASS [PROOF] every tool under tools/ is named in the README
PASS [PROOF] the README's zero-permission claim matches the manifest
PASS [PROOF] the prebuilt APK's SHA-256 is the one the docs record (M8, C5)
PASS [HEURISTIC] the README still labels the ip-link fallback non-persistent (H8)
PASS [HEURISTIC] the README still states the Android 12+ randomization-detection limit (H5)

checks: 21 passed, 2 informational, 0 failed (23 result line(s))
CHECKS: PASS (21 passed, 2 informational, 0 failed)
```

**The count in the hand-off brief is stale.** The brief says `24/24`; the harness now counts
PASS/INFO/FAIL separately and the real split is 21/2/0. `24 of 24` was the pre-repair summary
that counted 3 informational lines as verified checks; that defect is repaired (see §4, checks.sh).

### 1.5 `sh -n cli/macchanger.sh` → OK (exit 0); `bash -n app/build.sh` → OK (exit 0)

### 1.6 `prebuilt/MacChanger.apk` is byte-identical to the pristine copy

```
1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da  prebuilt/MacChanger.apk
1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da  /root/macchanger-fixed/.pristine/prebuilt/MacChanger.apk
$ cmp prebuilt/MacChanger.apk /root/macchanger-fixed/.pristine/prebuilt/MacChanger.apk ; echo $?
0
```

Nobody rebuilt or re-signed it. The shipped APK is still the pre-fix artifact (it carries
`cmd wifi status 2>/dev/null | head -3`, the H5 defect) and it is still signed by the leaked key —
both are documented in `CHANGELOG.md`, and neither can be fixed by editing this tree.

### 1.7 The distributable is current (this was an open blocker)

```
$ sh tools/package.sh --out /tmp/vfy-pkg/outclean          # from a /tmp copy of the frozen tree
PACKAGE: OK /tmp/vfy-pkg/outclean/MacChanger-1.0-vc1.tar.gz 3471b5efe2f357485e5c6f5275bfb712b318e7b513815d462ed902913ae1f325
$ sha256sum /tmp/vfy-pkg/outclean/*.tar.gz /root/macchanger-fixed/dist/*.tar.gz
3471b5efe2f35748...  /tmp/vfy-pkg/outclean/MacChanger-1.0-vc1.tar.gz
3471b5efe2f35748...  /root/macchanger-fixed/dist/MacChanger-1.0-vc1.tar.gz
IDENTICAL: dist/ is current with the frozen tree
$ sh tools/package.sh --check /root/macchanger-fixed/dist/MacChanger-1.0-vc1.tar.gz
HANDOFF: PASS ... every member byte-identical to /root/macchanger-fixed/MacChanger
```

A freshly built archive is byte-identical to the one in `dist/`, so the "stale dist ships a
superseded revision" blocker is resolved by proof, not by assertion.

### 1.8 The freshness gate fired on my own report — recorded because it matters

Writing this file into the tree is itself a tree change, and `checks.sh` check 2c caught it on the
next run, at 13:41:

```
FAIL [PROOF] the newest archive in dist/ passes the hand-off gate (C5) -
     MacChanger-1.0-vc1.tar.gz sha256 3471b5efe2f35748... - HANDOFF: FAIL ... the archive is stale:
     1 of its member(s) do not match the tree this script belongs to; rebuild it with
     'sh tools/package.sh' rather than handing off a revision nobody verified
checks: 20 passed, 2 informational, 1 failed (23 result line(s))
CHECKS: FAIL (1 of 23 result line(s) failed)
```

The differing member was `VERIFICATION.md` and nothing else. That is the gate behaving exactly as
the repaired `package.sh`/`checks.sh` are supposed to behave, on a real one-file change rather than
a synthetic one, and it is the strongest single piece of evidence in this document that check 2c is
not decorative. I rebuilt the archive with the tree's own remedy — `sh tools/package.sh`, which
writes only under `/root/macchanger-fixed/dist`, outside the frozen tree — and re-ran everything:

```
PACKAGE: OK /root/macchanger-fixed/dist/MacChanger-1.0-vc1.tar.gz 7b56f8f787b7461a62d8b635323a98857463616c024b00b3c3a609ea417fa1f8
$ tar -tzf /root/macchanger-fixed/dist/MacChanger-1.0-vc1.tar.gz | grep -c VERIFICATION.md
1
$ sh tools/package.sh --check /root/macchanger-fixed/dist/MacChanger-1.0-vc1.tar.gz
HANDOFF: PASS (1 of 1 artifact(s) safe to hand off)
checks: 21 passed, 2 informational, 0 failed (23 result line(s))
CHECKS: PASS (21 passed, 2 informational, 0 failed)     [exit 0]
STUBCOMPILE: PASS      SELFTEST: PASS      CLITEST: PASS (18/18)      APK byte-identical to pristine
```

**That rebuild produced `7b56f8f787b7461a62d8b635323a98857463616c024b00b3c3a609ea417fa1f8`, and it
included this file** (the archive was `3471b5ef…` before this report existed). Any further write to
the tree — including a later edit to this report, or a commit-time `CHANGELOG.md` edit — re-stales
it again, and the fix is the same one command.

There is a circularity here worth naming, because the author will hit it. This report lives inside
the tree, so **every edit to this report re-stales `dist/`**; I hit it twice. The order that works
is: finish every write to the tree, *then* run `sh tools/package.sh` once, then run
`sh tools/checks/checks.sh` and touch nothing. Because any hash written into this file would be
invalidated by the next edit to it, the authoritative hash of the shipped archive is not quoted
here — it is in `dist/MacChanger-1.0-vc1.tar.gz.sha256`, which `package.sh` writes beside the
artifact on every run, and `sh tools/package.sh --check <archive>` re-derives it against the tree.
If `checks.sh` reports check 2c FAIL, something was written to the tree after the packaging step;
the remedy is that same one command, not a different diagnosis.

## 2. Critical and high blockers — re-decided against the current code

### 2.1 CRITICAL — "the shipped test-only redirect hook is an unvalidated root-write primitive" → RESOLVED (hook deleted)

The hook is **gone from the script**, not narrowed:

```
$ grep -n 'MACCHANGER' cli/macchanger.sh
130:# hook - MACCHANGER_TEST plus MACCHANGER_DIR/MACCHANGER_NV/MACCHANGER_NET - which
137:# '[ -n "$MACCHANGER_TEST" ]' armed the redirect for MACCHANGER_TEST=0 - so the
$ grep -n '^DIR=\|^NV=\|^NET=' cli/macchanger.sh
53:DIR=/data/adb/macchanger
54:NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
56:NET=/sys/class/net
```

Only two comment lines name the variables, and they record the removal. A/B proof, executed
against a root-owned 0600 victim outside every path the tool knows:

```
=== RECONSTRUCTED PRE-FIX revision (the hook re-inserted verbatim from the blocker report) ===
victim before: 64 bytes mode 600 owner root sha=6b6a67e7...
$ MACCHANGER_TEST=0 MACCHANGER_DIR=... MACCHANGER_NV=$W/victim MACCHANGER_NET=... sh prefix-cli.sh set 02:11:22:33:44:55
macchanger: TEST OVERRIDES ACTIVE: MACCHANGER_DIR=... MACCHANGER_NV=... (not the device paths)
[*] capturing factory image of /tmp/vfy-hook2/victim (MAC field at offset 4 = 04:f9:93:11:36:bf)
[*] saved factory image -> .../dir/WIFI.factory ...
[*] writing NVRAM (/tmp/vfy-hook2/victim) at byte offset(s) 4 ...
victim after : 64 bytes mode 600 owner root sha=b57f2434...
RESULT: *** ROOT-OWNED 0600 VICTIM REWRITTEN, MACCHANGER_TEST=0 ***
 01 00 08 00 02 11 22 33 44 55

=== THE SHIPPED revision, same environment, same command ===
$ MACCHANGER_TEST=0 MACCHANGER_DIR=... MACCHANGER_NV=$W/victim MACCHANGER_NET=... sh cli/macchanger.sh set 02:11:22:33:44:55
error: NVRAM file not found: /mnt/vendor/nvdata/APCFG/APRDEB/WIFI   [rc=4]
RESULT: shipped revision left the victim byte-identical
```

The environment is inert for every value, including the "off" values the old truthiness gate
armed:

```
$ for VAL in 1 0 false off no; do MACCHANGER_TEST=$VAL MACCHANGER_DIR=... MACCHANGER_NV=$W/victim \
    MACCHANGER_NET=... sh cli/macchanger.sh doctor | grep -i override ; done
overrides      : none: /data/adb/macchanger, /mnt/vendor/nvdata/APCFG/APRDEB/WIFI and /sys/class/net
                 are constants of this script and no environment variable can redirect them
     (identical for 1, 0, false, off and no)
```

And the suite's gate has discriminating power — `clitest` was pointed at the reconstructed
pre-fix revision:

```
$ CLI=/tmp/vfy-hook2/prefix-cli.sh sh tools/clitest/clitest.sh --redirect=override
PASS: A0 the test override is gated on MACCHANGER_TEST: without it, MACCHANGER_NV/DIR/NET redirect nothing
FAIL: A0b the hook gate requires the literal value: MACCHANGER_TEST=0 (and any other non-1 value)
      redirects nothing
clitest: 19 of 20 assertions passed, 1 failed ... CLITEST: FAIL
```

The hook is absent from the delivered archive as well: the archive's `cli/macchanger.sh` matches
`MACCHANGER_` only on those same two comment lines, and the archive contains no
`cli/macchanger.test.sh` (the two `test.sh` matches are `clitest.sh` and `selftest.sh`).

The documented safety property is now true of the code, and the documentation was rewritten to
say what the code does (`README.md:524`, `SECURITY.md:199` "Test-only redirects, and why there
are none"). One residual that cannot be settled here is recorded in §7.

### 2.2 HIGH — "the entire fix pass is uncommitted and tools/ is untracked" → **NOT RESOLVED**

```
$ git log --oneline
5b15188 pristine MacChanger as shipped
$ git ls-files | wc -l
10
$ git status --porcelain
 M .gitignore
AM CHANGELOG.md
A  LICENSE
 M README.md
AM SECURITY.md
 M app/AndroidManifest.xml
 M app/build.sh
 M app/src/com/macchanger/MainActivity.java
 M cli/macchanger.sh
?? tools/
$ git diff --stat HEAD | tail -1
 9 files changed, 8665 insertions(+), 426 deletions(-)
$ git show HEAD:app/src/com/macchanger/MainActivity.java | wc -l     # what a clone gets
855
$ git show HEAD:cli/macchanger.sh | wc -l
139
```

This is unchanged by the repair round: a `git clone` of this repository still yields the
pre-fix 855-line `MainActivity.java`, the 139-line CLI with no located write and no precondition
on the backup, no `tools/` (so no gate can even be run), no `LICENSE` text, and `git clean -xdf`
or `git checkout .` destroys the entire fix pass irrecoverably. The tarball path is safe
(§1.7); the git path is not. **Nothing in `README.md` or `CHANGELOG.md` tells a reader that the
fixes are uncommitted.** This is the one blocker of the critical/high set that a repair agent
could have closed and did not.

### 2.3 HIGH — "the CLI accepts a multicast live MAC as the patch anchor and poisons the app's shared record" → RESOLVED (CLI half), and the app half independently confirmed

```
$ sed -n '/^usable_factory_target()/,/^}/p' cli/macchanger.sh
usable_factory_target() { # $1 = MAC -> 0 = plausible station address
    valid_mac "$1" || return 1
    case "$(echo "$1" | tr -d ':')" in
        000000000000|ffffffffffff) return 1;;
    esac
    _first=$(echo "$1" | cut -d: -f1)
    [ $((0x$_first & 1)) -eq 0 ]
}
```

`capture_decide()` (cli/macchanger.sh:747–775) refuses a live MAC that fails it **with no
`--assume-factory` escape**, and `record_factory_txt()` re-checks before persisting. Executed
negative control (guard disabled in a /tmp copy, everything else identical):

```
FAIL: A7  'backup' on an NVRAM whose live MAC is multicast (01:aa:bb:cc:dd:ee): exit 0, target
          byte-identical, no image and no app record captured
FAIL: A7  'backup --assume-factory' on a multicast live MAC: exit 0, ...
FAIL: A7b 'backup' on an all-zero live MAC: exit 0, ...
clitest: 15 of 18 assertions passed, 3 failed ... CLITEST: FAIL
```

with the guard present the same assertions `PASS` (§1.3). The failure text shows the pre-fix
behaviour exactly: exit 0 and a capture.

App half, read in the current source: `isUsableTarget(byte[],int)` rejects bit 0x01 and all-zero /
all-0xFF; `isFactoryCandidate()` adds `!0x02`; `patch()` returns `PATCH_REFUSED` for a multicast,
all-zero or all-0xFF pattern *before* the first `replaceAll` (MainActivity.java:2163, :2170);
`recordableFactory()` gates the pref, the mirror and `factory.txt` (MainActivity.java:2810,
2866, 2957).

### 2.4 HIGH — "a same-size concurrent change between the read and the write is silently overwritten and reported as a verified success" → RESOLVED

`WRITE_SCRIPT` now takes a third stdin line, the pre-image the patch was derived from, and refuses
before `dd` runs:

```
$ sed -n '1451,1463p' app/src/com/macchanger/MainActivity.java
static final String WRITE_SCRIPT =
        "IFS= read -r s; IFS= read -r e; IFS= read -r d; set -- \"$s\" \"$e\" \"$d\"; "
      + "ss=$(stat -c %s -- \"$1\" ...) || ss=$(wc -c < \"$1\" ...); "
      + "ds=$(stat -c %s -- \"$3\" ...) || ds=$(wc -c < \"$3\" ...); "
      + "[ -n \"$ss\" ] && [ -n \"$ds\" ] || { echo wf=size-unreadable; exit 1; }; "
      + "[ \"$ss\" = \"$ds\" ] || { echo \"wf=size-mismatch src=$ss dst=$ds\"; exit 1; }; "
      + "if [ \"$2\" != - ]; then cmp -s -- \"$2\" \"$3\" || { echo wf=stale; exit 1; }; fi; "
      + "dd if=\"$1\" of=\"$3\" bs=4096 conv=notrunc,fsync ..."
```

I extracted the constant from the shipped file myself and executed it with Java's own three-line
protocol against a 512-byte target, with a second writer installing a **different same-size**
image:

```
=== 1. destination still the pre-image ===
wf=ok          rc=0    dst == staged image: YES
=== 2. destination replaced by a same-size DIFFERENT image (the blocker's scenario) ===
wf=stale       rc=1    the other writer's image SURVIVED: YES
=== 3. no pre-image offered ('-') ===
wf=ok          rc=0
=== 4. size mismatch ===
wf=size-mismatch src=3 dst=512   rc=1
=== 5. NEGATIVE CONTROL: only the pre-image clause removed, everything else identical ===
wf=ok          rc=0
 02 11 22 33 44 55
 *** WITHOUT the clause the other writer's same-size image IS silently overwritten and reported as success ***
```

`W_STALE = -1` is carried out of the write and handled so it cannot raise the `worst` counter;
the user sees "nothing was written … another writer changed it while the write was being
prepared" (MainActivity.java:1494, :1604, :4730–4742). All three write sites now pass a real
pre-image: `setMac` carries `origs.get(i)` from the read that produced the patch (:4645, :4728),
restore strategy 1 passes `pOrig.get(i)` (:5101, :5168), and restore strategy 2 passes the live
bytes it read (:5222, :5238).

One residual, stated by the author and confirmed by me in §6: the *lock* is an optimisation and
its stale-break still has a two-winner window; the content gate above is what actually protects
the partition, and it is now unconditional.

### 2.5 HIGH — "checks.sh reports PASS on a tree containing the exact hazards checks 3 and 4 are named after" → RESOLVED

I re-ran the injection first-hand on a scratch copy of the whole tree:

```
$ cat >> cli/macchanger.sh <<'EOF'
cal_fixup() {
    cat "$BAK" | tee "$NV" >/dev/null
    cp "$BAK" /efs/wifi/.mac.info
    cp "$BAK" /persist/wifi/.mac.info
    CAL=$NV; cat "$BAK" > "$CAL"
    install "$BAK" /productinfo/wifi/.mac.info
}
EOF
$ sh tools/checks/checks.sh
FAIL [HEURISTIC] no shell redirection into a calibration path (C1) - ...:1973: cat "$BAK" | tee "$NV" >/dev/null
     ...:1976:    CAL=$NV; cat "$BAK" > "$CAL"
FAIL [HEURISTIC] no cp/mv/install onto a calibration path (C1, C4) - ...:1974: cp "$BAK" /efs/wifi/.mac.info
     ...:1975: cp "$BAK" /persist/wifi/.mac.info
     ...:1977: install "$BAK" /productinfo/wifi/.mac.info
checks: 19 passed, 2 informational, 2 failed (23 result line(s))
CHECKS: FAIL (2 of 23 result line(s) failed)
```

All six hazards are now caught, including the `tee` form and the renamed-variable alias. The
labels are honest: checks 3 and 4 are `HEURISTIC` and each states its blind spot in the PASS line.
Check 8i no longer hard-codes its evidence — with the two `aapt dump badging` gate blocks deleted
from `app/build.sh`:

```
FAIL [PROOF] the build stamps a per-build versionCode and versionName (M8) - app/build.sh passes
     --version-code 1 time(s) and --version-name 1 time(s) to aapt, but never compares them against
     'aapt dump badging' (versionCode gate: 0, versionName gate: 0): an aapt that ignored the flags
     would be packaged silently
```

and `report()` now counts outcomes separately (§1.4). The `*.test.sh` exclusion is moot: that file
is deleted (§2.8).

### 2.6 HIGH — "package.sh prints PACKAGE: OK on a tree that still contains the leaked private key" → RESOLVED

Reproduced with the **real** leaked PKCS#12 (from the pristine snapshot) copied to a
`keep()`-excluded name inside a /tmp copy of the tree:

```
$ sha256sum /root/macchanger-fixed/.pristine/app/ks.jks
91a447f33ebd648d99bd2b26b3daa01d1f07e5faeb52266040b712b227ffae3a
$ cp /root/macchanger-fixed/.pristine/app/ks.jks app/ks.tmp      # magic 30 82 0a 7e 02 01 03 30
$ sh tools/package.sh --out /tmp/vfy-pkg/out
package: signing material found (by name or by content, anywhere in the tree):
  app/ks.tmp
    PKCS#12 keystore contents (PFX header 30 82 .. .. 02 01 03 30 82)
    sha256 91a447f3...ffae3a
  This is the whole tree, not only the files that would be packaged
PACKAGE: REFUSED a keystore or key file is present in the tree ...            [exit 1]
$ ls /tmp/vfy-pkg/out
(no output directory - nothing was produced)
$ sh tools/package.sh --check .                                               [exit 1]
HANDOFF: FAIL . - 1 key finding(s) ...
```

Packaging and `--check` now agree, the refusal happens before anything is written, and the
content detector is applied to the whole tree rather than trusted by name. An innocent name with
keystore magic and a PEM key inside a packaged file are caught too (the author's report, `selfChecks` section;
the mechanism I exercised is the same `sniff_key` path).

### 2.7 HIGH — "README asserts the app never rewrites a whole file — it does" → RESOLVED

```
$ grep -c 'never rewrites a whole file' README.md
0
```

and the replacement is true of the code, which I confirmed by executing the shipped
`WRITE_SCRIPT` (§2.4: `cmp dst src` → equal, i.e. the whole staged image is installed; a
16-byte image against a 64-byte target → `wf=size-mismatch src=16 dst=64`, rc=1). `README.md:195`
now says the app installs a full-size copy of the whole file with only the located MAC windows
changed, and states the guards (size equality, unchanged-size re-read, `cmp` inside the write
invocation, `MIN_IMAGE`).

### 2.8 HIGH — "MainActivity.java comments assert what the code does not do, and credit a gate that does not exist" → PARTIALLY RESOLVED

Resolved:

* dead declarations are gone: `grep -c` for `usableBackup` = 0, `ADB_TXT` = 0; the `isRandomized()`
  wrapper is deleted (only `isRandomizedValue()`, which is declared once and called from three
  places — :3380, :3467, :4926 — remains).
* the false provenance is corrected and the corrected claim **reproduces**. I extracted all ten
  script constants from the Java myself and ran them under both shells:

```
RECORD_SCRIPT  dash -n rc=0  bash -n rc=0  OK        PROBE_SCRIPT ... OK    WRITE_SCRIPT ... OK
SAVE_SCRIPT ... OK   READ_SCRIPT ... OK   EXPORT_SCRIPT ... OK   LOCK_SCRIPT ... OK
RENEW_SCRIPT ... OK  IFACE_SCRIPT ... OK  TEXT_SCRIPT ... OK
GATE: PASS
=== negative control: RECORD_SCRIPT's '};' reduced to '} ' ===
/tmp/vfy-java/consts/RECORD_BROKEN.sh: 1: Syntax error: word unexpected   (dash rc=2, bash rc=2)
```

  and the new comment states plainly that **no such gate exists under `tools/`**
  (`grep -rn 'sh -n' tools/` → 0 hits), which is true.
* the `MIN_IMAGE` comment names only `backupReject`; the "ten lines on stdin" claim is true
  (`SAVE_SCRIPT` has ten `IFS= read -r`, `pushDurable` feeds exactly ten values).

Still wrong, and newly so — the repair introduced **two fresh stale line references while fixing
an old one**, in the same comment (MainActivity.java:96–98):

```
     * Both strings are this file's own. The line number the first sentence used to
     * cite (cli/macchanger.sh:341) is plan_write(), a --dry-run helper, ...
     * MTK sentence below, verbatim, above its own write path (cli/macchanger.sh:914)
$ sed -n '335,345p' cli/macchanger.sh        # line 341 is inside an awk program body
      }{
          for (i = 1; i <= NF; i++) {
$ grep -n '^plan_write()' cli/macchanger.sh
428:plan_write() { # $1=file $2=mac $3=offsets
$ grep -n 'Do NOT touch ip link' cli/macchanger.sh
1097:    # Do NOT touch ip link here - it crashes the WiFi service on MTK.
```

The two substantive claims are true (the CLI really never prints the `RUNTIME_ONLY` sentence:
`grep -ci persistent cli/macchanger.sh` → 2, both banner lines; and the MTK sentence really is
the CLI's own wording). The line numbers are false, because the CLI grew from 1701 to 1969 lines
after the correction was written. Two of the five comments this blocker covered were fixed by
replacing a number with a number. The other half of the required change — **wire an `sh -n` gate
over the script constants into `tools/`** — was not done at all (`tools/` has no such check; the
author's implementation lives in `/tmp`). No behaviour regressed, so this is a documentation and
process defect, not a hazard.

### 2.9 HIGH — "cli/macchanger.test.sh: 522 lines and 143 green assertions wired to nothing" → RESOLVED by retirement

```
$ ls cli/
macchanger.sh
$ grep -rn 'macchanger\.test' . | grep -v '^\./\.git/'
./tools/checks/README.md:156: ... (`cli/macchanger.test.sh`, which this once named, has since been removed from the ...
```

The file is deleted, it is not in the packaged archive, and the single remaining reference records
the removal. The blocker's alternative (port its unique groups into `tools/clitest`) was **not**
done — `T12/T13` interface ambiguity, `T16–T24` panic/recovery, `T27` JSON, `T28` inode
preservation, `T29–T31`, `T33–T35` are now covered by nothing. That is a real coverage loss and it
is recorded in §6, but a green unrun suite was the worse option, as the blocker itself said.

### 2.10 HIGH — "MACCHANGER_TEST=0 turns the calibration-path redirect ON" → RESOLVED

Resolved by removal: there is no gate left to get wrong (§2.1), and `clitest` A0b now exists to
fail any future revision whose gate is truthiness-based (demonstrated above).

## 3. Per-issue verdicts — `audit/PLAN.json`, all 29 ids

`fixed` = the defect is gone and I produced a command or a line-by-line reading that would fail
if it returned. `partially` = the named harm is reduced but something the issue asked for is
still missing. `unverifiable` = cannot be established in this environment.

| id | sev | verdict | evidence / what is left |
|---|---|---|---|
| C1 | critical | **fixed** | `WRITE_SCRIPT` executes (§2.4): source/destination size equality, `MIN_IMAGE` pre-gate, `dd bs=4096 conv=notrunc,fsync`, post-write size re-read, `cmp` of the image in place, `W_PARTIAL` when it cannot be proved. Short image over a long target → `wf=size-mismatch`, rc=1, target untouched. |
| C2 | critical | **fixed** | `saveBackup()` is now called before every `writeRoot()`: setMac :4643→:4728, restore strategy 1 :5161→:5168, strategy 2 :5234→:5238, and a failure aborts the path. The strategy-2 site — which the earlier verifier found writing with *no* pre-image while the dialog promised one — now saves the live bytes and hands them to the write as its expected pre-image. Reading only; not executable here. |
| C3 | critical | **fixed** | `patch()` returns `PATCH_REFUSED` for a multicast/all-zero/all-0xFF pattern before any `replaceAll` (:2163, :2170) and abandons a file above `PATCH_MAX_HITS`, clearing the caller's window list so the refusal log does not name offsets of an unwritten file. |
| C4 | critical | **fixed** | `clitest` A3/A4/A5/A6 PASS (§1.3): no fabricated factory backup, a 32-over-64 image refused with the target byte-identical, a live MAC absent from the NVRAM refused with exit 6, an LA live MAC refused. `ensure_backup()` also refuses symlink/short/digest/foreign records. |
| C5 | critical | **fixed** | no key by name or by content in the tree or in `git log --all` (0 history hits, 0 files found); `checks.sh` checks 1–2 PASS; `app/build.sh:105–116` refuses to build without a keystore and now probes it *before* its `rm -rf`; README/CHANGELOG/SECURITY document the compromise; the prebuilt APK is untouched and still carries the leaked signer, by design. |
| H1 | high | **fixed** | durable record under `/data/adb/macchanger` (`ADB_DIR`, `SAVE_SCRIPT`, `RECORD_SCRIPT`, `loadBackup`), with the app-slot digest now read back (`appsha`, :2555) and verified (:3186–3192). Reading + author's executed round-trip. |
| H2 | high | **fixed** | capture refuses an LA value unless it is explicitly acknowledged, refuses multicast/all-zero/all-0xFF with no escape (§2.3), and `restoreRefusal()`/`isFactoryCandidate()` gate the write; `publishRecord()` writes the recorded provenance verbatim instead of substituting `"typed"` (:3220–3234). |
| H3 | high | **fixed** | the verdict comes from re-reading the partition (`W_VERIFIED`, `windowsVerify`), and `reportNoWrite(probe, unreadable, …)` now carries the unreadable flag at both call sites (4790, 5278) so "present but not usable by root" is no longer reported as "no MAC match". |
| H4 | high | **fixed** | every root command runs on a deadline (`MS_DEFAULT` 15 s, `MS_BIG_READ` 60 s, `MS_WRITE` 120 s) with a watchdog kill; the killed-`dd` case leaves the file at full length (author's executed test); `destroyForcibly` is now reached through reflection for minSdk 21 and the `merge=false` stderr pipe is drained. |
| H5 | high | **fixed** | no line-position parsing anywhere (`\| head -N`, bare `head -N`, `1,3p`): checks.sh PASS; the status is parsed by content. |
| H6 | high | **fixed** | the located write is proven by execution: `clitest` B1/B2 — with the live MAC at offset 40 the write lands at 40, the decoy at offset 4 is untouched, length stays 64, and the stub driver re-reads offset 40; `checks.sh` check 5 confirms all 3 `seek=` sites carry `count=`. |
| H7 | high | **fixed** | `reinit_wifi()` is a bounded poll, not sleeps (checks 6/6b PASS, only 0.5 s poll intervals); the app polls too; and the CLI now distinguishes the unverified outcome — executed, `wifi` on a ROM with no state source exits **9** (was 0) while `set` in the same state behaves as documented. |
| H8 | high | **fixed** | the ip-link fallback is opt-in (`runtimeAllowed`), never produces a success verdict, and README still labels it non-persistent (checks PASS). |
| H9 | high | **fixed** | `app/build.sh` dies on a javac failure with the diagnostic and no `BUILT:` line (author executed both directions, including the pristine control that prints `BUILT:` on the same error). |
| M1 | medium | **fixed** | `isRandomizedValue()` + `privacyValue()` handle the 0–3 space and `AUTO=3`; the dead `isRandomized()` wrapper the issue named is deleted. |
| M2 | medium | **partially** | both front ends now take the same `mkdir /data/adb/macchanger/lock` with the same 300 s staleness rule: `clitest` A8/A8b PASS, and I executed the app's own shipped `LOCK_SCRIPT`/`RENEW_SCRIPT`/`RELEASE_SCRIPT` against a scratch directory (fresh `lock=ok`; second taker `lock=busy`; aged → `lock=stale` with the owner handed over; displaced owner's renew `lock=lost`; displaced owner's release `lock=not-ours` with the new owner's lock intact — while the pre-repair release, as a negative control, deletes it). **Left open:** the stale-break is not atomic (see §6, measured 2 of 20 rounds with two winners at 8 breakers), so mutual exclusion is strongly narrowed, not proven. The design's actual safety property is `W_STALE`, which is unconditional. |
| M3 | medium | **fixed** | the log cap is 50 and the expanded view shows every retained line (`log()`, `renderLog()`), so an operation touching several paths is no longer truncated out of the only record kept. |
| M4 | medium | **fixed** | four outcomes with the unreadable flag threaded through (H3). |
| M5 | medium | **fixed** | vendor detection, an unsupported-device verdict, and the manifest `targetSdk` matching the README (checks PASS). |
| M6 | medium | **fixed** | interface detection with ambiguity/`--iface` handling; the CLI exits 5 rather than writing blind; and the regression the fresh hunt found — a failed resolution cached for the process lifetime — is closed (`retryIfaceIfUnresolved()` at :3758, :4357, :4530, :5003). |
| M7 | medium | **fixed** | `getRuntimeMac()` and the other reader validate with `isMac()` and return `""` on shell failure, so failure text is no longer rendered as the MAC. |
| M8 | medium | **fixed** | versionCode/versionName are stamped and read back from the packaged manifest (`app/build.sh:293, :332, :335`); `versionName` carries the git revision and a `-dirty` suffix driven by `git status --porcelain`; checks 8i PASS, and FAILs when the gate lines are deleted (§2.5). |
| M9 | medium | **partially** | the capability exists under different names (`tools/checks`, `tools/clitest`, `tools/stubcompile`, `tools/package.sh`, each with its own negative control) and the false claim about `stubcompile` asserting invariants is corrected. **The specific harness does not exist:** `find tools -name '*.java' -not -path '*/stubs/*'` → 0, `tools/extract_logic.py` and `tools/logic_test.java` are absent, and `check.sh` only runs `javac`. The 11 acceptance criteria that require *executed* Java logic (C2, C3, H1, H2, H3, H5, H7, M1, M2, M9, L6) still cannot be run by anyone from this tree. `audit/PLAN.json` M9 also still names `verify.sh`, `extract_logic.py`, `logic_test.java`, `cli_test.sh`, `readme_claims_test.sh`; none exist. |
| L1 | low | **partially** | every doc-vs-code check in `checks.sh` PASSes and the README's claims are now accurate about the whole-file install, the record formats, the encodings and `--assume-factory`. **Still open:** the CLI accepts 8 aliases while `help` documents 2 and the README 1 — `check`, `diag`, `status`, `save`, `rand`, `reset` are emitted capability with no documentation, and `check` collides in meaning with `tools/checks/checks.sh`; and the parser still lets a trailing positional replace the command argument (executed: `set 12:34:56:78:9a:bc EXTRA` → `error: invalid MAC address: EXTRA`, exit 1; `show EXTRA1 EXTRA2` → accepted, rc 0). |
| L2 | low | **fixed** | `LICENSE` is present and tracked, `SECURITY.md` and `CHANGELOG.md` exist, and the version stamp is per-build with the source revision in `versionName`. |
| L3 | low | **fixed** | `README.md:19` "Intended use and authorization" ("devices you own and networks you are authorized to"), cross-referenced from `SECURITY.md:74`. |
| L4 | low | **fixed** | `random` validates its own output and a missing busybox exits 3 immediately for every subcommand; `checks.sh` exercises the applet-bounded `seek=` sites. |
| L5 | low | **fixed** | `FLAG_SECURE` is set on the window (MainActivity.java:257–260). |
| L6 | low | **fixed** | `lastRoot` is consumed, `checkRoot(boolean)` is live and the no-arg dead overload is gone. |

## 4. Other findings raised by the six verifiers — dispositions

Repaired and confirmed by me (method in brackets):

* **Stale `dist/` archive endorsed as PASS** — resolved; a fresh build is byte-identical (§1.7)
  [executed].
* **`checks.sh` counted INFO lines as checks** ("24 of 24" for 21 checks) — resolved; the summary
  is now `21 passed, 2 informational, 0 failed` [executed].
* **`show` never called `require_nvram`** — resolved; with the NVRAM absent `show --json` and
  `show` both exit 4 with `error: NVRAM file not found`, and no `nvram_bytes: ""` is emitted
  [executed in a private mount namespace]. `doctor` remains deliberately exempt (verdict `no`).
* **`doctor`/`set` accepted a foreign-tagged record** — resolved; `doctor` prints
  "recorded source … [NOT … - a foreign image, refused by 'panic' and 'set']", `verdict: no` and a
  blocker line, and `set` exits 7 with the NVRAM byte-identical, consistent with `panic` [executed].
* **Unverified value called "factory NVRAM MAC" / "not spoofed"** — resolved; `restore` prints
  "recorded NVRAM MAC re-read … (offset 4, ASSUMED - unverified)", `doctor` shows
  "recorded MAC … [offset 4 ASSUMED, unverified]" and "so 'not spoofed' cannot be concluded from
  it", with `verdict: no` plus two blockers [executed]. **Residual:** `restore` still installs that
  locally administered image and exits 0 — see §6.
* **`wifi --dry-run` toggled the radio for real** — resolved; it exits 1 with
  "--dry-run is not available for 'wifi': restarting the radio is the whole command" before any
  `svc` call [executed].
* **`wifi` reported an unobservable restart as success** — resolved; exit 9 [executed].
* **The app's cross-process lock was one-sided** — resolved for the lock itself; the CLI takes it
  before it reads the NVRAM and honours `lock=busy` with exit 6 and no write [executed, plus
  `clitest` A8/A8b].
* **`WIFI.factory.app.sha256` written and never verified** — resolved; `RECORD_SCRIPT` emits
  `appsha` and `loadBackup`'s own-slot branch verifies it [read].
* **"Check NVRAM (read-only)" wrote files** — resolved; the button is `Check NVRAM` and the code
  comment states exactly what the scan may write (`checkBtn`, MainActivity.java:603–610) [read].
* **Interface resolution cached as unresolvable for the process lifetime** — resolved (see M6).
* **`releaseLock` deleted a lock it did not own; the stale break was not atomic; no renewal during
  the write phase** — resolved as far as it can be: owner token checked before removal (executed,
  with the pre-repair `rm -rf` as the negative control), break via rename + mkdir + token re-check,
  and `renewLock()` called before every `writeRoot` (:4722, :5151, :5207) failing closed.
* **restore strategy 2 wrote with no pre-image** — resolved (C2).
* **`publishRecord` invented the `"typed"` provenance** — resolved (H2).
* **A pre-fix, provenance-less `factory` pref could not be corrected** — resolved the way the
  blocker allowed: `saveTypedFactory` may now replace a record with no provenance. The other
  option (reordering the resolver so a durable record outranks a provenance-less pref) was
  deliberately not taken; the user can correct it from the UI.
* **`restore` hardcoded `unreadable=false`** — resolved (M4).
* **`checks.sh` false-verdict gates 3/4 and 8i** — resolved (§2.5).
* **`package.sh` key gate by name only** — resolved (§2.6).
* **`build.sh` deleted the previous signed APK before opening the keystore** — resolved; the
  keystore is probed at :184–188, the `rm -rf` is at :199, `OUT` that is an existing directory is
  refused at :81–88 [read + the neighbour paths executed by the author].
* **`build.sh` icon gate accepted `icon=''`** — resolved; I tested the gate expression directly:
  `icon=''` → FAIL (the pre-repair pattern → PASS), `icon='res/…png'` → PASS,
  `application-icon-160:` → PASS.
* **`build.sh` `-dirty` missed untracked/staged-only changes; `hash256()` masked a failed hasher** —
  resolved; `git status --porcelain` at :287, and `hash256` failures now `die` at :225 and :377.
* **Documentation items** — `WIFI.factory.path` documented as the bare path with the warning that
  appending `@0x` makes `panic` refuse the image (:668); `--assume-factory` no longer described as
  "the only way through"; `stubcompile` no longer described as asserting invariants (0 matches for
  "asserts the write-path"); the `clitest` README no longer claims a fixed sleep or a
  `checks.sh` FAIL that cannot happen; the `checks` README's stale 20/4/0 block and its 8j finding
  are replaced with the measured result and the correction is recorded; `SECURITY.md`'s
  `configChanges` claim is replaced by the mechanism actually shipped, with the grep evidence.
* **`PLAN.json` M9 names tools that do not exist** — still true; `PLAN.json` is a read-only record
  and rewriting the plan of record is not a repair a verification stage should make unilaterally.

Open, low, and confirmed still open (I reproduced them; no repair touched them):

* The argument parser still takes the **last** positional as the command argument (L1) [executed].
* Six CLI aliases remain undocumented and two of them collide with the names of `tools/` scripts
  (L1) [read].
* `CHANGELOG.md`'s "Not yet released" list still omits the release's most visible additions —
  `doctor`, `backup`, `panic`, `--json`, `--dry-run`, the whole `tools/` harness, the
  cross-process lock and the removal of the test hook [read].
* `README.md:633` still states the absolute "never writes unless the bytes at the matched offset
  are the MAC the driver is actually using", while `panic` and the app's saved-image install are
  documented exceptions elsewhere in the same file [read].
* The README documents one privacy-row state while the code renders five plus an
  "on N of M saved networks" summary; and it names a **FACTORY MAC** row/field while the card is
  titled **FACTORY RECORD** (`MainActivity.java:502`, button `Save factory`) [read].
* `SECURITY.md:105` and `:124` still spell the leaked key's path identically (`app/ks.jks`) for
  two different directories — the as-shipped snapshot and this tree [read].
* `app/build.sh` passes the keystore password in argv, visible through `/proc/<pid>/cmdline`
  [read; developer-side script, informational].
* ~50 lines of duplicated UI boilerplate in `MainActivity.java`: three near-identical `EditText`
  setup blocks (`setHintTextColor(0x8AFFFFFF)` at :510, :548, :625; `round(0xFF111116, …)` at
  :516, :554, :631 — while `C_BG2 = 0xFF111116` is declared at :79), and six copies of the
  three-line `new LinearLayout.LayoutParams(-1, -2)` shape; plus 46 anonymous-class constructions
  [measured by this verifier].
* `android:icon="@android:drawable/ic_menu_manage"` cannot be resolved here — no `aapt`, no
  `android.jar`; unverifiable, and disclosed by the tool's own README.
* `versionCode` is a date (`VC=${VERSION_CODE:-$(date +%Y%m%d)}`), so two different-source builds
  made the same day share a versionCode; they differ only in `versionName` and bytes [read].

## 5. The size question — resolved, with the numbers I measured

Measured by this verifier (`wc -l` and a script that classifies blank / `//`,`/*`,`*` / code, and
for shell `#` / code):

| file | total lines | code | comment | blank | comment share of non-blank |
|---|---|---|---|---|---|
| `app/src/com/macchanger/MainActivity.java` (current) | 5687 | 3444 | 1926 | 317 | **35.9 %** |
| `app/src/com/macchanger/MainActivity.java` (pristine) | 855 | 750 | 21 | 84 | 2.7 % |
| `cli/macchanger.sh` (current) | 1969 | 1441 | 426 | 102 | **22.8 %** |
| `cli/macchanger.sh` (pristine) | 139 | 105 | 11 | 23 | 9.5 % |
| `README.md` (current) | 873 | 686 | 75 | 112 | 9.9 % |
| `README.md` (pristine) | 116 | 82 | 1 | 33 | 1.2 % |

Growth: `MainActivity.java` **855 → 5687 lines (6.7×)**, of which code **750 → 3444 (4.6×)**;
`cli/macchanger.sh` **139 → 1969 (14.2×)**, code **105 → 1441 (13.7×)**. Also measured in the
Java file: 46 anonymous-class constructions, 252 code lines indented 24 spaces or more, and a
crude 4-line-block repeat scan that reports 37 repeats — which I checked, because "big but not
copy-pasted" is the load-bearing argument here, and all 37 are closing-brace / `catch (Throwable t)`
/ `return;` tail shapes, not duplicated logic. That is my own measurement and it does not support
"zero repeats" as strongly as a normalised scan would; what it does support is that there is no
large copy-pasted block.

**My verdict, per file.**

`MainActivity.java`: the code growth is justified by requirements and I can point at each block —
the durable record (H1), the provenance gate and the usable-address predicate (H2), the read-back
verdict (H3), the shell deadlines and watchdog (H4), the semantic status parser (H5), the four
outcomes (M4), the vendor table (M5), the lock plus the confirmation dialog (M2), the retained
cards and rotation handling, and the version row (M8). None of it is padding. The **comment**
growth is the part I would cut: 35.9 % of non-blank lines, an order of magnitude denser than the
pristine file's 2.7 %, and it is *load-bearing* because no Java can be executed here — which
is exactly why prose that cites line numbers is a liability rather than an asset. Two of the five
comments this round set out to fix were "fixed" into new false line numbers (§2.8) inside a single
repair round. **Concrete trim list, in order:** (1) delete every hard-coded line number from the
prose and name symbols or greppable literals instead — this is the highest-value change and it is
mechanical; (2) move the "an earlier revision of X did Y" narrative into `CHANGELOG.md`, where it
is already being written; (3) extract the six large anonymous `Runnable` bodies into private
methods (measured: they capture 0–1 locals and touch 0–2 of 43 instance fields, so the whole
extraction needs about two parameters); (4) collapse the duplicated UI boilerplate behind
`styleInput(...)`/`lp(...)` (~50 lines to ~25). I would **not** split the file before a device run:
`app/build.sh:264` does `find src -name '*.java'`, so sibling classes need no build change, but
`tools/stubcompile/check.sh` compiles exactly `$TARGET` (line 24, 76) against stubs only — a split
breaks the one gate that proves the Java compiles at all unless `check.sh` is taught to compile
the sibling sources, and that refactor would then be verified by nothing but `javac`.

`cli/macchanger.sh`: 14.2× is the number that needs defending, and about three quarters of it is
justified — the located write (H6), the backup-as-precondition and the foreign/damaged/short
refusals (C4), the same lock as the app (M2), interface detection (M6), the exit-code classes,
`doctor`/`panic`/`backup`, `--json`/`--dry-run` and the honest labels. Three parts are **not**
justified and should be trimmed: (a) the alias spellings — 8 canonical commands plus 8 aliases,
6 of them undocumented, two of them (`check`, `selftest`) colliding with differently-meaning
`tools/` scripts; nothing depends on them; (b) the narrative comment history (~40 % of its 426
comment lines) in a root-executed script; (c) the duplicated lock protocol, now maintained
independently in shell and in Java string constants, with no test that runs both halves against
one another (`clitest` A8 hand-builds the app's lock; nothing checks the two implementations stay
in step). The test-only hook is **not** on this list: it is gone, and the redirect it provided is
now done from outside the process by `tools/clitest/sandbox.sh` under a private mount namespace,
which is strictly better and needs no cooperation from the shipped script.

## 6. Residual risks

1. **The fix pass is uncommitted (§2.2).** A `git clone` is the vulnerable pre-fix tree, `tools/`
   is untracked, and `git clean -xdf` destroys the work. Nothing in the documentation says so.
   This is the one remaining high blocker.
2. **The stale-break in the lock is narrowed, not atomic.** `mkdir` cannot be made a
   compare-and-swap in shell. Measured by this verifier with 8 simultaneous breakers on one aged
   lock: 2 of 20 rounds produced more than one `lock=stale` winner (the author measured 2 of 40).
   Consequence: two writers can, rarely, both believe they hold the lock. The safety property does
   not rest on it — `WRITE_SCRIPT`'s pre-image gate refuses the loser's install with `wf=stale` and
   writes nothing (§2.4) — but the *documentation* in both front ends should keep saying the lock
   is an optimisation, as the Java comments already do.
3. **`restore`/`panic` still install a recorded image whose value the tool itself refuses to call a
   factory MAC.** Executed: with an `?4` (ASSUMED) record holding `02:aa:bb:cc:dd:ee`, `restore`
   prints the honest label, exits 0, and the NVRAM changes to that value; `doctor` on the same
   record says `verdict: no` and lists it as a blocker. A caller who checks only the exit status is
   still told "success" for a write the tool has just said it cannot justify. The repair chose
   labelling plus a doctor blocker; I consider the exit status the load-bearing half, and would
   either return `EX_APPLY`/a distinct code here or require an explicit acknowledgement flag.
4. **The `tools/clitest` port of the retired suite never happened (§2.9).** Interface ambiguity,
   panic/recovery, JSON and inode-preservation cases are now unguarded by any suite.
5. **No executed-Java gate exists (M9).** `stubcompile` proves compilation, nothing more, and
   `README`/`tools/README` now say so. Everything in this app that cannot be exercised by
   `javac` — the lock protocol, the ask/answer handshake, the ten `static volatile` fields, the
   rotation path, `Process` handling — is verified by reading alone.
6. **The lock's CLI half and the app's half are two implementations of one protocol.** They agree
   today (same path, same `ts`/`owner` files, same 300 s), verified by execution in both
   directions; nothing in the tree detects future drift.
7. **Line-number citations in prose cannot survive this rate of change.** Demonstrated twice in a
   single round (§2.8). Any comment that must be true should name a symbol.
8. **`dist/` goes stale the moment anyone edits the tree.** `tools/package.sh` now detects this
   (`check 2c` FAILs with the differing members) and I demonstrated it on a real one-file change —
   this report itself (§1.8). The archive is current as of this run
   (containing this file, `HANDOFF: PASS`), but the last writer must run
   `sh tools/package.sh` again after any further edit, and the `CHANGELOG.md` edit that item 1 of
   the final verdict requires will re-stale it.
9. **`prebuilt/MacChanger.apk` is the pre-fix artifact, signed by the leaked key.** It must stay
   byte-identical (rule), so it must never be presented as the fixed build — the docs say this and
   `versionName`/`versionCode` differ, but a user who installs it gets the audited defects.

## 7. Things that can only be confirmed on a real device

Each of these is a step someone can actually perform. None of them can be settled in this
container, and none of them should be reported as verified until they are.

1. **`W_STALE` end to end (the §2.4 fix).** On a rooted phone, open the app, start a Set MAC that
   opens the confirmation dialog, and while the dialog is up replace the calibration file with a
   **different file of the same size** (`dd if=/sdcard/other bs=1 count=<size> of=<NV>` from a
   second shell, keeping the length identical). Then confirm the dialog. Expect: "nothing was
   written … another writer changed it while the write was being prepared", no `dd`, and the other
   writer's bytes still on the partition (`od -An -tx1 -j<off> -N6 <NV>`). The shell half is
   executed here; the Java half is read only.
2. **Lock interop with Magisk's `su`.** With the CLI holding `/data/adb/macchanger/lock`
   (`mkdir /data/adb/macchanger/lock; date +%s > /data/adb/macchanger/lock/ts; echo x > /data/adb/macchanger/lock/owner`),
   start Set MAC in the app and confirm it refuses rather than writing; then age the timestamps by
   more than 300 s and confirm both writers proceed one at a time. The CLI half is proven here; the
   app half needs the device.
3. **Whether Magisk's `su`/`su -c` passes the caller's environment through** the way stock
   util-linux `su` 2.37.2 does (measured here:
   `MACCHANGER_TEST=0 MACCHANGER_NV=/tmp/victim su -s /bin/sh nobody -c 'echo $MACCHANGER_NV'` →
   the variable arrives). The hook is gone, so this is now a property of the platform rather than
   of this tool, but it is what made the critical blocker reachable and it is still unmeasured on
   Android: `su -c 'env | grep ^MACCHANGER'`.
4. **`Process.destroyForcibly()` on API 21–25.** Install and launch on Android 5.1/6/7, make `su`
   hang (e.g. a `su` wrapper that sleeps forever), trigger the deadline so `kill()` runs, and
   confirm the activity survives and reaches the `destroy()` fallback instead of dying with a
   `NoSuchMethodError`/`VerifyError`.
5. **The icon reference builds.** `app/AndroidManifest.xml:10` uses
   `@android:drawable/ic_menu_manage`; run `./build.sh` on the Termux device and confirm step
   `[4/8]` package succeeds and `aapt dump badging` shows a non-empty `icon='…'`.
6. **The export path's resulting mode and ownership.** Press **Export record** and check
   `ls -l /sdcard/Download/WIFI.factory*` plus opening one from a non-root file manager — toybox
   `cp` without `-p` and the FUSE/uid mapping decide this, and neither exists here.
7. **A full `./build.sh` run.** Compile → dex → package → align → sign → all 16 `die "gate:` checks,
   with the real `aapt`/`d8`/`zipalign`/`apksigner` and the maintainer's key: confirm the printed
   APK SHA-256 and signer certificate SHA-256, then turn the CHANGELOG's "Not yet released"
   section into a real entry keyed to the stamped `versionCode` and the source commit.
8. **The whole app-side behaviour on one real MTK device**: that the durable record survives
   `pm uninstall`/Clear data, that Restore actually puts the factory MAC back and the driver
   re-reads it, and that the confirmation dialog survives a rotation without a second worker
   (the property is now held by the static `busy`/`current` fields rather than by
   `configChanges`, which the manifest does not declare).

## 8. What this project's testing does NOT cover — the honest closing statement

* **No Java is ever executed by any gate.** `tools/stubcompile/check.sh` compiles
  `MainActivity.java` against 73 hand-written `android.*` stubs and asserts `javac` exit 0 plus the
  presence of `MainActivity.class`. It does not run a line of it, does not read the manifest,
  does not check API levels, and its stubs are a model of Android, not Android. The Java half of
  every fix in this document is therefore verified **by reading** — no Java method was executed by
  me or by any gate. What I *could* execute is the shell that the Java hands to root: I extracted
  the script constants verbatim from the Java literals and ran them (`WRITE_SCRIPT` for the
  write-path gate and the TOCTOU refusal, `LOCK_SCRIPT`/`RENEW_SCRIPT`/`RELEASE_SCRIPT` for the
  lock protocol, all ten through `sh -n`). That is a real execution of a real artifact this app
  ships, but it is not the app: nothing that depends on `Activity`, threading, rotation, `SharedPreferences`, `Process`, `Parcel`, resources or the
  Android runtime has been executed by anyone at any stage of this project.
* **Nothing has ever run on Android, in an emulator, or against a real MTK `APCFG/APRDEB/WIFI`.**
  Every "it writes"/"it restores" claim is a claim about a synthetic 64- or 512-byte image under a
  private mount namespace on Linux, or about a shell script driven by stubs.
* **The APK is never built, installed or run here** — no `aapt`, `d8`, `zipalign`, `apksigner`,
  no SDK, no device — so the shipped `prebuilt/MacChanger.apk` is verified only as *identical to
  the pristine copy* and as a container (one activity, zero permissions, versionCode 1, the leaked
  signer). Its behaviour is unverified by construction: it is the pre-fix build.
* **The gates are pattern checks, and the harness says so.** `checks.sh` labels each line PROOF or
  HEURISTIC; the two write-path checks are greps with a fixed vocabulary and stated blind spots
  (a path composed at runtime, a helper command not in the vocabulary, a name renamed twice, a
  legitimate `cp $NV elsewhere`), and its `[PROOF]` label means "this exact pattern is absent
  from the files scanned", not "this hazard is absent". `checks.sh` proves nothing about
  behaviour.
* **`clitest`'s 18 assertions are the only behavioural tests of the CLI, and they run against a
  stub `svc`, a synthetic NVRAM and a stub interface.** They cannot judge whether a real ROM's
  `svc wifi enable` reloads the driver, whether `cmd wifi status` says what the parser expects on
  a given vendor ROM, or whether toybox `mkdir`/`stat`/`date` behave as the lock assumes (all
  three are already used by the app's own lock script).
* **Coverage that was deliberately given up:** the retired `cli/macchanger.test.sh` (143
  assertions) was never run by any gate and its unique groups were not ported, so interface
  ambiguity, panic/recovery, JSON and inode-preservation cases are now untested. The `M9`
  extract-and-run Java harness does not exist, so the 11 acceptance criteria that need executed
  Java logic remain unexecuted.
* **Nothing checks that the two front ends stay in step.** The app and the CLI implement the same
  MAC predicates, the same record format and the same lock in two languages with no shared test;
  the divergence found in this audit (one encoding versus four, multicast accepted on one side)
  was found by a human reading both, not by a gate.
* **The documentation is verified only where a check greps for it.** Seven doc-vs-code checks
  exist; the README is 873 lines and the rest of it — including the two stale line numbers this
  verification found in Java prose — is maintained by hand.

## 9. Final verdict

**Do not hand this tree over over git yet. Everything else is ready for the author to build and
use.**

All six objective gates pass on my own run, `prebuilt/MacChanger.apk` is byte-identical to the
pristine copy, the delivered archive is byte-identical to a fresh build of the frozen tree, the
critical redirect-hook blocker is genuinely resolved (the hook is deleted, A/B-proved against a
reconstructed pre-fix revision, and pinned by an assertion that fails on that revision), and six
of the eight high blockers are resolved at the root cause with commands that would fail if the fix
were absent — including the write-path TOCTOU, which is closed at the primitive (`cmp` before
`dd`, `wf=stale`) rather than by the lock, and the two false-verdict gates (`checks.sh`,
`package.sh`), which I reproduced as failures and then as refusals. Of the remaining two high
blockers, the false Java prose is partially resolved (the dead code is gone and the substantive
claim now reproduces, but the repair introduced two fresh stale line numbers and the `sh -n` gate
it asks for still does not exist), and **the uncommitted fix pass is untouched.**

What must happen first:

1. **Commit the fix pass, including `tools/`,** and say in `CHANGELOG.md` which commit the
   corrections live in. Without this, the deliverable over git is the vulnerable pre-fix tree and
   `git clean -xdf` is unrecoverable. Nothing else in this list matters as much.
2. **Re-run `sh tools/package.sh` after the last write to the tree** (one command) so `dist/`
   matches whatever the committed revision is; the freshness gate now catches it either way.
3. **Fix or accept the four small honesty items** that are cheap and currently false: the two
   stale line numbers in `MainActivity.java:96–98`, the `restore` exit status on an unverified
   locally administered record (residual 3 in §6), the six undocumented CLI aliases plus the last-positional
   parser behaviour, and `CHANGELOG.md`'s incomplete "Not yet released" list.
4. **Then build on-device and work through §7.** The first item there — `W_STALE` with two real
   writers during the confirmation dialog — is the one result that would change my confidence in
   the most safety-critical repair in this round, and it takes two shell commands and one dialog.
