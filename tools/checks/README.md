# checks — the regression checks for the claims this audit corrected

The audit behind this project found specific mistakes: a signing key shipped with the APK,
a password written into the docs, a shell redirection that truncated the calibration file,
a `cp` over a partition path, an unbounded `dd seek=`, blind sleeps on the WiFi-restart
path, and documentation claiming things the code did not do. Each of those was fixed once.
`checks.sh` is what keeps them fixed: one line per check, `PASS` / `FAIL` / `INFO`, with
the file and line that tripped it.

```
sh tools/checks/checks.sh [ROOT]        # ROOT defaults to the repository root
```

It exits 0 when no check FAILs, 1 when any check FAILs, and 2 on a usage error. The last
line separates the three outcomes rather than reporting one opaque total, so an `INFO` line
cannot be read as a check that passed:

```
checks: <n> passed, <n> informational, <n> failed (<n> result line(s))
CHECKS: PASS (<n> passed, <n> informational, 0 failed)
```

One measured example, on this tree with `dist/` emptied:

```
checks: 20 passed, 3 informational, 0 failed (23 result line(s))
CHECKS: PASS (20 passed, 3 informational, 0 failed)
```

That is the shape of the line. **The middle number moves with the state of `dist/` and of
the tree, so do not treat any pasted tuple here as a fixture** — the stable fact is that
`0 failed`. In particular the check-2c line reports one of three things depending only on
`dist/`:

| `dist/` holds | 2c line | effect on the totals |
| --- | --- | --- |
| no `.tar.gz` at all | `INFO … no .tar.gz in <dist>: run 'sh tools/package.sh' to build one and this check will scan it` | `+1 informational` |
| an archive that is not this tree's current output | `FAIL … HANDOFF: FAIL … the archive is stale: N of its member(s) do not match …` | `+1 failed`, exit 1 |
| an archive built from this tree | `PASS … <name> sha256 <hash> - HANDOFF: PASS <path> - no signing key … (tools/package.sh --check, exit 0)` | `+1 passed` |

So the green run measured on this tree, whose `dist/` had been emptied, is
`20 passed, 3 informational, 0 failed`; building the archive
(`sh tools/package.sh`) turns the same tree green at `21 passed, 2 informational, 0 failed`.
Both are correct and neither is a regression — only the `FAIL` row is a defect, and its
remedy is the command the line names.

`tools/checks/checks.sh` keeps one counter per outcome plus a line count, and the two
numbers in the summary are those counters — the previous version incremented a single
counter for every line, so three `INFO` lines that asserted nothing were reported as
"CHECKS: PASS (24 of 24)" and the quoted figure read as 24 verified claims.

## Proofs and heuristics

Every line is tagged, because the difference matters:

* **[PROOF]** — a deterministic fact about the files that were scanned: a file does or does
  not exist, a literal does or does not occur, two numbers taken from two different files
  agree. It means "this exact pattern is absent from the files scanned", **not** "this hazard
  is absent": a scan reads text, and the same write can be written in a shape no pattern
  lists. A PROOF line can be trusted as a statement about the text it read.
* **[HEURISTIC]** — a grep that a determined author could evade, or that a human still has
  to judge. The line says what it cannot see.

