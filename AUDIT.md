# MacChanger audit — canonical merged plan

Six independent auditors, merged and de-duplicated by the synthesizer. Every finding below was
re-checked against the working tree; where an auditor was wrong or overstated, that is said
explicitly in **Correction**.

Repos state audited: `a82ffee pristine MacChanger as shipped`, working tree clean, 855-line
`MainActivity.java`, 139-line `cli/macchanger.sh`, 42-line `app/build.sh`.

> **About the paths in this document.** It is a record of an audit run inside a
> disposable Linux container, so it cites absolute paths from that machine: the work tree was
> `/root/macchanger-fixed/MacChanger` and `/root/macchanger-fixed/.pristine` was an untouched copy of
> the pre-fix revision — which you can reproduce from this repository's own history with
> `git worktree add /tmp/pristine a82ffee`. Nothing outside the work tree is published, and the
> signing key those paths mention is deliberately absent from this repository and from its history.

Verification available in this environment: JDK 17 `javac`/`java`, python3, dd/od/hexdump/busybox,
dash+bash, unzip, keytool/jarsigner/openssl, and outbound HTTPS. **No** aapt/d8/apksigner/zipalign,
**no** Android SDK — the APK cannot be rebuilt here, so all claims are source reads, extracted-logic
execution, shell reproduction, artifact forensics, or AOSP source verification.

Auditor disagreements resolved (details inline): **javac partial-classes** (build lens right, shell
lens wrong — proven below), **K3 "committed"** (corrected: gitignored but shipped next to the APK),
**`printf %b \xHH`** (shell lens' dash-builtin test does not exercise the shipped path — downgraded
from high/speculative to medium), **"the CLI is the safer path"** (false for `restore`).

Coverage: all 12 known issues K1–K12 appear below. K7 gets a corrected diagnosis and a worse
consequence than the original list assumed; K4's "the CLI in-place dd is safer" is only true for `set`.

---

## CRITICAL

### C1 — The NVRAM write is a non-atomic whole-file rewrite with no size authority
**Owner:** `java_write` · **Covers:** K4, plus new evidence from the NVRAM lens
**File:** `app/src/com/macchanger/MainActivity.java:444-451` (`writeRoot`), `:423-442` (`readRoot`, esp. `:438`), `:738-746`

**Evidence.**
- `:447` — `run("cat '" + tmp.getAbsolutePath() + "' > '" + path + "'")`. Redirecting into the
  destination opens it `O_TRUNC`. Reproduced: `sh -c "cat empty_tmp > target"` on a 512-byte file
  leaves **0 bytes, exit 0**.
- `:438` — `return d.length == 0 ? null : d;`. `readRoot` reads to EOF and **never compares the byte
  count to the file's real size**. A short `su cat` (LMK kill, Magisk timeout, a read cut short)
  yields a short buffer that is then written back in full. Four of the nine paths in
  `nvramPaths()` (`:519-521`, `:524-525`) are whole partition/productinfo images — far larger than
  the 512-byte MTK blob — where a partial read is both likelier and more damaging.
- `:450` — `return back != null && Arrays.equals(back, data);`. The only check compares against that
  same in-memory buffer, so it *certifies* the truncation instead of catching it.

**Impact.** Silent, permanent loss of every calibration byte past the read length on Qualcomm
`/persist/wifi/wlan_mac.bin`, and on Unisoc `/productinfo/wifi_mac` — a whole productinfo image
holding model/serial/MAC. That damage survives a factory reset and this app cannot repair it. The
user is told `nvram patched (N hit)` in green.

**Fix.** Get the authoritative size inside the same `su` invocation and gate on it:
`sz=$(stat -c %s -- "$path" 2>/dev/null || wc -c < "$path")`; refuse to patch unless `sz >= 16` and
the read length equals `sz`. Replace the write with an argv-passed, size-bounded in-place write
(`dd if="$1" of="$2" bs=4096 conv=notrunc,fsync`), then require `stat -c %s` unchanged, then
`cmp -s`, then re-derive the MAC from the located offset. Emit one log line per path actually
written carrying **path + matched offset(s) + old→new** (this is the data K11/M3 needs). Add
`if (data.length == 0) return false;` at the top of `writeRoot`. Do **not** call `writeRoot` unless
java_core's verified-backup helper returned true (C2). **Never `mv` into place** — that changes the
SELinux label, owner and mode of a calibration file; K4 is right and in-place is mandatory.

**Acceptance.** `grep -n "cat .*>" MainActivity.java` shows no shell redirection targeting `path`;
the write command contains `conv=notrunc` and an argv-passed path (not string-concatenated); `stat`
or `wc -c` comparisons exist both before and after the write. With a stub `su` whose `cat` returns 40
of 512 bytes, the run logs a read-size/on-disk mismatch, writes nothing, and the target's content and
mtime are unchanged.

---

### C2 — The backup is not a precondition: its write result is discarded, it is not atomic or verified, and an empty/short backup is later installed over the calibration file
**Owner:** `java_core` · **Covers:** new (N2/N3, restore half)
**File:** `MainActivity.java:739-746` (esp. `:745`), `:412-421` (`writeFile`), `:398-410` (`readFile`), `:801-814`, `:600-612`; `README.md:113`

**Evidence.**
- `:745` — `if (!bk.exists()) writeFile(bk, orig);` — the boolean return is **dropped**. When the
  app-private write fails (ENOSPC, dir not writable) the NVRAM is patched at `:746` anyway, with no
  independent copy. `README.md:113` ("The app always copies the original NVRAM before writing") is
  false on this path.
- `writeFile` (`:414-416`) is a plain truncate-then-write `FileOutputStream` with no `fsync` and no
  read-back, so a kill or ENOSPC during it leaves a partial backup. `:745`'s `if (!bk.exists())`
  means a partially-written backup is **never re-copied** and is promoted to "factory" forever.
- `readFile` returns `byte[0]` — **not** `null` — for an existing 0-byte file, so the `data == null`
  guard at `:811` does not filter it and `:812` calls `writeRoot(path, new byte[0])`. Verified that
  this truncates a 512-byte target to **0 bytes** with exit 0.
- `:803-806` treats every non-`.path` file in the backup directory as a backup and only requires a
  `.path` sibling to *exist*, not that the backup is valid.

**Impact.** One Restore tap can reduce a calibration file to zero bytes while the routine keeps
iterating and finally prints `no nvram match · runtime only` — no clear signal that the file was
destroyed. And the one documented recovery guarantee can be backed by a file that certifies a
truncated image as "factory".

**Fix.** (1) Write the backup to `name.tmp` in the app dir, `getFD().sync()`, close, re-read with
`readFile()` and require `Arrays.equals` with the pre-image **and** equal length, then `renameTo`
the final name — inside `getFilesDir()` a rename is safe (K4's SELinux caveat applies only to the
NVRAM path). Return that boolean. (2) When a backup already exists, re-validate its length against
the target's on-disk size from the same `su` call and re-create it on mismatch. (3) In restore,
require `data != null && data.length >= 16 && data.length == on-disk size` and that the buffer
contains a MAC-shaped field; skip and log otherwise.

**Acceptance.** With `getFilesDir()/nvram_backup` made unwritable, a stub run of `setMac` logs
`backup failed — NVRAM untouched` and the target's sha256 is unchanged. With a 0-byte file and,
separately, a 10-byte file plus `.path` sibling placed in `nvram_backup`, tapping Restore leaves the
512-byte target byte-identical in both cases.

---

### C3 — `patch()` has no degenerate-pattern guard and no replacement cap: an all-zero/all-0xFF runtime MAC rewrites ~90% of the calibration image
**Owner:** `java_write` · **Covers:** K7, with a corrected trigger
**File:** `MainActivity.java:587-594` (`patch`), `:572-585` (`replaceAll`), `:596-598` (`isMac`), `:730-742`, `:790-797`

**Evidence** — the real `patch()`/`replaceAll()` bodies extracted verbatim and executed on a
512-byte MTK-style image (header `01 00 08 00`, MAC at offset 4 and 32, ASCII copy at 100):

```
isMac(00:00:00:00:00:00) = true
isMac(ff:ff:ff:ff:ff:ff) = true
isMac(01:00:5e:00:00:01) multicast = true
patch(ALL-ZERO -> 11:22:33:44:55:66):  hits=78  bytesChanged=468/512  span=10..506
patch(ALL-0xFF -> 11:22:33:44:55:66):  hits=83  bytesChanged=498/512
```

**Correction to the raw finding.** The claimed trigger *"type `00:00:00:00:00:00` into Set MAC and
then set another MAC"* is **wrong**: the search pattern is always `macToBytes(getRuntimeMac())`
(`:732`) or `macToBytes(curRt)` (`:791`) — the typed value is only ever the *replacement*, never the
pattern. The real trigger is a runtime MAC read of `00:00:00:00:00:00` or `ff:ff:ff:ff:ff:ff`, which
some drivers report before association, after rejecting a MAC, or when the interface never
initialises. The damage is unchanged, and the all-`0xFF` case is the worse one because flash padding
is routinely `0xFF`. **Consequence for the fix: guard the pattern, not the user's input.** (The raw
finding also claimed the MAC field at offset 4 would be rotated and header byte 3 clobbered; on the
image tested here the first all-zero 6-run begins at offset 10, so offsets 0-9 survive. That detail
does not hold in general — it depends on the header bytes — and the headline damage does.)

**Impact.** A single Set or Restore rewrites every zero- or `0xFF`-padded region of a calibration
image — other interfaces' MACs, length fields, checksum-adjacent bytes — and reports
`nvram patched (78 hit)` in green. The tool's own `restore()` uses the runtime MAC as its pattern
(`:791`), so a driver reporting zeros turns the *recovery* action into whole-file corruption.

**Fix.** In `patch()`, reject a degenerate pattern **before** any replacement: require
`(oldB[0] & 1) == 0`, at least one non-zero octet, and not all-`0xFF`; return a sentinel the caller
logs as `refusing to patch: MAC pattern is degenerate`. Cap the match count per file (the existing
`hits > 0` gate becomes `hits > 0 && hits <= CAP`, CAP at the top of the tested range, e.g. 8);
above it, abandon the file entirely — no backup, no write — and log
`unexpected layout — N matches, refusing`. After building the patched buffer, assert
`data.length == orig.length` and that every byte outside the recorded replacement windows is
identical to `orig` — cheap in-memory checks that catch all of the above.

**Acceptance.** A harness calling `patch()` with an all-zero and an all-`0xFF` pattern on a 512-byte
padded image returns the refusal sentinel, logs the refusal, and leaves the buffer byte-identical.
`grep` shows an explicit unicast / non-zero / not-all-`0xFF` check before the first `replaceAll`
call and a `hits > CAP` abandon branch.

---

### C4 — CLI `restore` fabricates a "factory" backup from the live spoofed NVRAM, reports success, and can truncate the calibration file
**Owner:** `cli` · **Covers:** new (both shell and security lenses found this independently)
**File:** `cli/macchanger.sh:26-31` (`init_backup`), `:96-102` (`cmd_restore`), `:104-110` (`cmd_show`), `:98`, `:48-51`

**Evidence** — reproduced by running the unmodified script with only `PATH`/`BB`/`DIR`/`NV`
redirected, against a 40-byte NVRAM already holding the spoof `02:aa:bb:cc:dd:ee`, with no backup:

