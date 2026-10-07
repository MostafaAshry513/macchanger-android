# Security policy

MacChanger runs as **root** on a rooted phone and rewrites the vendor file that
holds the factory Wi-Fi MAC. A bug here does not corrupt a cache: it can
truncate a calibration partition that Android will never regenerate, and the
only way back is a backup this same tool is supposed to have taken. Treat
reports about that path as security reports, not as feature requests.

---

## Reporting a vulnerability

**There is no private contact address recorded in this repository.** That is a
verified fact, not an oversight in this text: there is no `git remote`, and no
tracked file names an author, a maintainer or an address. Until the maintainer
adds one:

1. If you obtained this from a Git host that offers private vulnerability
   reporting or security advisories, use that form on the project you got it
   from.
2. If you received it as a zip or a directory with no such channel, then there
   is no private channel and you should assume anything you send may become
   public. In that case, **do not paste the contents of your backup directory
   or your calibration file** (see "What never to include in a report" below).
3. The maintainer should add a real contact address to this section before
   distributing another build; a policy with no way to report is not a policy.

Please include, when you can:

* device, SoC, Android version and API level, root solution (Magisk/KernelSU/…);
* the APK `versionCode` / `versionName` you are running and the output of
  `sha256sum MacChanger.apk` (see CHANGELOG.md — every release publishes both);
* the exact commands you ran and the app/CLI log lines, verbatim;
* whether `/data/adb/macchanger/` existed and what it contained **in terms of
  which files are present**, not their bytes.

There is no bounty programme and no response-time commitment; there is one
maintainer at most.

---

## In scope

* **The write path.** Anything that can truncate, blank, resize, or write to the
  wrong offset in a calibration file; anything that replaces the file instead of
  writing through it (`mv`/`cp` over the NVRAM path changes the SELinux label
  and can truncate — this is a defect by definition, not a style choice);
  anything that writes when the bytes at the matched offset are not the MAC the
  driver is actually using.
* **Backups and recovery.** Anything that can lose, overwrite, forge or
  silently mislabel the factory record; a `restore` that reports success without
  the target file matching the backup byte-for-byte; a backup that can be
  created from an already-spoofed image and then presented as "factory".