A PASS on a heuristic check is not a promise. In particular, "no fixed sleep without a
state poll" recognises a poll by the presence of a loop plus a state read in the same shell
function; a cleverly obfuscated blind wait would pass. Checks 3 and 4 are heuristic for the
same reason, and are labelled as such rather than as proofs about the write path — see
["What checks 3 and 4 actually search for"](#what-checks-3-and-4-actually-search-for).

## The checks

| # | check | kind | audit issue |
| --- | --- | --- | --- |
| 1 | no signing key in the tree (and none tracked by git) | PROOF | C5 |
| 2 | no keystore password anywhere in the shipped tree | PROOF | C5 |
| 2b | the directory **around** the tree carries no signing key | PROOF | C5 (hand-off) |
| 2c | the newest archive in `dist/` is this tree's current output and passes `tools/package.sh --check` | PROOF | C5 (hand-off) |
| 3 | no shell redirection (or `tee`/`sponge`) into a calibration path | HEURISTIC | C1 |
| 4 | no `cp`/`mv`/`install`/`rsync` onto a calibration path | HEURISTIC | C1, C4 |
| 5 | every `seek=` carries a `count=` on the same line | HEURISTIC | C1, C3, H6 |
| 6 | no fixed sleep without a state poll on the WiFi-restart path | HEURISTIC | H7 |
| 6b | the app waits by polling, not by a literal `Thread.sleep` | HEURISTIC | H7 |
| 7 | the manifest requests zero permissions and declares no service | PROOF | design |
| 7b | the shipped APK's own manifest carries no permission string | PROOF | design |
| 8a | no line-position parsing of the WiFi status in `app/` or `cli/` | PROOF | H5 |
| 8b | the CLI's MediaTek-only scope matches its code | PROOF | L1 |
| 8c | the sidecar files the README documents are the ones the CLI writes | PROOF | L1 |
| 8d | the `targetSdk` the README states is the one the manifest sets | PROOF | M5 |
| 8d2 | every tool under `tools/` is named in the README | PROOF | discoverability |
| 8e | the README's zero-permission claim matches the manifest | PROOF | design |
| 8f | the prebuilt APK's SHA-256 is the one the docs record | PROOF | M8, C5 |
| 8g | the ip-link fallback is still labelled non-persistent | HEURISTIC | H8 |
| 8h | the Android 12+ randomization-detection limit is still stated | HEURISTIC | H5 |
| 8i | the build stamps a per-build versionCode and versionName and reads both back out of the packaged manifest | PROOF | M8 |
| 8j | the DEVICE card shows the app's own version | PROOF | M8 (app half) |

What each heuristic cannot see is stated on its own output line; the "docs versus code"
checks prove that the two files agree, not that the words are wise.

Check 2b is why check 1 is not enough. Check 1 passed on a tree whose signing key had been
removed while a byte-identical copy of the same key sat one directory up, in
`_private/ks.jks` beside `.pristine/app/ks.jks`; anything that zips or copies the enclosing
directory — the hand-off pattern that leaked the key the first time — re-ships it next to
the APK. The enclosing directory is not this tree's to police and the pristine snapshot
must not be modified, so 2b reports what it finds as `INFO` instead of failing, and 2c runs
the actual gate (`sh tools/package.sh --check`) on the newest archive in `dist/`, where a
key or an `AUDIT.md` inside the artifact *is* a `FAIL`. The gate itself takes a path:
`sh tools/package.sh --check PATH` on whatever is about to leave the machine
([`tools/README.md`](../README.md) documents it).

**What 2c's PASS now means, and what it still does not.** 2c used to compare the archive
*against itself*: `tools/package.sh --check` inspected names, keystore magic bytes,
git-history paths, archive member paths and the presence of the required files, and never
compared a member's contents with the work tree it claimed to have been built from. A stale
archive — one whose own `.sha256` matches itself and whose own inventory lists itself — is
completely self-consistent, so `HANDOFF: PASS` was compatible with `dist/` holding an older
revision of the tree, and a reader took the PASS for an endorsement of a distributable. That
is now an explicit `FAIL`: `tools/package.sh --check` compares every member of an archive
against the tree the script lives in, byte for byte, in both directions (a tree file the
archive lacks counts too), and prints

```
HANDOFF: FAIL <archive> - no key material, but the archive is stale: N of its member(s)
do not match the tree this script belongs to (<tree>); rebuild it with 'sh tools/package.sh'
```

so 2c can no longer go green on a superseded archive. The remedy after any edit is the one
the line names — `sh tools/package.sh` — and the freshness comparison is against *this*
checkout, so an archive that came from somewhere else is judged by `--no-freshness`, which
says in its verdict that freshness was not checked. What 2c still does not prove: that the
archive's code is correct, or that it is the revision the CHANGELOG describes; it proves the
archive is this tree's current output, and `tools/package.sh --check` proves it carries no
key, no audit artifact and no escaping member path.

**Consequence for the order of work, and it bites**: because 2c compares the archive against
the tree, **editing any file in the tree makes the existing `dist/` archive stale, and 2c
FAILs until it is rebuilt** — including a change to a `.md` file, the README included. A
documentation pass therefore cannot leave `dist/` green behind it. Packaging must be the
**last** action, after the tree is frozen: `sh tools/package.sh`, then
`sh tools/checks/checks.sh`. Observed here — after `tools/checks/README.md`, `README.md` and
`cli/macchanger.sh` were each edited once, `--check` reported
`stale: 3 member(s) do not match …` while the archive's own `.sha256` still matched the
archive, which is exactly the self-consistent-stale state 2c now catches.

## Why some files are excluded from the scans

* `AUDIT.md` and `audit/` are the audit's own output. They *quote* the removed keystore
  password and the vulnerable code, and `tools/package.sh` does not ship them. Scanning
  them would make checks 2, 3 and 4 fail forever on a correct tree, and `checks.sh` says so
  on an `INFO` line instead of hiding the exclusion.
* `checks.sh` excludes itself from its own scans: it necessarily contains its own patterns
  and their descriptions as literals, and a scanner that reports its own source as a
  finding is noise. `tools/package.sh` is excluded from check 2 for the same reason.
* `.git/` and `dist/` are not source.
* `*.test.sh` scripts are excluded from checks 3–5, and the check says so on an `INFO`
  line (`cli/macchanger.test.sh`, which this once named, has since been removed from the
  tree). A test harness *must* build synthetic fixtures: it redirects
  into its own throwaway `$NV` and moves files around inside its sandbox, which is exactly
  what the product must never do. The guard for those scripts is
  [`tools/clitest`](../clitest/README.md), not a grep.

`CHECK_EXCLUDES` adds extra `grep -r` arguments if a tree needs more.

## What checks 3 and 4 actually search for

Both are line-level pattern scans, and both were widened after a fresh-hunt verifier
reproduced them passing on a tree that contained the exact hazards they are named after:

* **check 3** searched only for `>` with a fixed verb/path list, so `cat "$BAK" | tee "$NV"`
  was invisible, and so was `CAL=$NV; … > "$CAL"`. It now searches `>` (with `-` before it
  still excluded, because the dry-run text legitimately contains `-> $NV in place`), `tee`,
  `sponge`, and the *aliases* of the calibration path — variable names derived, at scan time,
  from assignments whose value is `$NV`/`${NV}` or a calibration literal, so `CAL=$NV` cannot
  put the write outside the vocabulary. The names it found are printed in the evidence line.
* **check 4** searched `cp|mv|install` against a path list that omitted `/efs/` and
  `/persist/`, which check 3 did watch. Both checks now build that list from one variable
  (`CAL_DIRS` in the script) and add `rsync`;
* both now name the paths and the aliases in their PASS evidence, and both say what they
  cannot see: a path assembled at runtime (`DIR=/mnt/vendor/nvdata; > "$DIR/WIFI"`), a write
  performed by a helper command the vocabulary does not name, or a name renamed twice.
  `dd of=` is deliberately **not** in either pattern: it is the sanctioned in-place writer,
  and what bounds it is check 5.

The reproduction is why they are labelled `HEURISTIC`: a label that says "PROOF" while the
pattern vocabulary is narrower than the hazard is the failure mode this file exists to
prevent. Verified on a scratch copy: with `cat "$BAK" | $BB tee "$NV" >/dev/null`,
`$BB cp "$BAK" /efs/wifi/.mac.info`, `$BB cp "$BAK" /persist/wifi/.mac.info` and
`CAL=$NV; $BB cat "$BAK" > "$CAL"` added to `cli/macchanger.sh`, the widened checks report
two `FAIL` lines naming all four lines and the summary reads
`CHECKS: FAIL` with two failed result lines. `install`, `sponge` and `rsync` against the
same paths, and the same writes aimed at `/productinfo/`, were added in a second scratch copy
and caught the same way.

**Check 8i** carried the matching version of the same mistake: its `PASS` text asserted that
`app/build.sh` "gates on both appearing in `aapt dump badging`" while the check only grepped
for the two `--version-*` flags, so deleting both gate lines left the sentence standing. The
check now greps for the gate lines themselves (`versionCode='$VC'` / `versionName='$VN'`
against `build/badging.txt`), `FAIL`s when either is gone, and quotes the real `app/build.sh`
line numbers in its evidence instead of describing them.

## Current result on this tree

Measured by running `sh tools/checks/checks.sh` on this tree after `dist/` was rebuilt from
it; its last two lines were, verbatim:

```
checks: 21 passed, 2 informational, 0 failed (23 result line(s))
CHECKS: PASS (21 passed, 2 informational, 0 failed)
```

The two INFO lines are stated findings, not passes:

* the audit artifacts are quoted and excluded from the scans (check 2);
* **2b**: the enclosing directory holds two byte-identical copies of the removed key
  (`_private/ks.jks`, `.pristine/app/ks.jks`, the same SHA-256), reported rather than
  fixed because neither is this tree's to delete — see above.

A third `INFO` line appears whenever a `*.test.sh` script exists: the exclusion of those
scripts from checks 3–5 is stated rather than applied silently. This tree currently has
none, so the line is absent and the result-line count is one lower — which is why the
summary counts result lines rather than quoting a fixed total.

A `*fourth*` variable is check 2c, and it is the one that makes a pasted tuple unsafe: it
reports one of the three states in the table at the top of this file depending only on what
`dist/` holds at that instant, so the totals move without anything in the tree changing.
With `dist/` emptied the run is `20 passed, 3 informational, 0 failed`; with a rebuilt
archive it is `21 passed, 2 informational, 0 failed`; with an archive that predates the
latest tree edit it is `1 failed` and exit 1 — which is the correct outcome, because that
archive is a revision nobody verified. See the table above and the ordering rule under
check 2c.

An earlier revision of this file reported `24 checks: 20 PASS, 4 INFO, 0 FAIL` and listed
8j as an open finding. Both were stale: the counter now separates the three outcomes (an
`INFO` line asserts nothing and is no longer counted as a check that passed, so "24 of 24"
cannot be read as 24 verified claims), and 8j `PASS`es — the code reads
`getPackageManager().getPackageInfo(getPackageName(), 0)` inside `MainActivity` and renders
`pi.versionName` in the DEVICE card, and the check reports
`PASS [PROOF] the DEVICE card shows the app's own version (M8, app half)`.
No line numbers are quoted for this one on purpose: `MainActivity.java` is the file most
likely to be edited, and a citation that drifts is how this block went stale in the first
place. Grep `getPackageInfo` and `versionName` instead.

## Requirements

POSIX `sh`, `grep`, `find`, `sed`, `awk`, `sha256sum`, `dd`, `od`, and `unzip` for check 7b,
plus `tools/package.sh --check` (POSIX `sh`, `tar`) for check 2c. No network, no Android SDK,
no Gradle, no build of any kind.

## Known limits

* It is not a behavioural test: it reads text. The write path itself is exercised by
  [`tools/clitest`](../clitest/README.md), and the Java source is compiled by
  [`tools/stubcompile`](../stubcompile/README.md). Run all three.
* It cannot rebuild or re-sign the APK (no Android SDK here), so check 7b inspects the
  shipped binary's manifest rather than a fresh build.
* Check 8f compares hashes; it does not prove the APK was built from this source. The
  README states plainly that the shipped APK predates these fixes.