```
$ macchanger.sh restore
[*] saved factory NVRAM backup -> .../WIFI.factory
[*] restored factory NVRAM MAC: 02:aa:bb:cc:dd:ee
exit=0
```

The NVRAM was **byte-identical** afterwards (nothing was restored), and the file just labelled
"factory" contains the spoof. The same side effect fires from the read-only command: `macchanger.sh
show` (`:105`) printed `[*] saved factory NVRAM backup` and created a **world-readable (0644)**
40-byte `WIFI.factory` holding the live spoof. `:27`'s `[ -f "$BAK" ] && return 0` then guarantees
the real factory value is never captured.

Second, independent defect on the same command: `:98` is `cp -a "$BAK" "$NV"` — a **truncating
whole-file rewrite**. With a 10-byte backup over a 40-byte NVRAM the file became **10 bytes**
(`01 00 08 00 de ad be ef 00 01`), the script printed
`[*] restored factory NVRAM MAC: de:ad:be:ef:00:01` and exited **0**.

**Corrections.** To K1's framing: the CLI's *location* is right but its *semantics* are not — it
silently enshrines a spoof as the factory value, the same class of loss K1/K2 describe for the APK,
with no MAC randomization involved. To K4's framing: *"the CLI in-place dd is safer"* holds only for
`set`; `restore` uses exactly the truncating primitive K4 warns about, on the one path where
recoverability matters most.