* **The root/shell layer.** Command construction and path handling (paths must
  be argv-passed, never interpolated into a root shell), injection into the root
  shell, the timeout/deadline logic (a deadline armed on a write that can shorten
  its target is a vulnerability, not a fix — see the rule under "What this
  project will not do to fix a bug"), and the handling of a partial read.
* **Silent failure.** A failed or unreadable path reported as "unsupported
  device", a failed write reported as success, or a non-persistent runtime
  change reported as a persistent one. Honest failure reporting is a security
  property of this tool.
* **The zero-permission property.** The manifest requests no permissions and
  declares no exported service or receiver; `MainActivity` reads no `Intent`
  extras. Any way a third-party app could trigger a root action, read the
  factory MAC, or read the connected SSID out of this app is in scope. Adding a
  permission, a foreground service, or an `Intent`-driven action API would
  itself be the vulnerability.
* **The signing identity.** See the next section.

## Out of scope

* Using this tool against a device you do not own, or a network you are not
  authorized to use. That is misuse, not a vulnerability; see "Intended use and
  licence" in README.md, and [docs/SAFETY.md](docs/SAFETY.md) for what the tool
  does to a phone and what a wipe does not undo.
* Feature requests that break the build model: Gradle, AndroidX, a `res/`
  directory, XML layouts, third-party libraries, a foreground service, new
  permissions. All of these are deliberate exclusions; the code-built UI and the
  zero-permission manifest are the design.
* `targetSdkVersion 30` and `allowBackup="false"`. Both are deliberate and
  explained in [docs/APP.md](docs/APP.md); reports asking to raise or flip them
  will be answered with that explanation.
* "My router still sees the old MAC / my network still randomizes." That is
  Android's per-network MAC randomization overriding the NVRAM value, and it is
  documented in [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md). It is not a defect in
  this tool — but *reporting a failure to detect it* is (see "Randomization
  detection" there).
* The absence of a `/data/adb` directory on a device that has no Magisk-style
  root: the app then falls back to its private mirror, and says so.

---

## The shipped APK's signing key: treat the identity as compromised

The APK in `prebuilt/` was signed with the debug keystore that **shipped in the
original distribution next to the APK**, with its password published in the
README of that distribution. The private key was therefore public.

Verified in this tree (commands and output in CHANGELOG.md):

* the certificate inside `prebuilt/MacChanger.apk` and the certificate inside
  that keystore are byte-identical — same SHA-256
  `8F:ED:CA:F1:8E:92:BB:19:24:E5:DE:1C:BB:A5:EA:6F:8B:31:FB:03:91:08:EE:AF:FC:4F:9C:F5:11:86:F1:AF`,
  same serial `f95c6faeadff3f8d`, `CN=MacChanger`;
* copies of that keystore still exist in the as-shipped snapshot this repository
  was extracted from (`app/ks.jks`), so "the key is gone from the tree" is not
  the same statement as "the key is secret".

**Consequences you must assume:**

* That certificate identifies *which* key signed a file. It does **not**
  establish that the file is trustworthy: anyone holding the original
  distribution could sign a `com.macchanger` update that Android accepts in
  place, inheriting the root policy already granted to the app and the app's
  private data (which, on older builds, included the only NVRAM backup).
* Do not add that fingerprint to a trust store, a pinning list, or an allowlist.
* If you have a device with the shipped APK installed and you value the record
  it holds, use the app's **Export record** button (or copy
  `/data/adb/macchanger/` and `/data/data/com.macchanger/files/nvram_backup/`)
  before you uninstall or replace anything.

**Current state of this tree (verified, and the reason no fingerprint is
published here as a trusted value):**

* `app/ks.jks` is **not** present in the tree;
* `.gitignore` excludes `ks.jks`, `*.jks`, `*.keystore`, `*.p12`, `*.pk8`,
  `*.pem`, `*.der` and the build outputs;
* `app/build.sh` has no default key: with `KS` unset it prints a refusal and
  exits non-zero without creating anything, and generating a key requires the
  explicit `--new-key`.

That is the correct end state. The key material that signed the old APK is still
public, and no documentation in this repository should ever present that
identity as something to trust.

Reports *about* this key are welcome as security issues — but note that the key
is already public, so describing it is not a disclosure.

---

## What never to include in a report

* The contents of `/data/adb/macchanger/WIFI.factory` or of any calibration
  file. Those images contain your hardware MAC and your device's calibration
  data; pasting them publishes both. Say which files exist and what `wc -c`
  reports, not what they contain.
* The `factory.txt` value, unless you have already changed your MAC and do not
  mind it being public.
* Your `WifiConfigStore.xml` (it lists your saved networks and their SSIDs).

---

## What this project will not do to fix a bug

Several tempting "fixes" are worse than the bug they address. They are listed
here so that a future contributor does not reintroduce them:

* **No timeout or kill on a command that can shorten the file.** Truncating a
  calibration partition is the worst outcome in this project, so the rule is
  stated about the *primitive*, not about the command: a deadline may be armed
  only on a write that cannot truncate its target — an **argv-passed, in-place
  `dd conv=notrunc,fsync`** that reads the source and destination sizes and
  compares them *inside the same invocation*, before and after the write, and
  whose partial result is refused by the size and content gates rather than
  reported as success. A kill can then leave a same-length, partly overwritten
  image, which the pre-image recorded before the write can undo — never a short
  file. It must **never** be armed on a truncating redirect (`cat tmp > path`,
  a bare `>`, or a `cp` onto the calibration path): there a kill is exactly the
  outcome this rule exists to prevent. The two front ends follow it differently
  and both are conforming: the app installs through `WRITE_SCRIPT` on
  `MainActivity.MS_WRITE` (120 s — twice the big-read deadline, so only a *hung*
  shell is cut off, never a slow one), where `execWrite`'s watchdog calls
  `destroyForcibly()`; the CLI's `write_mac` carries no deadline at all. Anyone
  who wants a deadline on a *different* primitive must first make that primitive
  incapable of shortening the file — a deadline is a property of the write, not
  an independent knob.
* No `mv`/`cp` of a rewritten file into place over NVRAM. Keep the inode:
  seek-and-write with `conv=notrunc` and `fsync`.
* No hardcoded NVRAM offset. The MAC is located by scanning for the bytes the
  driver is actually using, and the write is refused when they are not there.
* No foreground service and no permission, ever, to make a long write safer. The
  mechanism actually shipped is a **process-wide operation flag plus a retained
  log**, not `configChanges`: the manifest declares no `android:configChanges`
  and no orientation lock (`grep -c configChanges app/AndroidManifest.xml` → `0`),
  so a rotation destroys the Activity and builds a fresh one while the worker
  keeps running. What stops a second write is `static volatile boolean busy` and
  `static volatile MainActivity current`
  (`app/src/com/macchanger/MainActivity.java:148-151`): the new instance sees the
  operation in flight, keeps its buttons disabled and reports it, and the retained
  log stays on screen. `MainActivity.java:137-142` states the same thing. Do not
  "restore" a `configChanges` attribute here — it is not what holds this property,
  and adding it would change the rotation behaviour the code is written around.
* No random MAC value that is all-zero, broadcast or multicast; a
  locally-administered value is rejected as a *factory* record (it is evidence
  of a spoof) and accepted as a target (the Random button sets that bit).
* **No environment-driven override of a calibration path.** See the next section.

---

## Test-only redirects, and why there are none

An earlier revision of `cli/macchanger.sh` shipped an off-device testing hook: if
`MACCHANGER_TEST` was set, then `MACCHANGER_DIR`, `MACCHANGER_NV` and
`MACCHANGER_NET` replaced the record directory, the calibration path and the
interface directory. It was documented as safe, and it was not. Recorded here
because it is the kind of "convenience" a future contributor is likely to
reinvent, and because the first sentence of its documentation was false:

* **The gate was looser than the documentation.** The code was
  `[ -n "$MACCHANGER_TEST" ]`, so any non-empty value armed it. Measured:
  `MACCHANGER_TEST=0`, `=false`, `=off` and `=no` each printed
  `TEST OVERRIDES ACTIVE: … (not the device paths)` and redirected the paths,
  while `README.md` and the CLI's own `help` both stated that "without
  `MACCHANGER_TEST=1` those variables are ignored, so a stray environment cannot
  silently redirect a calibration write". Only an empty or unset value disarmed
  it. A gate documented as `=1` and implemented as "non-empty" is a false safety
  claim, which is worse than no claim.
* **The redirected target was not validated.** `MACCHANGER_NV` was used verbatim;
  the only precondition on it was `[ -f "$NV" ]`; there was no allow-list for the
  path or for `MACCHANGER_DIR`; and every symlink refusal in the file guarded the
  *record* path (`$BAK`), never the file that was actually written. A caller could
  therefore choose the target, the content and the offset of a write performed as
  uid 0.
* **The environment crosses a real privilege boundary.** The CLI's own
  `require_root` message instructs the user to invoke it as `su -c '<script>'`,
  and stock `su` passes the caller's environment into the target shell. Measured
  here with util-linux `su` 2.37.2:

  ```
  $ MACCHANGER_TEST=0 MACCHANGER_NV=/tmp/victim su -s /bin/sh nobody -c \
      'echo "inside: [$MACCHANGER_TEST] [$MACCHANGER_NV]"'
  inside: [0] [/tmp/victim]
  ```

  So "whoever controls the environment of a root invocation" is not an exotic
  attacker; it is the normal way this tool is started. **What this container
  cannot establish:** whether Magisk's `su` on a real device preserves the
  environment the same way. That is a device fact, and it is recorded as
  unverified rather than assumed — it does not change the conclusion, because the
  guarantee the documentation asserted ("without `MACCHANGER_TEST=1` those
  variables are ignored") was already false as written, whatever `su` does.

**No validation of the redirected target could have repaired it**, because the
caller chose the target: an allow-list of MTK calibration paths cannot cover a
validation sandbox, and the sandbox was the only reason the hook existed. The
hook was therefore **deleted**, not gated more tightly. `DIR`, `NV` and `NET` are
literals in the script and nothing in the environment overrides them.

**How the CLI is tested off-device now, without any hook:** `tools/clitest/`
runs the real script text under `unshare -m`, bind-mounting throwaway
directories over `/mnt/vendor/nvdata/APCFG/APRDEB`, `/data/adb/macchanger` and
`/sys/class/net`, with `tmpfs` over `/mnt`, `/data` and `/sys` so nothing can be
created outside the namespace. That needs no cooperation from the script and
works on revisions that never had a hook. See `tools/clitest/README.md`.

**The regression rule this leaves behind:** a redirect that exists so a test can
point a root tool at a sandbox must live in the sandbox, not in the shipped
script. If a future change needs a path override for debugging, the answer is a
mount namespace or a copy of the script — never an environment variable in the
artifact users run. And if such a variable is ever reintroduced, the gate must be
the literal `[ "$VAR" = 1 ]`, the target must be validated against an explicit
allow-list, and the documentation must state the gate the code actually has.

One override does remain, deliberately: `BB` selects the busybox binary
(`BB=${BB:-/data/adb/magisk/busybox}`), and [docs/CLI.md](docs/CLI.md) documents
it. It chooses a
*tool*, not a target path, and the CLI probes it before use — but it is still
caller-controlled input to a root process, so it belongs to the same trust
boundary as root itself.