**Impact.** Any user whose first CLI action is `show`, `set` or `restore` on a phone already spoofed
(by another tool, or after a `/data/adb` wipe) permanently loses the true factory MAC and is told the
restore succeeded — self-confirming, because `show` then reports the spoof as `factory (backup)`.
And a partial backup (precisely what C1's non-atomic write produces) destroys everything past byte
10 of the calibration file.

**Fix.** Make capture explicit: `init_backup create|require`. `set`/`random` call `create`;
`restore` calls `require` and dies with `no factory backup — capture it before spoofing, or restore
the factory MAC you recorded elsewhere`; `show` creates nothing and prints `(no backup)`. Never
label a capture "factory" when `get_runtime` differs from the captured MAC — that is direct evidence
of a live spoof; print the mismatch and require an explicit `backup --assume-factory`
acknowledgement, or accept a factory MAC typed by the user. Restore must be in-place and
length-checked: compare `wc -c` of backup and target and `die` on mismatch, then
`dd if="$BAK" of="$NV" bs=4096 conv=notrunc,fsync`, then `cmp -s "$BAK" "$NV" || die`, then print the
MAC **re-read from `$NV`** (never `get_factory`). Store `$BAK.sha256` at capture and verify before
writing back. Exit non-zero when the requested MAC is not in place afterwards. Add `umask 077`, `mkdir -m 700 -p "$DIR"` and `chmod 600 "$BAK"`.

**Acceptance.** The transcript above must instead print an error and leave `od -An -tx1` of the
NVRAM unchanged; the 10-byte-backup case must `die` with a size mismatch and leave the 40-byte file
at 40 bytes; `sh cli/macchanger.sh show` must create nothing under `$DIR` (diff a `find` before and
after); `$BAK` is mode 600 and `$DIR` mode 700.

---

### C5 — The signing private key ships with the distribution and verifiably signed the shipped APK; `build.sh` silently generates a *different* key when it is absent
**Owner:** `build` · **Covers:** K3, with a precision correction
**File:** `app/ks.jks`, `app/build.sh:31-35`, `:38-39`; `README.md:35`, `README.md:47`; `.gitignore:1`

**Evidence** (all re-verified here).
- `keytool -printcert -jarfile prebuilt/MacChanger.apk` → SHA-256
  `8F:ED:CA:F1:…:11:86:F1:AF`, serial `f95c6faeadff3f8d`, `CN=MacChanger`.
  `keytool -list -v -keystore app/ks.jks -storepass android` → **identical** SHA-256 and serial,
  alias `mac`. So the key on disk is exactly the key that signed the shipped APK.
- **Correction to K3's wording ("committed").** The key is **not** in the git object store:
  `.gitignore:1` is `ks.jks`, `git ls-files` returns 7 files without it, and `git status --porcelain`
  is clean. It **is** present in the shipped tree — `/root/macchanger-fixed/.pristine/app/ks.jks`
  (the pristine/shipped snapshot) contains it. So the exposure is *"ships next to the APK in the
  distributed directory or archive"*, not *"in the git commit"*. For anyone who receives the tree or
  a zip — the normal way a `prebuilt/` APK reaches a user — the impact is identical, so K3 stands in
  substance and its fix is unchanged.
- `README.md:35` publishes `debug signing keystore (alias: mac / pass: android)`, so the key is
  unusable for secrecy by design.
- `build.sh:31-35` regenerates a fresh key when `ks.jks` is missing and prints **no warning**, so on
  a clean `git clone` every self-built APK has a different signing identity from
  `prebuilt/MacChanger.apk`. `pm install -r` (`README.md:47`) then fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only workaround is `pm uninstall`, which deletes
  `/data/data/com.macchanger/` — per K1/C2 exactly where the NVRAM backups and the saved factory MAC
  live. `versionCode` is frozen at 1 (`AndroidManifest.xml:4`), so no version check distinguishes a
  trojan from the original.

**Impact.** (a) Anyone holding the distribution can sign a `com.macchanger` update that Android
accepts in place, inheriting the already-granted root policy and the app's private backup data.
(b) A user following the documented self-build path can be pushed into destroying the only
factory-MAC record. **Ordering matters:** C2/H1 (durable records under `/data/adb` + export) should
land before or with the keystore withdrawal, or the loud refusal must be unmistakable.

**Fix.** Remove `app/ks.jks` from anything distributed and delete the password line from
`README.md:35`. Make signing explicit: `KS=${KS:?supply a release keystore}`,
`KS_PASS=${KS_PASS:?}`; if the keystore is absent, `die` with an explicit
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "uninstall first, after copying your backup out of
/data/data/com.macchanger/files/nvram_backup/" warning and require a `--new-key` argument to
generate one. Add `-storetype PKCS12` (and rename to `ks.p12`) so the format does not depend on the
deployed JDK. Print `keytool -printcert -jarfile "$OUT"` and `sha256sum "$OUT"` at the end of every
build. Publish the release certificate SHA-256 in the README and state the Janus (CVE-2017-13156)
caveat for API 21-23, where v2/v3 are ignored.

**Acceptance.** `git ls-files` and `find . -name 'ks.jks'` show no keystore in the shipped tree;
`grep -rn 'pass:android\|pass: android'` returns nothing in code or docs; running `bash app/build.sh`
with no `KS` prints the refusal and exits non-zero **without** creating a keystore; a build with a
supplied keystore prints both the APK SHA-256 and the signer certificate SHA-256.

---

## HIGH

### H1 — K1: factory record and NVRAM backups live in app-private storage, are destroyed by uninstall, and there is no way to enter a known factory MAC
**Owner:** `java_core` · **Covers:** K1
**File:** `MainActivity.java:600-612`, `:398-421`, `:463-466`, `:783-784`; `AndroidManifest.xml:7`; `README.md:111-116`

**Evidence.** `backupDir()` is `new File(getFilesDir(), "nvram_backup")` (`:601`) and the factory MAC
is `getSharedPreferences("mc", 0)` (`:463`). `restore()` bails at `:784` with
`toast("No factory MAC saved yet")`, and there is no field anywhere to type a known factory MAC — so
once the pref is gone the factory value is unrecoverable through the UI, even if the user wrote it
down. `allowBackup="false"` (`AndroidManifest.xml:7`) means Android's backup transport will not
preserve it either, and C5's `pm uninstall` deletes it. `README.md:114-115` tells the user to "keep
the backup" without giving any way to obtain it — an unrooted user cannot read app-private storage.
The CLI already does this correctly (`cli:13-14`).

**Impact.** Uninstall, clear-data, or a forced reinstall (C5) destroys the only recovery path for a
modified calibration partition. For a tool whose promise is "always be able to restore the factory
MAC", this converts a reversible change into a permanent one.

**Fix.** Make `/data/adb/macchanger/` the primary record location for both front ends. Java has no
file-API access to `/data/adb`, so go through the root channel the app already uses:
`su -c 'mkdir -m 700 -p /data/adb/macchanger'`, then write/read `WIFI.factory`, `WIFI.factory.path`
and a new `factory.txt` via argv-passed paths. Keep the app-private dir as a **fallback mirror** for
devices without `/data/adb`. Add a factory-MAC input row: when no record exists, accept a typed MAC,
validate with `isMac()`, label it `unverified — typed by user`, and allow restore from it. Add an
Export action that copies the record to a user-visible path through the existing root channel
(`su -c 'cp -a /data/adb/macchanger/WIFI.factory /sdcard/Download/'`) — no manifest permission.

**Acceptance.** With the app's private data removed (delete `getFilesDir()` in a stub run),
`restore()` still finds the factory record under `/data/adb/macchanger/` and reports its provenance;
the factory row accepts a typed MAC and a restore from it succeeds; `grep -n getFilesDir
MainActivity.java` shows the app dir used only as a fallback; `AndroidManifest.xml` still has zero
`<uses-permission>` elements.

---

### H2 — K2: the factory value is captured from the live runtime MAC, so a randomized or already-spoofed MAC is recorded as "factory"
**Owner:** `java_core` · **Covers:** K2, plus the first-seen/provenance finding
**File:** `MainActivity.java:730-731`, `:744-745`, `:791-792`, `:759-762`, `:824-827`, `:777`, `:596-598`

**Evidence.** `:731` — `if (!isMac(getPref("factory")) && isMac(cur)) setPref("factory", cur);` records
whatever `/sys/class/net/wlan0/address` says at the first Set MAC tap. Under Android MAC
randomization that is the randomized MAC (K2); after a spoof applied by the CLI it is the spoof.
Either way the true factory MAC is unrecorded while the UI labels the value `factory` (`:233` label,
`:694` render) and `restore()` faithfully reinstalls it. The app already knows how to recognise its
own randomized output — `randomMac()` sets the locally-administered bit
(`b[0] = (b[0] & 0xFC) | 0x02`, `:777`) — but nothing checks it at capture time. There are two
records that can disagree (the NVRAM snapshot at `:745` and the `/sys` string at `:731`) and nothing
cross-checks them.

**Impact.** The product's central promise fails silently for any user who first touches the app
while the MAC is randomized or already spoofed — which includes every Android 11+ user whose network
uses randomization. The row says "factory", the restore says "Restored", and the real factory MAC is
gone from both records.

**Fix.** Derive the factory bytes from the **NVRAM pre-image**, not from `/sys`: `patch()` already
sees `orig`, so require the 6 bytes at the matched offset to equal `cur` before writing, and record
`bytesToMac` of `orig` **at the matched offset**. Refuse capture when `(b[0] & 0x02) != 0` (locally
administered), when the value is all-zero or all-`0xFF`, or when the runtime MAC is absent from every
candidate NVRAM path — log `runtime MAC looks randomized/spoofed; factory value not recorded — enter
it manually` and surface H1's manual-entry row. Persist provenance in a `factory_src` pref
(`path + "@" + offset`, or `typed`) and render the row as `factory (NVRAM @0x4)` /
`factory (typed, unverified)` / `(not recorded)`. Never overwrite an existing record.

**Acceptance.** With a stub `/sys` value of `02:11:22:33:44:55` (locally administered) and no pref,
`setMac` logs the refusal and leaves the factory pref unset. With a stub NVRAM whose factory MAC
deliberately differs from the `/sys` string, the recorded factory value equals the **NVRAM bytes**,
not the `/sys` string — asserted in the stub run.

---

### H3 — K12: restore's verdict comes from the runtime MAC, so "Restored" is printed while the NVRAM still holds the spoof
**Owner:** `java_core` · **Covers:** K12
**File:** `MainActivity.java:789-816` (esp. `:798`, `:801`), `:818-831` (esp. `:820-823`, `:829`)

**Evidence.** `:798` — `if (hits > 0 && writeRoot(path, data)) restored++;`. The executed probe shows
`patch(x, x)` returns **hits=1 with byte-identical output**, so `nvram restored (1)` can be printed
for a complete no-op. Step 2 is gated on `if (restored == 0)` (`:801`), so a *partly* applied step 1
suppresses the backup fallback entirely — the two strategies are mutually exclusive instead of
additive. The verdict is `now.equalsIgnoreCase(factory)` (`:821`) where `now = getRuntimeMac()`: the
one signal that is stale exactly when the write did not take. The mirror image is real too — after a
genuinely verified restore, a driver that only re-reads NVRAM on reboot makes `:829` print
`(unexpected)` and warn, i.e. a success reported as a failure.

**Impact.** The user is told the factory MAC is back while the spoof remains in the calibration
partition and returns at the next reboot — the tool's central recovery guarantee silently does not
hold. Conversely, a correct restore is reported as a warning.

**Fix.** Base the verdict on the **write**, which the code can already verify. Count only
content-verified writes; after each successful install re-read the file and re-derive the MAC at the
matched offset to prove the factory bytes are present. Make the strategies additive: run step 1 per
path, and for any path where step 1 matched nothing but a valid backup exists, use the backup — do
not gate step 2 on a global `restored == 0`. Print two separate facts: `NVRAM restored (verified:
factory MAC present at 0x…)` and, when the runtime still differs, `runtime not re-read yet — reboot
or toggle WiFi to apply`. Early-return when the runtime MAC already equals the factory pref **and**
no NVRAM occurrence exists, to avoid a pointless destructive rewrite.

**Acceptance.** A stub run where the NVRAM write succeeds but the driver keeps reporting the spoof
prints the NVRAM-verified success line and the "runtime not re-read yet" note — never the bare
`Restored:` toast. A stub run with a valid backup for a path whose live MAC is not in the file still
restores that path (proves step 2 is reachable after a partial step 1). `patch(x, x)` producing zero
byte changes does not increment the restored count.

---

### H4 — K6: no shell timeout and no cancellation anywhere — and the obvious "add a timeout to `run()`" is unsafe on the write path
**Owner:** `java_core` · **Covers:** K6, plus the sequencing hazard
**File:** `MainActivity.java:367-381`, `:423-442` (`:433-436`), `:383-396` (`:388-391`), `:668-676`, `:723`, `:782`, `:767-770`, `:832-835`

**Evidence.** `run()` has no `waitFor(timeout)` and `destroy`/`destroyForcibly` appears **nowhere** in
the file, so an ignored Magisk grant prompt blocks a worker forever while `busy` stays true; Set MAC
and Restore both start with `if (busy) { toast("busy…"); return; }` (`:723`, `:782`), so the app is
wedged until force-stop. `run()` is not the only blocking path: `readRoot` has its own read loop
(`:433-436`) and so does `getprop` (`:388-391`), and `readRoot` blocks in `in.read(buf)` (`:435`)
**before** `waitFor()` (`:436`) — while su sits on a prompt it has written nothing, so no read
timeout can fire; only an external thread calling `destroyForcibly()` breaks it. There is no
cancellation: `refresh()` (`:668`) and the root badge (`:216` → `checkRoot(false)`) have no `busy`
guard, so every Refresh tap during a hung action spawns another hung su + thread. One Set MAC tap
issues ~12-16 sequential `su` invocations (getRuntimeMac, existingNvram, 2 per candidate path,
several in `reinitWifi`, a final getRuntimeMac) — each an independent chance to hit a superuser
manager's prompt or rate limit.

**Sequencing constraint — this is the single most dangerous ordering mistake in this plan.**
`java_core` runs **before** `java_write`, but C1's in-place `dd conv=notrunc` write does not exist
yet when `java_core` lands. Killing su after the shell has opened the destination with `O_TRUNC` but
before it finishes produces exactly the truncation K4/C1 describe — i.e. **the timeout fix would
cause the bug it is meant to prevent**. Therefore: in `java_core`, apply the deadline to
`run()`/`readRoot()`/`getprop()` **but explicitly exempt the write command** (leave `writeRoot`'s
`su` invocation unwatched, with a comment saying why), and let `java_ui` (which runs last, after C1
has landed) extend the deadline to the write path.

**Impact.** Without a bounded *and cancelable* shell layer, a single ignored prompt or a slow `cat`
of a calibration partition wedges the app until force-stop; and a uniformly applied timeout would
turn that timeout into NVRAM truncation.

**Fix.** (1) Add `Res runTimed(String cmd, long ms)` returning exit code + output, draining stdout on
the calling thread with a deadline and a watchdog thread that calls `destroyForcibly()`; route
`run()`, `readRoot()` and `getprop()` through it (default 15000 ms, a longer explicit budget for
whole-image reads) and return the exit code so callers can distinguish failure from empty (see M4).
(2) Gate `refresh()` (`:668`) and the badge (`:216`) with `if (busy) { toast("busy…"); return; }` and
disable the header buttons while busy, so one action is one serialized su stream. (3) Log a red
`su did not answer — nothing was written` on timeout instead of returning a silent empty value.
(4) Exempt `writeRoot`'s command until C1 lands (see above).

**Acceptance.** With a stub `su` that never exits and never writes, tapping Set MAC returns the UI to
a usable state within the timeout, logs the timeout line, clears `busy`, and a second tap is
accepted. `grep -n 'destroyForcibly' MainActivity.java` finds a watchdog; `grep -n 'waitFor('`
shows a deadline. Inspection confirms `writeRoot`'s own `su` invocation was not given a deadline by
`java_core` (comment present), and that `java_ui` only wired it after the `conv=notrunc` write landed.

---

### H5 — A1+K9: `cmd wifi status | head -3` cannot see the SSID on Android 12+, and an unresolved SSID is then read as "this network randomizes the MAC"
**Owner:** `java_ui` · **Covers:** K9, plus new AOSP-verified platform evidence
**File:** `MainActivity.java:614-619`, `:634-648` (esp. `:635`, `:638`, `:640`), `:628-631`, `:699-717`, `:759-762`, `:824-827`

**Evidence — verified against AOSP sources, not inferred.** `printStatus` in
`packages/modules/Wifi/…/WifiShellCommand.java` gained a
`if (Binder.getCallingUid() != Process.ROOT_UID)` privilege branch from Android 12 onward
(android12:1454, android14:2404, android16:2888), and **this app runs everything as root**
(`su -c`, `:369`). As root the output order is:

```
1  Wifi is enabled                                    (android16 line 2881)
2  Wifi scanning is …                                 (2882)
3  ==== ClientModeManager instance: … ====            (2896, printed per manager)
4  Wifi is connected to "<SSID>"                      (2866, inside printWifiInfo)
```

With `:615`'s `| head -3`, a simulation gives `… | head -3 | grep -c 'connected to'` → **0**.
`android11-release` is the one branch with no privilege break, where line 3 *is* the SSID line —
exactly why the Android 11 test device hid this. So `connectedSsid()` returns `""` (`:618`) on every
Android 12-16 device.

Second, independent defect in the same path: `privacyValue`'s guard (`:635`) checks
`ssid.equals("\u2014")` but **not** `ssid.isEmpty()`, while `connectedSsid()` returns `""`.
`xml.split("<Network>")` element 0 is the file preamble (`:636`), `blk.contains("")` is always true
(`:638`), the preamble has no `MacRandomizationSetting`, so `if (i < 0) return "1";` (`:640`) fires
**unconditionally** → `privacyValue(xml, "") == "1"` deterministically → `isRandomized() == true`.

Third: `blk.contains(ssid)` (`:638`) is a raw substring match against XML-escaped text, so an SSID
containing `&`, `<`, `>`, `'` or `"` (escaped in the store, raw in `cmd wifi status`) never matches
its own block and can match the wrong one (the original K9).

**Impact.** On essentially all 2024-2026 hardware the privacy row can only ever show `unknown` and
the NETWORK row `— · on`. Worse, every failed Set/Restore is then explained as
`ignored: this network randomizes the MAC` / `set its Privacy to 'Use device MAC'` (`:760-761`,
`:825-826`), overriding the accurate `driver kept it` branch (`:764`) and sending the user to change
a setting that was already correct — while the real cause (a failed NVRAM write) stays hidden. The
false-positive rate is 100% whenever the SSID is unresolved. See A5 below for the value space.

**Fix.** (1) Stop slicing lines: run `cmd wifi status` whole, extract with an anchored match on the
`Wifi is connected to ` prefix, strip the surrounding double quotes that `WifiInfo.getSSID()` adds,
match `Wifi is not connected` explicitly, treat `<unknown ssid>` as unknown, and return a distinct
"not connected" state instead of `""`. (2) In `privacyValue`, add `ssid.isEmpty()` to the guard, skip
element 0 (`for (int b = 1; b < blocks.length; b++)`), and match the SSID against the exact
`<string name="SSID">…</string>` element inside one `<Network>` block after XML-unescaping both sides
(`&amp;`→`&`, `&lt;`→`<`, `&gt;`→`>`, `&quot;`→`"`, `&apos;`→`'`) — plain `String.replace`, no
dependency. (3) Return a tri-state `"0"/"1"/"2"/"3"/"?"` and let a positive value only *explain* a
failure, never override it; when the SSID is unknown print
`driver kept it (randomization state unknown)`.

**Acceptance.** A harness calling `privacyValue(storeWhereAllNetworksAre0, "")` returns `"?"` (today
it returns `"1"`). With a store whose SSID is `Ben &amp; Jerry` and a `cmd wifi status` reporting
`Wifi is connected to "Ben & Jerry"`, the row resolves to that network's real setting rather than
`unknown`/`randomized`. `grep -n 'head -3\|1,3p' MainActivity.java` returns nothing.

---

### H6 — K5: the CLI hardcodes offset 4 and verifies by re-reading that offset, so verification is circular
**Owner:** `cli` · **Covers:** K5, plus the unbounded-`dd` and escape-portability evidence
**File:** `cli/macchanger.sh:33-37` (`mac_from`), `:39-41`, `:48-51` (`set_nvram`), `:69-72`, `:96-102`, `:86`

**Evidence.** `mac_from` is `$BB dd if="$1" bs=1 skip=4 count=6 | $BB hexdump …` (`:34-36`) — a fixed
offset. `get_nvram` re-reads the same offset (`:40`), so `:72`'s
`[ "$(get_nvram)" = "$mac" ] || die` compares the write against itself and **can never detect that
the driver ignored it**. `set_nvram` writes blind: there is no precondition on what is currently at
offset 4 — contrast the APK, which requires `hits > 0` before writing (`MainActivity.java:743-746`).
And `dd bs=1 seek=4 conv=notrunc` on a file shorter than the input **extends** it: verified, a 4-byte
file became **14 bytes** after `printf '1234567890' | dd of=… bs=1 seek=4 conv=notrunc`. `get_factory`
reads `$BAK` at the same fixed offset (`:41`), so a different real layout means header bytes are
reported as the factory MAC. `cmd_random` (`:86`) never validates its own output (see L4).

**Correction on the escape pipeline.** `set_nvram` builds `\xHH` escapes (`:49`) and feeds them to
`$BB printf "%b"` (`:50`). `\xHH` in `%b` is a bash/busybox extension, not POSIX, and dash's
**builtin** prints it literally (`dash -c 'printf "%b" "\x41"' | od -An -tx1` → `5c 78 34 31`). But
the shipped code calls **busybox's printf applet**, not the shell builtin, and busybox 1.30.1 here
expands it correctly (`41`). **So the failure is not demonstrated for the shipped path** — the raw
finding's "high/speculative" rating is too strong, and the dash-builtin test cited as evidence does
not exercise the code under audit. The residual risk is real because busybox's hex-escape handling is
build-config dependent, and combined with `count=6` being absent (`:50`) an unexpanded escape would
write 24 literal ASCII bytes from offset 4 — past the MAC field. Fix both anyway; one on-device
command settles it: `busybox printf "%b" "\x41" | od -An -tx1` → `41` = safe, `5c 78 34 31` = broken.

**Impact.** On any MTK variant whose MAC is not at offset 4 — or whose file holds a different record
there — `set` overwrites 6 bytes of calibration data blind, then "verifies" the location it just
wrote; a truncated file is silently extended. `cmd_set` always exits 0, so scripts cannot detect
failure.

**Fix.** Precondition the write: `cur4=$(mac_from "$NV")`, require `[ "$cur4" = "$(get_runtime)" ]`,
and otherwise refuse with the bytes actually found printed — or adopt the APK's approach and scan the
whole file for the runtime MAC bytes (keeping the CLI dependency-free with `$BB hexdump`/`od`).
Generate bytes portably as POSIX octal — verified correct under **both** dash and busybox:
`printf "%b" "\0004\0371\0223\0021\0066\0277"` → `04 f9 93 11 36 bf`; build it with `$BB awk`
(already required at `:86`). Add `count=6`. Verify **non-circularly**: `hexdump` a whole 16-byte
window around the offset before and after and compare against the expected whole-window image, so
the check covers what changed rather than only what was written. Have `cmd_set` exit non-zero when
the post-restart runtime MAC differs from the requested one.

**Acceptance.** On-device, `busybox printf "%b" "\x41" | od -An -tx1` returns `41`. A sandbox run
where the runtime MAC is absent from offset 4 makes `set` refuse and leaves the file byte-identical.
`grep -n 'seek=4' cli/macchanger.sh` shows `count=6` on the same command. `cmd_set` exits non-zero
when the runtime MAC is unchanged after `reinit_wifi` (test with a stubbed `svc`).

---

### H7 — K10: the WiFi restart is blind sleeps around a toggle whose result is discarded, so a legal no-op is reported as the driver refusing the MAC
**Owner:** `java_ui` · **Covers:** K10, plus the airplane-mode evidence
**File:** `MainActivity.java:839-853`, `:763-766`; `cli/macchanger.sh:53-57`

**Evidence.** `reinitWifi` discards every result: `run("svc wifi disable")` (`:840`), `sleep(2500)`
(`:841`), `run("svc wifi enable")` (`:842`), `sleep(7000)` (`:843`), then read the runtime MAC
(`:844`). `svc wifi` is the legacy path and the toggle can be **legally refused**:
`WifiSettingsStore.handleWifiToggled` contains
`if (mAirplaneModeOn && !isAirplaneToggleable()) { return false; }` — verified at
`packages/modules/Wifi/…/WifiSettingsStore.java:257-260` (android16). So with airplane mode on (or
APM "enhance Wi-Fi" off, or an OEM/su policy denying NETWORK_SETTINGS, or a dead wifi HAL — the very
state this app can create), `svc wifi enable` does nothing, the driver never re-reads NVRAM, and the
app reports `runtime = <old> (driver kept it)` (`:764`) or, per H5, the randomization advice. The
blind window is 9.5-16.5 s per operation (2500+7000, plus 1500+5000 on the fallback branch at
`:847`/`:852`) — which is also what makes M2's rotation race reachable. The maintained replacement is
`cmd wifi set-wifi-enabled enabled|disabled`; note it also discards the boolean and returns 0, so
switching commands without verifying state fixes nothing.

**Impact.** The user chases a driver/NVRAM problem that does not exist. On MTK the failed toggle also
triggers the `ip link set wlan0 address` fallback (`:848-850`) that `cli/macchanger.sh:75` documents
as crashing the WiFi service.

**Fix.** Replace both sleeps with polling for the actual state: issue the toggle via
`cmd wifi set-wifi-enabled disabled` (falling back to `svc wifi disable`), then poll the first line
of `cmd wifi status` for `Wifi is disabled` / `Wifi is enabled` every 500 ms up to ~10 s, and only
then read the runtime MAC. If the state never changes, abort the verdict with a specific message
(`WiFi did not toggle — airplane mode on?`) and do **not** run the ip-link fallback. Apply the same
sequencing to `reinit_wifi` (`cli:53-57`), which can poll `/sys/class/net/$IF/operstate` or
`cmd wifi status`. Everything stays in-process: no new permission, no foreground service.

**Acceptance.** With a stub `cmd wifi status` reporting `Wifi is disabled` for the first 3 polls then
`Wifi is enabled`, the app proceeds after ~1.5 s instead of 9.5 s and logs the observed transitions.
With a stub that always reports `Wifi is enabled`, it logs `WiFi did not toggle` and issues **no**
`ip link set` command (assert with a logging stub `su`). `grep -n 'sleep(' MainActivity.java` shows
no fixed sleep on the WiFi-restart path.

---

### H8 — K8: the crash-prone `ip link` fallback is used to fabricate a success verdict and is never labelled non-persistent
**Owner:** `java_write` · **Covers:** K8, plus the false-success evidence
**File:** `MainActivity.java:839-853` (`:848-850`), `:750-758`, `:815-823`; `cli/macchanger.sh:74-75`; `README.md:19`

**Evidence.** `reinitWifi`'s second phase runs
`ip link set wlan0 down; ip link set dev wlan0 address <targetMac>; ip link set wlan0 up`
(`:848-850`) — the exact command `cli/macchanger.sh:74-75` documents as *"Do NOT touch ip link here -
it crashes the WiFi service on MTK"*. The verdict then compares the runtime MAC (`:844-845`, `:756`,
`:820`) and prints a green `runtime = … ok` plus `toast("MAC set: " + now)` (`:757-758`), so an
**ephemeral ip-link spoof is indistinguishable from a persistent NVRAM patch**. `README.md:19`
advertises this fallback as a feature with no caveat, and the only warning lives in an 8-line ring log
collapsed to 1 line (`:293-305`, cap `:345`).

**Impact.** On the Qualcomm/Samsung/Unisoc devices the README claims to support, a spoof whose NVRAM
patch failed is announced as success while nothing persistent changed; after a reboot the MAC reverts
with no explanation. On MTK the fallback can crash the WiFi service — the state the app is least
able to recover from, since its own recovery depends on WiFi coming back.

**Fix.** Gate the fallback behind an explicit opt-in in the SET MAC card (off by default, with the
MTK warning shown verbatim) and run it only when the NVRAM write produced no verified change. When
it runs, the log and toast must say `runtime only — NOT persistent, will not survive reboot`; keep
the green verdict and the `MAC set:` toast **exclusively** for a content-verified NVRAM write. Never
run it when the WiFi toggle did not take (H7) or when a candidate file existed but was unreadable
(M4). If the interface does not exist, report unsupported device rather than attempting it.

**Acceptance.** With `any == false` and an intercepting stub `su`, the log contains `runtime only`
and `NOT persistent`, the toast is not `MAC set:`, and no `ip link set` command is issued unless the
opt-in was enabled. `grep -n 'ip link set' MainActivity.java` shows the command constructed behind
the toggle guard.

---

### H9 — `build.sh` discards javac's exit status, so step [1/6] can never fail
**Owner:** `build` · **Covers:** new (all three build-adjacent auditors found this)
**File:** `app/build.sh:2`, `:5`, `:12-15`, `:17-19`

**Evidence.** `:14-15` — `javac … 2>&1 | grep -v 'source value 8\|target value 8\|deprecat' || true`.
The pipeline status is grep's and `|| true` forces 0, so `set -e` (`:2`) never sees a compile
failure. Reproduced here with JDK 17.0.20 using the real construct, one good and one bad unit:

```
javac -source 8 -target 8 -d out src/Good.java src/Bad.java 2>&1 | grep -v '…' || true
→ PIPELINE exit=0        classes emitted: out/Good.class
javac -source 8 -target 8 -d out src/Bad.java src/Good.java
→ classes emitted: (none — order dependent)
```

**This resolves a direct contradiction between two auditors.** The claim *"javac emits no class files
at all when any compilation unit errors"* is **wrong**; the build lens is right, and the behaviours
are order-dependent. Also confirmed: with `env -u PREFIX` the script prints a javac fatal error and
marches on to `[2/6] dex`, so a missing or mistyped `$PREFIX` surfaces later as a confusing d8/aapt
error. Neither `$AJ` (`:5`) nor `FRAMEWORK=/system/framework/framework-res.apk` (`:6`) is checked for
existence.

**Impact.** With today's single source file a compile error leaves `classes.txt` empty and d8 fails,
so the visible damage is a misattributed error message. But the script is explicitly written for many
files (`find src -name '*.java'`, `:13`), and in that regime a partial class set can be dexed, signed
and announced as `BUILT:` — an installable APK silently missing the classes that failed. Unset
`$PREFIX` is misdiagnosed the same way.

**Fix.** `set -euo pipefail`; capture and test the status without disturbing it:
`out=$(javac … 2>&1); st=$?; printf '%s\n' "$out" | grep -v '…' || true; [ $st -eq 0 ] || { echo "javac failed (see above)"; exit 1; }`.
Simpler and better: drop the pipe and pass `-Xlint:-options` (the README already requires
openjdk-21) so plain `set -e` does its job. Add
`[ -f "$AJ" ] || { echo "android.jar not found: $AJ (is \$PREFIX set?)"; exit 1; }` near `:5` and the
same for `$FRAMEWORK`. Assert the compile produced the expected class:
`[ -s build/classes.txt ] || exit 1` and `[ -f build/classes/com/macchanger/MainActivity.class ] || exit 1`.
Note `set -o pipefail` is bash-specific — the shebang is the Termux bash, so document that the script
must be run as `./build.sh`, not `sh build.sh`.

**Acceptance.** With a deliberate syntax error in `MainActivity.java`, `./build.sh` prints the javac
error, exits non-zero, and does **not** print `BUILT:`. With `env -u PREFIX ./build.sh` it prints the
android.jar message and exits non-zero. `bash -n app/build.sh` passes.

---

## MEDIUM

### M1 — A5: the randomization value space is 0-3, AUTO=3 is the field default, and the app handles only 0/1/2
**Owner:** `java_ui` · **File:** `MainActivity.java:634-648`, `:628-631`, `:707-717`, `:245`, `:629`

**Evidence.** Verified in AOSP: `WifiConfiguration` declares `RANDOMIZATION_NONE = 0`,
`RANDOMIZATION_PERSISTENT = 1`, `RANDOMIZATION_NON_PERSISTENT = 2`, `RANDOMIZATION_AUTO = 3` with
`public int macRandomizationSetting = RANDOMIZATION_AUTO;` (android12:1652-1684, android14:1802-1829,
android16:1879-1906). The app maps only `"0"` (`:708-710`) and `"1"/"2"` (`:711-713`) and treats
everything else as `unknown` (`:715`); `isRandomized()` (`:630`) returns true only for `"1"/"2"`, so
a stored `3` — the default for any configuration that never had an explicit setter — is silently
classified as **not** randomized, even though AUTO defers the decision to the framework at connect
time. Separately, the app only ever reads the connected network (`:629`, `:707`) while the setting is
per saved network, so it cannot warn that a spoof will be overridden the next time the phone joins
another SSID.

**Impact.** The app can tell a user their spoof will reach the router when it will not, and it never
warns about the saved networks that will silently override it later — the most likely way a working
spoof "stops working".

**Fix.** Map `"3"` explicitly to `auto (framework decides)` and treat 1/2/3 as "may randomize" in
`isRandomized()`. Read the store once and enumerate every `<Network>` block (plain root `cat`, no
permission, no resources) so the row can say `randomized on N of M saved networks`, keeping the
existing tap-through to WiFi settings. Combine with H5's tri-state and unescaping.

**Acceptance.** A harness on a store containing a network with `value="3"` returns `"3"` and
`isRandomized()` is true for it. With a store of 3 networks of which 2 are randomized, the privacy
row's text names that count.

---

### M2 — `busy` is an Activity instance field and rotation is enabled, so a config change mid-operation starts a second concurrent operation
**Owner:** `java_ui` · **File:** `MainActivity.java:73-75`, `:723-726`, `:767-770`, `:782-785`, `:832-835`, `:839-853`; `AndroidManifest.xml:8-12`

**Evidence.** `volatile boolean busy` is an instance field (`:73`); the guards read it at `:723` and
`:782` and the clears are in each worker's `finally` (`:768`, `:833`). The manifest declares the
activity with **no** `android:configChanges` and no orientation lock (`:8-12`), so a rotation destroys
the Activity and creates a fresh one with `busy == false` while the old worker keeps running. The
window is large: `reinitWifi` alone is 9.5-16.5 s (H7) plus several su forks before and after, and
the buttons stay enabled. The CLI has no lock at all and reads `$NV` while the app may be inside its
non-atomic write, so its lazy `init_backup` can copy a torn file into the record it never re-takes.

**Impact.** Two workers write two different MACs to the same NVRAM path and interleave `svc wifi` /
`ip link` commands; the value shown and toasted by the loser of the last-write race need not be the
value in NVRAM, and the factory pref can be captured from a runtime MAC the other worker already
spoofed (H2's poisoning, reached by ordinary rotation).

**Fix.** Make the lock process-wide (`static volatile boolean busy`), reflect it in `onCreate` by
disabling/annotating the action buttons while an operation is in flight, and add
`android:configChanges="orientation|screenSize|keyboardHidden"` so rotation does not recreate the
Activity mid-operation (this also keeps the log visible). Add a dependency-free cross-process lock in
the directory both front ends can use — `/data/adb/macchanger/lock`: `mkdir` succeeds or fails
atomically, so acquire with `su -c 'mkdir /data/adb/macchanger/lock'` plus a PID+timestamp staleness
check, release in a `finally`, and use the same lock in `cmd_set`/`cmd_restore`/`init_backup` with a
`trap` for cleanup.

**Acceptance.** Rotating the device during a Set MAC — or an instrumented stub run that completes
`onCreate` a second time while the first worker is alive — does not start a second worker; assert
exactly one su stream and one `reinitWifi` per operation via a logging stub. A second CLI invocation
while the app holds the lock exits non-zero with `another macchanger operation is running` and writes
nothing.

---

### M3 — K11: the log keeps 8 lines, shows 6 when expanded, and never names the patched NVRAM path
**Owner:** `java_ui` · **Covers:** K11 · **File:** `MainActivity.java:339-351` (cap `:345`), `:289-309` (`:304`), `:750`, `:815`

**Evidence.** `while (logLines.size() > 8)` (`:345`) trims to 8, newest first; the expanded bar shows
only `setMaxLines(6)` (`:304`) of those 8. The patch success line is
`log("nvram patched (" + hitsTotal + " hit)", C_OK)` (`:750`) — it never says *which* path was
written, at which offset, or the old→new pair; `restore()` is identical (`:815`). On a device where
several of the nine candidate paths exist, or where two table entries resolve to the same inode, the
user cannot tell what a destructive operation on a calibration partition actually changed. The CLI
does print the NVRAM path and backup location (`:104-110`).

**Impact.** After a Set/Restore the user has no record — not even in the log — of which file was
modified, so a later investigation is impossible, and the `no nvram match` message they may be
staring at is indistinguishable between causes (M4).

**Fix.** java_write emits one log line per path actually written containing path + matched offset(s)
+ old→new (folded into C1). Here: raise the buffer to at least 50 entries, render all retained lines
when expanded (`setMaxLines(logLines.size())` or a fixed larger cap) so nothing retained is
invisible, and add a long-press "copy log" via `ClipboardManager` (no permission). Keep newest-first
and the existing colour scheme.

**Acceptance.** A Set MAC touching two paths produces two log lines, each containing the path string
and an offset. `grep -n 'size() > 8' MainActivity.java` shows the raised cap. Expanding the bar after
10 operations shows more than 6 lines.

---

### M4 — A read/write denial or a failed root probe is reported as "no nvram match · runtime only", i.e. as if the device were unsupported
**Owner:** `java_ui` · **Covers:** new (both the platform and shell lenses reached this)
**File:** `MainActivity.java:529-537`, `:738-751`, `:444-451`, `:423-442` (`:438`), `:794-816`, `:659-663`

**Evidence.** `existingNvram()` tests only existence (`[ -e "$p" ]`, `:531`) and keeps only lines
starting with `/` (`:534`), so a refused enumeration returns an empty list. `readRoot` returns
`d.length == 0 ? null : d` (`:438`), making *"file is empty"*, *"cat failed (ENOENT/EACCES)"* and
*"su produced no output"* all indistinguishable `null`; every caller then does
`if (data == null) continue;` (`:740`, `:796`). Every probe pipes stderr to `/dev/null`
(`:615`, `:622-623`, `:670-675`) and `readRoot` drains and **discards** stderr on its own thread
(`:426-430`), so an EACCES/EROFS/SELinux denial never reaches the log. `writeRoot` returns
`back != null && Arrays.equals(back, data)` (`:450`), so a *failed verification read* makes a write
that actually landed return false → `any` stays false (`:746`) → the UI prints
`no nvram match · runtime only` (`:751`, `:816`) while the calibration file **was** in fact modified.
With su denying, `run("id")` lacks `uid=0` (`:659`) so the chip says "not granted" — but the operation
still runs its full 9.5-16.5 s WiFi restart and prints that same neutral line.

**Impact.** Three materially different situations — unsupported SoC layout, root/permission denial,
and a *successful* patch whose readback failed — produce one identical message, on a tool whose whole
job is destructive writes. The worst direction is a successful patch reported as "no nvram match":
the user retries or concludes the app is broken while the partition already carries the new MAC. It
also hides real permission failures and then performs the risky fallback (H8).

**Fix.** Give the shell layer an explicit status and a per-path report. Probe each candidate in one
su round trip and print per-path state —
`for p in …; do if [ -e "$p" ]; then r=ok; w=ok; [ -r "$p" ] || r=denied; [ -w "$p" ] || w=denied; echo "$p r=$r w=$w"; else echo "$p absent"; fi; done`
— plus the mount options of the containing filesystem from `/proc/mounts` so a read-only mount is
visible. Then distinguish four outcomes in the final message: no candidate on this device (name the
paths probed); candidate found but not readable/writable (name the errno or mount option); patched
(name the path); patched but readback unreadable → red
`patched but verification unreadable — re-check before rebooting`. Abort before any WiFi restart when
the enumeration itself failed, and never run the ip-link fallback then. Cheap partial step needing no
refactor: if `!any` and `!run("id").contains("uid=0")`, print `no root — nothing was attempted`.

**Acceptance.** With a stub su that denies reads, the log names the paths probed and the denial
instead of `no nvram match`, and no WiFi restart is issued. With a stub where the write succeeds but
the verification read returns empty, the log says `patched but verification unreadable` rather than
`no nvram match`. A genuinely unsupported device prints the distinct no-candidate message with the
probed list.

---

### M5 — The detected vendor never affects behaviour, there is no unsupported-device verdict, and targetSdk 30 must deliberately stay
**Owner:** `java_ui` · **Covers:** new, plus a verified **negative result** about `targetSdk`
**File:** `MainActivity.java:468-491`, `:515-527`, `:696-697`, `:750-751`, `:839-853`; `AndroidManifest.xml:6`, `:7-12`; `README.md:6`, `:14-19`

**Evidence.** `socName()` classifies MediaTek/Qualcomm/Samsung/Unisoc from properties, but its only
consumers are `vSoc.setText(soc)` and `socColor(soc)` (`:696-697`); `nvramPaths()` returns the same
nine candidates in a fixed order for every device (`:515-527`) and `existingNvram()` filters on
existence only (`:531`). When nothing matches, the code logs `no nvram match · runtime only` (`:751`)
and proceeds into the ip-link fallback (`:848-850`). The vendor string is decoration, so the tool
cannot distinguish "no known NVRAM file, nothing was changed" from "we changed something and the
driver ignored it".

**Negative result — the install-block suspicion is not real, and raising targetSdk is a trap.**
Verified in AOSP: `MIN_INSTALLABLE_TARGET_SDK` is `Build.VERSION_CODES.M` (23) on android14
(`PackageManagerService.java:570`) and `Flags.minTargetSdk24() ? N : M` (24) on android15:565-566 /
android16:570-571, and the "built for an older version of Android" dialog threshold
`Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT` resolves to 28. So targetSdk 30 **installs and launches
cleanly with no dialog on Android 14-16**. Raising it to 35/36 buys nothing and would enforce
edge-to-edge on Android 15+, which this hand-built layout cannot absorb: it uses fixed paddings
(`:192`, `:297-299`) and has no inset handling anywhere (no `WindowInsets`, no
`setOnApplyWindowInsetsListener`, no `configChanges` in the manifest), so the title row and log bar
would sit under the status/nav bars; Android 16 drops the opt-out.

**Impact.** A user outside the one tested device gets no explicit verdict, then a risky fallback with
no gate. And a reader who "fixes" the stale-looking targetSdk 30 breaks the layout and gains nothing.

**Fix.** Use `socName()` for behaviour: order/filter the candidate list per detected vendor and record
the vendor in the log. Add an explicit terminal state — when `existingNvram()` is empty, or no path
contains the runtime MAC, show
`UNSUPPORTED DEVICE — nothing was written. Looked for: <paths with missing/unreadable/MAC-not-found>`
built from M4's per-path report, and require H8's explicit opt-in before any ip-link fallback. Record
in the manifest comment and README *why* targetSdk 30 is deliberate (installs on 14-16; raising it
requires adding inset padding). Java-only, existing helpers, no permission, no `res/`.

**Acceptance.** On a stub device with none of the nine paths present, the UI shows the
`UNSUPPORTED DEVICE` state listing the probed paths and issues no WiFi restart and no `ip link`
command. With a MediaTek soc string, MTK paths are probed before Qualcomm/Samsung/Unisoc and the log
names the vendor. `grep -n targetSdkVersion app/AndroidManifest.xml` still reads 30, with an
explanation present.

---

### M6 — The CLI rewrites NVRAM even when the runtime MAC is unknown, and `wlan0` is hardcoded in both front ends
**Owner:** `cli` · **File:** `cli/macchanger.sh:16`, `:39`, `:48-51`, `:69-72`, `:79`; `MainActivity.java:45`, `:459-461`, `:670`

**Evidence.** `IF=wlan0` (`:16`) is used only by `get_runtime()` (`:39`) for display; `set_nvram()`
(`:48-51`) writes offset 4 unconditionally and `cmd_set` verifies by re-reading that same offset
(`:72`), so nothing confirms the interface reported as "runtime MAC" is the one this NVRAM entry
feeds. When wlan0 does not exist, `:79` prints an empty runtime MAC and the script reports success.
The app degrades more safely — a non-MAC runtime value makes `ob == null` so `setMac` aborts before
any write (`:732-734`) — but it too hardcodes `IFACE = "wlan0"` (`:45`) for the read (`:460`),
refresh (`:670`) and fallback (`:848-850`).

**Impact.** Where the station interface is not wlan0 (concurrent AP/STA, hotspot tethering renaming
the station, ROMs exposing only p2p0 early in boot), the CLI rewrites the calibration MAC and reports
success with a blank runtime MAC, and H6's circular verification cannot catch it; the app reports the
opaque `cannot parse MAC`.

**Fix.** Resolve the interface instead of assuming it — derive from `cmd wifi status` / `iw dev` /
`ls /sys/class/net | grep '^wlan'` — and `die "cannot determine the WiFi interface; use --iface=NAME"`
when zero or more than one candidate exists unless explicitly forced. Add `--iface`. In the app,
detect the same way, show the interface in the DEVICE card, and use it for the address read, the
refresh and the fallback so the failure is self-describing.

**Acceptance.** With a stub environment where no `wlan*` interface exists, `macchanger.sh set …`
refuses with the interface message and leaves the NVRAM's content and mtime unchanged. With two
`wlan*` interfaces it refuses unless `--iface` is given. The app displays the detected interface name.

---

### M7 — Shell failure text is returned as data and shown as the MAC; root detection cannot tell "denied" from "not installed"; paths are pasted into a root shell
**Owner:** `java_core` · **File:** `MainActivity.java:367-381` (`:370`, `:379`), `:459-461`, `:670`, `:654-666` (`:659`), `:423-442` (`:425`), `:444-451` (`:447`), `:506-513` (`:510`), `:801-812`, `:839-853`

**Evidence.** `run()` merges stderr into stdout (`:370`) and returns `"ERR " + t` on failure (`:379`).
`getRuntimeMac()` (`:460`) runs `cat /sys/class/net/wlan0/address` with **no** `2>/dev/null`, while
`refresh()`'s duplicate of the same read (`:670`) has it — two implementations of one operation that
disagree. So when WiFi is off or the interface is renamed, `getRuntimeMac()` returns
`cat: /sys/class/net/wlan0/address: No such file or directory`, which becomes the runtime row
(`:691`), the log `runtime = cat: … (driver kept it)` (`:764`) and `toast("driver gave cat: …")`
(`:765`). With su denied the row shows `su: permission denied`. With no su binary at all,
`run("id")` returns the ERR string, `contains("uid=0")` is false (`:659`), the chip says
"not granted" and the log says "root not granted" — telling the user to grant a permission that is
impossible on that device, while `ProcessBuilder("su", …)` relies on the PATH the Zygote app process
inherits rather than probing an absolute path (Magisk's su also lives at `/debug_ramdisk/su` on
Android 11+).

**Latent injection.** `pathsCsv()` single-quotes paths with no `'\''` handling (`:510`) and those
strings are pasted into root commands (`:425`, `:447`); `restore()` reads a path back out of a
`.path` text file and re-uses it with no re-validation beyond "the line started with `/`"
(`:534`, `:808-812`).

**Negative results worth keeping.** (1) `isMac()` (`:596-598`) rejects every realistic failure string
— tested against `su: permission denied`, the ERR/IOException text, the `cat` ENOENT text and empty
— so no error text can reach `macToBytes()` and none can be written to NVRAM or stored as the factory
pref. The "factory pref poisoned by garbage" variant is **not real**. (2) Nor is injection exploitable
in the shipped build: every path traces to the hardcoded `nvramPaths()` constants (`:517-525`), no
UI/config/network value reaches a shell, and the MAC alone is regex-gated by `isMac()` at `:725`/`:784`
before use at `:760`/`:848`. It is latent only.

**Impact today.** On a phone with no Magisk/KernelSU the user is told to grant root that cannot be
granted, with nothing indicating the app cannot function at all. On a rooted phone where WiFi is off,
every failure is narrated as the *driver* refusing the MAC — sending the user after the wrong cause
on a tool that has just rewritten a calibration partition. The latent injection becomes live the
moment a candidate path is made configurable or derived from a `getprop`.

**Fix.** (1) Return status, not text: a small
`static final class Res { final int code; final String out; boolean ok(); }` from
`run()`/`readRoot()` (`Process.exitValue()` is already available after `waitFor`). (2) At every
display site, `if (!isMac(rt)) { log("cannot read " + IFACE + " (WiFi off, or root denied)", C_BAD); return; }`
instead of rendering the error as a MAC. (3) Make `checkRoot` distinguish three outcomes by probing
`/system/bin/su`, `/debug_ramdisk/su`, `/sbin/su` then PATH and passing the resolved absolute path to
`ProcessBuilder`: found+exit0 = granted; found+exit≠0 = `root denied`; not found =
`su is not installed — this app cannot work on this device`. (4) Delete the duplicate read so
`getRuntimeMac` and `refresh` cannot diverge. (5) Harden: never interpolate a path into shell text —
`new ProcessBuilder("su", "-c", "cat -- \"$0\"", path)` for reads and
`… "cat -- \"$0\" > \"$1\"", tmp, path` for writes — plus a whitelist gate
`if (!nvramPaths().contains(path)) return false;`, re-checked after reading a `.path` file in restore.

**Acceptance.** A stub su that denies makes the runtime row show a clear
`cannot read wlan0 (WiFi off, or root denied)` and never the stderr text, and no toast reads
`driver gave <error text>`. With no su binary at all the UI says "su is not installed" rather than
"not granted". A stub `/system/bin/su` that exists but exits 1 yields "root denied".
`grep -n "cat '" MainActivity.java` returns no path interpolated into shell text, and
`grep -n 'nvramPaths().contains' MainActivity.java` shows the write whitelist.

---

### M8 — No build identity and no post-sign verification
**Owner:** `build` · **Covers:** new (build + security lenses)
**File:** `AndroidManifest.xml:4-5`, `:7`; `app/build.sh:22-24`, `:28`, `:38-42`

**Evidence.** Manifest `:4-5` are literals (`versionCode="1" versionName="1.0"`) that `build.sh` never
rewrites — `:22` passes `AndroidManifest.xml` to aapt unchanged — and the app displays only the OS
version (`:698`), never its own. The prebuilt APK's parsed manifest confirms versionCode 1 /
versionName "1.0" / minSdk 21 / targetSdk 30 with **no** `<uses-permission>` element, so the
zero-permission property holds in the artifact and not just the source; `icon` appears nowhere, so
the launcher, the Android 12+ splash and the recents card show the generic system icon. After
signing, nothing inspects the APK: `:24` is `aapt add … >/dev/null`, `:28` `zipalign -f 4`, `:40`
`apksigner verify`, and `:41-42` only echo the path and `ls -la` — no `unzip -l`, no `zipalign -c`,
no `aapt dump badging`, no `sha256sum`. There is no `trap`, so a failure after `:38` leaves a
plausible-looking `app-signed.apk` on disk.

**Impact.** Two builds of different sources are indistinguishable on-device — which matters most for
a root tool that rewrites calibration partitions and whose keystore ships (C5): a user cannot tell
whether the installed APK matches the source they audited. And a build whose compile half-failed
(H9) or whose `aapt add` misplaced the dex is announced as `BUILT:` with no hash to compare.

**Fix.** Stamp identity at build time via aapt
(`--version-code $(date +%Y%m%d) --version-name 1.0+$(git rev-parse --short HEAD 2>/dev/null || echo nogit)`),
print `sha256sum "$OUT"` and `keytool -printcert -jarfile "$OUT"` at the end, and display the app's
own `versionName` + `lastUpdateTime` from `getPackageManager().getPackageInfo(getPackageName(), 0)`
in the DEVICE card (API 21-safe, no permission, no resources). Add a launch icon **without** a `res/`
directory by referencing a framework drawable: `android:icon="@android:drawable/ic_menu_manage"`.
Add cheap post-sign gates — `aapt list "$OUT" | grep -q classes.dex || exit 1`,
`zipalign -c -v 4 "$OUT" || exit 1`, `aapt dump badging "$OUT" | grep -q launchable-activity || exit 1`
— and wrap the post-sign region in `trap 'rm -f "$OUT"' ERR` (or sign to a temp name and `mv` after
verification). **Use aapt/busybox `unzip` rather than a bare `unzip`**, or a missing tool breaks the
on-device build.

**Acceptance.** `./build.sh` prints the APK SHA-256 and the signer certificate SHA-256. The DEVICE
card shows a version string that changes between builds. `aapt dump badging` reports an application
icon. Removing `classes.dex` from the aligned APK by hand makes the script fail at the new gate
instead of printing `BUILT:`.

---

### M9 — No offline regression harness exists, so every fix in this plan would be unverifiable and unprotected against reintroduction
**Owner:** `tools` · **Covers:** new (synthesizer)
**File:** new files under `tools/` (e.g. `tools/verify.sh`, `tools/extract_logic.py`, `tools/cli_test.sh`), optional `tools/README.md`

**Evidence.** Every defect in this plan was verified by *hand-building* throwaway harnesses: Java
harnesses compiled against hand-written `android.*` stubs with the real method bodies extracted from
`MainActivity.java`, and a constants-redirected copy of `cli/macchanger.sh` run against synthetic
NVRAM files with stubbed `busybox`/`svc`. None of that is in the repo, so nothing prevents a fix from
silently regressing — and with no aapt/d8/apksigner in this environment the rebuild path cannot be
exercised at all.

**Impact.** For a tool that rewrites calibration partitions, the verification story is currently
"read it very carefully"; a future contributor has no way to re-run the checks that found these bugs.

**Fix.** Add a dependency-free harness that runs fully offline and touches no app/CLI logic:
- `tools/extract_logic.py` — pulls the pure methods (`isMac`, `macToBytes`, `bytesToMac`, `hexPlain`,
  `reverse`, `replaceAll`, `patch`, `privacyValue`) **verbatim** out of `MainActivity.java` into a
  generated Java file, so the harness tests the shipped source text rather than a stale copy.
- `tools/logic_test.java` (generated) — compiles against minimal `android.*` stubs and asserts the
  invariants this plan depends on: degenerate all-zero/all-`0xFF` patterns are refused; `patch`
  preserves length and every byte outside match windows; `patch(x,x)` reports zero byte changes;
  `privacyValue(store, "")` is `"?"`; uppercase and plain-hex ASCII copies are handled; `replaceAll`
  never overruns.
- `tools/cli_test.sh` — synthesises NVRAM files and runs a constants-redirected copy of
  `cli/macchanger.sh` with stub `busybox`/`svc`, asserting: no implicit backup creation from
  `show`/`restore`; restore refuses a size-mismatched backup; `set` refuses when the target offset
  does not hold the runtime MAC; the file survives byte-identically on every refusal.
- `tools/readme_claims_test.sh` — greps the claims this audit corrected (keystore password in docs,
  `head -3`, `pass:android`, `targetSdkVersion`, the CLI's MTK-only scope) so doc/code drift is caught.

javac + sh + busybox/od only. No Gradle, no network, no Android SDK.

**Acceptance.** `sh tools/verify.sh` runs on this machine with only JDK 17 + busybox and exits 0 on
the fixed tree. Run against a copy of `/root/macchanger-fixed/.pristine` it fails with a specific
message for each of C3, H3, H5 and H6 — proving the harness detects the defects it guards.

---

## LOW

### L1 — README claims the code does not support, and omits what a root tool needs
**Owner:** `docs` · **File:** `README.md:14`, `:19`, `:22-23`, `:35`, `:47`, `:54-64`, `:96-107`, `:113`; `cli/macchanger.sh:2`, `:15`, `:24`, `:74-75`

**Evidence.** (a) `:14` "Detects multiple vendors" is stated for the product, but
`cli/macchanger.sh:2` says "for MediaTek (MTK) Android", `:15` hardcodes
`NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI` and `:24` `die`s "NVRAM file not found" — the README never
says the CLI is MTK-only, so a Qualcomm/Samsung/Unisoc user's documented
`sh cli/macchanger.sh set …` is a dead end. (b) `:19` advertises the runtime `ip link` fallback
without the caveat `cli/macchanger.sh:74-75` states in capitals. (c) `:22-23` promise randomization
detection; H5 shows the SSID can never be resolved on Android 12+ and that an unresolved SSID is
reported as randomized. (d) `:35` documents `ks.jks` with its password as part of the shipped layout
(C5). (e) `:47` and `:54-64` contradict each other: `pm install -r` assumes one signing identity
while the self-build path guarantees a different one. (f) `:56-58` name `openjdk-21` and an
android.jar in `$PREFIX/share/java` but never the required API level. (g) `:113` ("The app always
copies the original NVRAM before writing") is **false** on the path C2 identifies.

**Impact.** Misleading documentation is the main way a tool with this risk profile ends up with a
damaged calibration partition; a non-MTK user gets a hard dead end with no explanation, and a
self-builder produces an APK that cannot update the installed one.

**Fix.** State up front "CLI = MediaTek only; APK = candidate paths for MTK/Qualcomm/Samsung/Unisoc,
tested only on MTK". Move the ip-link sentence out of "What it does" and mark it a last-resort
fallback that can kill the WiFi service on MTK and does not survive a reboot. Soften the
randomization claim to "attempts to detect, and cannot on Android 12+ without a connected network".
Delete the keystore password line, document the required android.jar API level and the two signing
identities, and correct `:113` after C2. Add: supported devices and an explicit Android ceiling,
per-vendor risk, what to do when the backup is lost, how to verify the APK by hash and signer
fingerprint, what to do when WiFi stops working entirely, and M5's deliberate targetSdk 30 note.
**Ordering:** this is the last stage — do not publish a certificate fingerprint before `build` has
stopped shipping `ks.jks`.

**Acceptance.** `grep -n 'pass: android\|pass:android' README.md` returns nothing. The README
contains "MediaTek only" next to the CLI section and "does not survive a reboot" next to the ip-link
mention. The randomization section states the Android 12+ limitation. The build section names an
android.jar API level and both signing identities.

---

### L2 — No LICENSE, SECURITY.md or CHANGELOG, and the version stamp is frozen
**Owner:** `docs` · **File:** `README.md:27-40`; repository root; `AndroidManifest.xml:4-5`

**Evidence.** `git ls-files` returns exactly seven files and none of LICENSE, SECURITY.md,
CHANGELOG.md or release notes; the single commit is "pristine MacChanger as shipped". The prebuilt
APK's compiled manifest confirms versionCode 1 / versionName "1.0", so no shipped APK carries a
distinguishable version.

**Impact.** Absent a license, default copyright applies and nobody may legally redistribute a
modified build — which blocks mirrors, ROM packagers, and any downstream maintainer from fixing C5/C4
in a redistributable way. Absent SECURITY.md there is no channel for a stranger to report the shipped
signing key. With versionCode pinned at 1 a user cannot tell a patched build from the shipped one.

**Fix.** Add `LICENSE` (MIT or GPL-3.0), `SECURITY.md` (contact + scope: "this app runs as root and
rewrites Wi-Fi calibration; reports about the shipped signing key are security issues") and
`CHANGELOG.md` keyed to versionCode, with the APK SHA-256 and signer certificate SHA-256 per entry.
Bump versionCode/versionName for every APK added to `prebuilt/` — the manifest is the only version
metadata an APK carries here — and state which source commit the shipped APK corresponds to. (The
current APK does verifiably match the current source: its dex contains the distinctive source
literals such as `nvram patched`, `MacRandomizationSetting`, `write.tmp`, `nvram_backup` and
`ip link set dev`. Nothing records that fact, which is the gap.)

**Acceptance.** `git ls-files` shows LICENSE, SECURITY.md and CHANGELOG.md. The newest changelog
entry names the APK SHA-256 and signer SHA-256 and matches `sha256sum prebuilt/MacChanger.apk`
(currently `1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da`). The README names the
source commit for the shipped APK.

---

### L3 — No intended-use/authorization statement
**Owner:** `docs` · **File:** `README.md:10-24`, `:96-107`, `:111-116`; `cli/macchanger.sh:65-67`, `:81`

**Evidence.** The README documents capabilities — random and arbitrary MACs (`:72-74`, `cli:86`), a
change that survives reboots (`:20`) and, because it edits the vendor NVRAM rather than the framework,
survives a factory reset — but never states who may use them. The CLI's own output acknowledges the
MAC is a credential (`your whitelisted WiFi will drop unless this MAC is whitelisted`, `cli:81`) and
warns about multicast (`cli:65-67`). Factually the tool contains no attack primitives against
anything but its own NIC — no scanning, deauthentication, injection, or cloning of observed
third-party MACs — and its legitimate uses are real: restoring a corrupted or zeroed factory MAC
after a bad flash or an earlier spoof, replacing a MAC burned into a captive-portal or asset database,
privacy on networks you own, testing your own AP's MAC filters, hardware diagnostics. What crosses
the line is the combination of an arbitrary/random MAC with persistence below the OS privacy controls
and beyond a factory reset — credential substitution against MAC-based access control and per-device
billing.

**Impact.** Users cannot tell whether this is a privacy utility or an access-evasion tool, and the
Safety section covers only calibration risk, not misuse, so a user has no stated basis for judging
the act or the risk.

**Fix.** Add a short "Intended use / authorization" section stating factually: intended for devices
you own and networks you are authorized to use (privacy, repair of a corrupted/zeroed factory MAC,
testing your own equipment); the change is persistent, invisible to Android's per-network
randomization setting, survives a factory reset, and only this app/CLI can undo it (with the recovery
steps from C4/H1); do not use it to evade per-device billing, MAC allow/deny lists, captive-portal
limits, or a block imposed by an operator who does not own the device, and note that some
jurisdictions treat that as a computer-misuse offence. Keep the existing CLI multicast warning as the
in-code model.

**Acceptance.** The README contains an "Intended use" (or equivalent) heading whose text states the
ownership/authorization boundary, the persistence-beyond-factory-reset property, and the recovery
steps. The section is prose only — no code, permission or dependency change.

---

### L4 — CLI diagnostics: `random` does not validate its own output, and a missing busybox blames the NVRAM
**Owner:** `cli` · **File:** `cli/macchanger.sh:18`, `:86-88`, `:72`

**Evidence.** `cmd_random` (`:86`) captures `r` from `$BB od … | $BB awk …` with no `pipefail` and no
validation, so with a missing or broken `$BB od` it prints `[*] random MAC: ` (empty) and then dies
with `error: usage: <script> set AA:BB:CC:DD:EE:FF` — the wrong diagnostic for a working code path
(harmless, since `valid_mac` stops it before any write). And `:18` `[ -x "$BB" ] || BB=busybox` means
that if neither `/data/adb/magisk/busybox` nor a busybox on PATH exists, the only symptoms are two
"not found" lines and then `error: NVRAM write verification failed`, which blames the NVRAM for a
missing tool.

**Impact.** A user with a broken busybox is sent to troubleshoot a calibration partition they did not
damage; a broken `od` produces a confusing usage error.

**Fix.** Capture into `r` then `valid_mac "$r" || die "cannot generate a random MAC (busybox od/awk
failed)"`. After `:18`, fail early with the real cause:
`$BB true 2>/dev/null || die "busybox not found: install it or set BB="`. Pure shell, dependency-free,
existing flow preserved.

**Acceptance.** With `BB` pointed at a nonexistent binary, every subcommand dies immediately with the
busybox message and no NVRAM path is touched. With a stubbed failing `od`, `random` prints the
od/awk message rather than a usage error.

---

### L5 — Screen privacy: the factory MAC and connected SSID land in the recents snapshot
**Owner:** `java_ui` · **File:** `MainActivity.java:231-234`, `:244`, `:289-309`, `:353-363`

**Evidence.** The factory and runtime MAC rows (`:231-234`) and the SSID row (`:244`) are plain
`TextView`s, and the log bar renders the same values (`:353-363`) with no `FLAG_SECURE` anywhere in
the file. The values therefore appear in the recents/task-switcher snapshot and in any screen
recording. Nothing leaks them off-device — the only file-write sinks are app-private (`:412-421`,
`:600-612`), there is no `android.util.Log`, `System.out` or `printStackTrace` anywhere, and the
manifest requests no permissions — but for a tool whose purpose is to stop exposing the hardware MAC
that is an inconsistency.

**Impact.** A user who spoofs their MAC for privacy can still expose the factory MAC and their SSID
in a screenshot, a screen recording, or the recents preview shown to a bystander.

**Fix.** One line in `onCreate` **before** `setContentView`:
`getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);`
No permission, no dependency, no `res/`, API 1. Optionally a toggle, default on.

**Acceptance.** `grep -n FLAG_SECURE MainActivity.java` finds it in `onCreate` before
`setContentView`. On a device, the recents preview shows a secure card and screenshots are refused.
This is the one finding in the plan that cannot be fully proven without a device; code inspection
plus documented platform behaviour is the ceiling here.

---

### L6 — The root state machine is decorative: `lastRoot` is write-only, no-arg `checkRoot()` is dead, the badge tap can never refresh
**Owner:** `java_ui` · **File:** `MainActivity.java:74`, `:216`, `:652`, `:654-666`, `:722-726`, `:781-785`, `:845`

**Evidence.** `volatile boolean lastRoot = false;` (`:74`) is assigned once (`:661`) and never read
anywhere in the file. The no-arg overload `void checkRoot() { checkRoot(true); }` (`:652`) has no
callers. The clickable root badge calls `checkRoot(false)` (`:216`), and the refresh only happens
`if (granted && thenRefresh) refresh();` (`:664`), so granting root via the badge leaves
vRuntime/vFactory/vModel/vSoc/vNetwork stuck at "..." with no hint that a manual Refresh is needed.
`setMac`/`restore` gate only on `busy`, never on `lastRoot` (`:723`, `:782`), so an unrooted run
still performs the full 9.5-16.5 s blind WiFi restart and reports `no nvram match · runtime only`
instead of "root required". With su denied, `run()` returns the sentinel `"ERR " + t` (`:379`) which
`reinitWifi` compares as if it were a MAC (`:845`), entering the fallback branch and burning an extra
`sleep(5000)` (`:852`).

**Impact.** Nothing corrupts — `writeRoot`'s read-back (`:449-450`) correctly refuses to claim
success — but the state machine misleads: after granting root the app looks broken until the user
guesses to press Refresh, and an unrooted run performs destructive-looking work instead of refusing.
The dead field and overload show the intended guard and refresh were never wired.

**Fix.** Read `lastRoot` at the top of `setMac()` and `restore()`: if `!lastRoot`, call
`checkRoot(true)` and refuse with "root required" instead of running the blind restart. Change the
badge handler (`:216`) to `checkRoot(true)` so a grant refreshes the cards. Delete the unused no-arg
`checkRoot()` (`:652`) and either use `lastRoot` or remove it. Make `reinitWifi` treat a value that
fails `isMac()` as unknown and skip the fallback (H7/M7).

**Acceptance.** With su denied, tapping Set MAC logs/toasts "root required" and issues no WiFi restart
and no NVRAM write (assert via a logging stub `su`). Tapping the root badge on a device that then
grants root populates the cards without a manual Refresh. `grep -c lastRoot MainActivity.java` shows
it both read and written, and `grep -n 'void checkRoot()' MainActivity.java` returns nothing.

---

## Device risk

These are the changes in this plan that can damage a device or break the on-device build if
implemented naively. They are constraints on the implementation, not findings.

1. **Adding a watchdog/timeout to the write path before C1 lands is the single most dangerous
   ordering mistake.** `java_core` runs *before* `java_write`. Killing `su` after the shell has
   opened the destination with `O_TRUNC` but before it finishes truncates the calibration file — the
   timeout would cause the bug it is meant to prevent. `java_core` must apply its deadline to
   `run()`/`readRoot()`/`getprop()` and **explicitly exempt `writeRoot`'s command**; only `java_ui`,
   after `java_write` has replaced the primitive with `dd conv=notrunc` plus a size pre-check, may
   extend the deadline to the write.
2. **Never `mv` a rewritten file into place.** K4 is right: rename brings the temp file's SELinux
   label and needs create/rename permission and the correct type transition in
   `/mnt/vendor/nvdata`, `/persist`, `/efs`, `/data/vendor`. Keep the same inode — truncate-and-rewrite
   (better: seek-and-write with `conv=notrunc`).
3. **Never restore with `cp`/`cat >`.** Both truncate to the source length. Use `dd if=… of=… bs=4096
   conv=notrunc,fsync` after a `wc -c` size equality check, then `cmp -s`.
4. **Do not "fix" the offset by hardcoding a different one.** A wrong offset writes into header or
   calibration fields. Keep scanning, and add the precondition that the bytes at the matched offset
   equal the current runtime MAC.
5. **Do not raise `targetSdk`.** Verified installable and undialogued at 30 on Android 14-16 (M5).
   Raising it to 35/36 enforces edge-to-edge on Android 15+ and this hand-built layout has no inset
   handling, so the title and log bars would sit under the system bars — a regression with no gain.
6. **Do not add a foreground service to survive the long write.** It requires
   `FOREGROUND_SERVICE` (plus a type on API 34+), which destroys the zero-permission property that is
   a genuine security feature of this design. Use `configChanges` + a static `busy` flag, and
   optionally the `mkdir`-based on-disk lock.
7. **Relocating the factory record to `/data/adb` multiplies `su` round trips.** Each is an
   independent prompt/timeout chance, so batch the reads and writes into as few invocations as
   possible. Create it privately (`mkdir -m 700`; `umask 077` in the CLI) — never let `mkdir -p` leave
   `/data/adb` world-traversable, and keep the app-private mirror as a fallback for roots that have no
   `/data/adb`.
8. **Do not set C3's replacement cap too low.** A legitimate file can hold a raw copy, a reversed copy
   and two ASCII copies; a cap like 4 would silently refuse to patch real devices. Count and log the
   distinct match offsets and refuse only above a threshold validated against a real device dump.
9. **Tightening `isMac()` must not reject the app's own Random output.** `randomMac()` sets bit
   `0x02` (locally administered), so a naive "reject locally administered addresses" rule breaks the
   Random button. Reject only all-zero, all-`0xFF`, multicast-as-a-*target*, and apply the
   locally-administered rejection **only** to the captured factory value (H2), not to the target.
10. **Removing the CLI's implicit backup changes behaviour users may rely on.** The new explicit
    `backup` subcommand must be documented, and refusing to write when no backup exists must not
    leave the user unable to restore a *known* factory MAC — hence the manual-entry path (H1) and the
    `--assume-factory` acknowledgement (C4).
11. **XML unescaping must be conservative.** Unescape both sides of the SSID comparison, but do not
    URL-decode, trim internal whitespace or case-fold — over-eager normalisation can match a
    *different* network and re-introduce the wrong-block bug H5 fixes.
12. **New post-sign gates in `build.sh` must not add a tool dependency.** Prefer `aapt list` and
    `zipalign -c` (both already required) over a bare `unzip`; if a helper is missing, print a clear
    message instead of failing obscurely, or the on-device build breaks for users who have the
    documented packages.
13. **`set -o pipefail` is bash-specific.** The shebang is the Termux bash, so it is fine for
    `./build.sh`; document that the script must be run that way, not `sh build.sh`.
14. **Do not withdraw `app/ks.jks` from distribution before the recovery path is durable.** A user
    who has installed the shipped APK and then builds with a different key hits
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only fix is uninstall — which deletes the app's
    backup. C5's loud refusal must be unmistakable, and H1/C2 (durable records under `/data/adb` plus
    export and manual entry) should land first or together.
15. **Do not rebuild or re-sign the APK in this environment.** There is no aapt/d8/apksigner/
    zipalign here, so `prebuilt/MacChanger.apk` must be left exactly as it is; the author rebuilds
    on-device after the fixes land.

---

## Not fixing, and why

- **`targetSdk 30`** — deliberately kept. Verified above: it installs and launches without a
  deprecation dialog on Android 14, 15 and 16, and raising it breaks the hand-built layout. Document
  the reason rather than changing the value (M5).
- **`allowBackup="false"`** — correct as-is. Do not "fix" it by enabling Android backup of a hardware
  MAC record; the fix for durability is H1's explicit, user-controlled records.
- **The zero-permission manifest** — a genuine security property, verified in the shipped artifact
  (no `<uses-permission>` element). No foreground service, storage permission, network permission or
  `res/` may be added. Export of the backup goes through the existing `su` channel; the icon comes
  from a framework drawable.
- **`android:exported="true"` on the launcher activity** — required for a MAIN/LAUNCHER entry and
  safe here: `MainActivity` never reads an `Intent`, takes no extras, and has no exported service or
  receiver, so no other app can drive a root action through it. Do **not** add an extras/ACTION API;
  if one is ever added, it must never interpolate caller-supplied text into `run()`.
- **`patch()`'s reversed-byte pass** (`reverse(oldB) -> reverse(newB)`) — correct for a byte-swapped
  vendor layout, not an endianness bug. Keep it; only add case-insensitive ASCII matching and the C3
  guard.
- **`reverse()`/`bytesToMac()`/`hexPlain()`/`macToBytes()` semantics** — verified correct for 6-byte
  input; `macToBytes` is lax (`Integer.parseInt` accepts `"0"`) but every caller is gated by
  `isMac()` or reads `/sys`, so hardening is optional. Not planned, to keep the diff small.
- **`String.format("%02x", …)` locale concerns** — verified not a hazard. `%x` is not
  digit-localised, so `bytesToMac`/`hexPlain`, the NVRAM search patterns and `isMac()` are
  locale-independent. The only real effect is `id.toUpperCase()` (`:488`) mangling an SOC name
  containing `i` under a Turkish locale ("Dimensity" → "DİMENSİTY") — cosmetic, not worth a change.
- **The `2>/dev/null` on `configStore()`** — verified *not* defeated by `run()`'s merged stderr:
  `2>/dev/null` suppresses even the shell's own "command not found" message in dash and bash (0 bytes
  produced), so the fallback at `:622-623` works. The real defect there is the `isEmpty()` versus `||`
  divergence (fixed as part of H5/M4 work), not the redirection.
- **`run()` deadlock** — verified **not** a bug: stderr is merged into stdout (`:370`) and stdout is
  drained to EOF (`:375`) before `waitFor()` (`:376`). `readRoot` correctly drains stderr on a
  separate thread instead of merging it, so error text cannot be mixed into partition bytes. The
  defect is timeouts and status, not deadlock (H4).
- **`any = writeRoot(path, data) || any;`** (`:746`) — verified **not** a short-circuit bug: Java
  evaluates the left operand first, so every matching path is always written.
- **The APK signature scheme** — verified sound, not v1-only. The shipped APK carries a valid v1
  signature plus genuine v2 (0x7109871a) and v3 (0xf05368c0) blocks whose RSA-2048/SHA-256 signatures
  and chunked content digest cover the whole file, so the Janus downgrade (CVE-2017-13156) does not
  apply on API 24+. It remains reachable only on API 21-23, where v2/v3 are ignored — hence the
  README caveat in C5 rather than a minSdk change.
- **The `res/`-free APK** — verified installable and valid. No `resources.arsc` exists, so the
  Android 11 alignment rule is inapplicable, and `aapt add` compressing `classes.dex` is legal at
  targetSdk 30. Keep the code-built UI; do not introduce XML layouts or AndroidX.
- **Stale-prebuilt suspicion** — verified **not** real. The shipped APK's dex contains the current
  source's distinctive literals (`nvram patched`, `no nvram match`, `MacRandomizationSetting`,
  `write.tmp`, `nvram_backup`, `ip link set dev`, `APCFG`) and its compiled manifest equals
  `app/AndroidManifest.xml`, so `prebuilt/MacChanger.apk` corresponds to the source audited here. The
  gap is that nothing *records* or *verifies* that (M8), not that the binary is stale.
- **Making the CLI multi-vendor** — explicitly **not** planned. Guessing calibration paths in a root
  shell that overwrites them is worse than not supporting them; the CLI stays fail-closed on non-MTK
  and the README claim is what changes (L1).
- **Fetching or verifying a third-party MAC/OUI database, or a router/BSSID collision check** —
  skipped. Only the LAN is visible via `/proc/net/arp`, false positives are high, and a duplicate MAC
  usually just breaks your own connectivity. Not worth the code.
- **Normalising MAC input formats** (`aabbccddeeff`, `AA-BB-…`, dotted, whitespace-padded) —
  deliberately out of scope. It is real usability polish but touches the `isMac()` boundary that
  guards the shell and the prefs; if added later, normalise at the input boundary only and never
  relax `isMac()` itself.
