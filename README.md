# Mac Changer (rooted Android)

> **Do not install the APK in `prebuilt/` unless you know what it is.** It is the
> **pre-fix** build, compiled before the audit in `AUDIT.md`: it writes at a
> hardcoded offset instead of locating the MAC, can truncate the calibration file
> when it reads a short image, accepts a multicast address, and reports success it
> has not verified. It is kept only because it is the artifact this project
> shipped, and its hash is recorded so you can recognise it.
> **Build your own instead** — on the phone, no PC, no SDK:
> `cd app && ./build.sh`. Anything this repository says about safety describes the
> source in this tree, not that binary.

A persistent WiFi MAC changer for rooted Android phones. It finds the vendor
**NVRAM / persist** file that stores the factory WiFi MAC, backs that file up,
replaces the MAC bytes **in place**, then restarts WiFi so the driver re-reads
it. Because the change lives in the calibration file rather than in Android's
settings, it survives reboots — and, on the tested device, a factory reset.

It ships two front ends: an on-device buildable APK (no PC required) and a
standalone CLI shell tool. They have **different** vendor support; read the next
two sections before you use either.

Tested on: **Infinix SMART 5 (MediaTek MT6761), Android 11, Magisk** — one
device, one SoC family. Everything else is untested. See
[Supported devices and per-vendor risk](#supported-devices-and-per-vendor-risk).

---

## Intended use and authorization

This is a tool for **devices you own** and **networks you are authorized to
use**. Its legitimate uses are real and are the reason it exists:

* privacy on your own network and on networks that do not use MAC-based
  filtering;
* repairing a **corrupted, zeroed or wrongly flashed factory MAC** — the case
  where the radio comes up with an address the AP or the driver rejects;
* replacing a MAC that has been burned into a captive-portal or asset database
  you control;
* testing your own access point's MAC allow/deny lists, and hardware
  diagnostics.

**Understand what you are doing before you do it.** This is not the same thing
as Android's "randomized MAC" privacy setting, and it is not reversible by any
normal Android mechanism:

* The change is written into the vendor calibration file, so it **persists
  across reboots** and, on the vendor-partition paths (MediaTek `nvdata`,
  Qualcomm `persist`, Samsung `efs`, Unisoc `productinfo`), it **survives a factory reset**:
  a factory reset does not restore the factory MAC, and neither does the
  per-network Privacy setting. The two candidate paths that live under `/data`
  (MediaTek's older `/data/nvram/...`, Qualcomm's `/data/vendor/...`) are the
  exception: a data wipe takes those with it.
* It is **invisible to Android's per-network MAC randomization setting**: the
  two mechanisms are independent, and the app warns you when a saved network is
  randomizing on top of your change.
* **Only this app or this CLI can undo it** in the normal case. The way back is
  the saved factory record: press **Restore factory** in the app, or run
  `sh cli/macchanger.sh restore`, both of which write the recorded factory image
  back in place and verify the result. If that record is gone, you need the
  factory MAC from somewhere else, and the two front ends differ: the app takes
  it **typed** into the FACTORY MAC row (see
  [If the backup is gone](#if-the-backup-is-gone)), while the CLI has no typed
  record at all — `sh cli/macchanger.sh set MAC` still captures the factory
  image first, and on a device whose live MAC is already locally administered
  (the state `random` itself creates) that capture is **refused**: `set` exits
  `6` and writes nothing until you add `--assume-factory`, which records the
  current spoof as the "factory" value. Re-flashing the vendor partition is the
  alternative that costs nothing.
* If you change the MAC to a value that is not yours, you are impersonating a
  device on that network. Doing so to **evade per-device billing, a MAC
  allow/deny list, a captive-portal limit, or a block imposed by an operator who
  does not own the device** is misuse, and in many jurisdictions it is a
  computer-misuse offence. The CLI's own warning — *"your whitelisted WiFi will
  drop unless this MAC is whitelisted"* — is the practical version of the same
  point: a MAC is a credential.

What this tool does **not** contain, factually: no scanning, no deauthentication,
no packet injection, no cloning of MACs observed on the air, and no attack
against anything except the WiFi adapter of the device it runs on. It also
requests **zero Android permissions** — it does everything through the `su`
channel it is already granted, and that property is deliberate and must not be
traded away for convenience.

---

## What it does

* Scans the vendor file for the WiFi MAC the driver is **currently** using.
  **The app** tries four encodings — the six raw bytes, those bytes reversed, and
  two **lowercase** ASCII spellings (colon-separated `aa:bb:cc:dd:ee:ff` and
  plain `aabbccddeeff`) — and it rewrites every located window. Uppercase ASCII
  (`AA:BB:CC:DD:EE:FF`) is **not** searched: the encodings are built with
  `String.format("%02x", …)`, so a file holding only an uppercase copy is
  reported as not containing the live MAC, which is true of that file in the
  encodings this code knows. **The CLI** is narrower: it searches the six raw
  bytes only (`scan_mac()` matches one hex pattern), so on a file that stores the
  MAC as ASCII text the app can change the value and the CLI reports the same
  file as not holding it (exit `6`, nothing written). That is a safe refusal, not
  a wrong write, but it is a real app/CLI difference — do not read "both scan for
  the MAC" as "both find it in the same files". Whatever is located, the tool
  backs the file up, replaces those bytes, and restarts WiFi so the driver
  re-reads the file.
* Detects the SoC vendor and probes **candidate paths for several vendors**
  (MediaTek, Qualcomm, Samsung, Unisoc) — see the table below. Only MediaTek has
  ever been tested.
* Refuses to write when it cannot be sure: the file must exist, the runtime MAC
  must actually be found inside it, and the position and length must be
  verifiable. Otherwise it writes nothing, and it says *which* case it hit —
  none of the candidate files exists (`UNSUPPORTED DEVICE`), a file exists but
  does not carry the driver's MAC (`no MAC match`), or a file exists that root
  could not read — instead of pretending it changed something. Those are three
  different verdicts with different follow-ups, and only the first one also
  suppresses the WiFi restart and the runtime fallback; all three are spelled out
  under
  [Supported devices and per-vendor risk](#supported-devices-and-per-vendor-risk).
* Copies the calibration file into **app-private storage before the first
  write, and aborts the write if that copy cannot be made and verified** — it is
  re-read and its length checked against the image that was just read out of the
  partition. It *then* also tries to copy that image into
  `/data/adb/macchanger/`, and that push is **best-effort: its result is not a
  gate.** On a device with no `/data/adb` directory the write therefore
  still proceeds, with only the copy an uninstall or a "Clear data" destroys.
  Read [Where the record lives](#where-the-record-lives): the app-private copy is
  what guards the write, and the `/data/adb` copy is the one that survives an
  uninstall. (An earlier version ignored even the app-private copy's failure and
  patched the file anyway; the old claim "the app always copies the original
  NVRAM before writing" was false on that path. The private copy is a gate now.)
* Verifies what it wrote by re-reading the patched region and comparing it with
  an image computed from the pre-write bytes, and tells you plainly when the
  driver did not pick the value up.

There is also a **runtime `ip link` fallback** for devices where no NVRAM file
matches. It is a last resort, it is **off by default** in the app, and the CLI
never runs it at all. The reason is the same sentence the CLI carries in its
source: on MediaTek, touching `ip link` this way **crashes the WiFi service**.
It is also **non-persistent — it does not survive a reboot**, so treat it as a
temporary experiment on a device whose calibration file you have already backed
up, never as a way to change a MAC.

---

## Supported devices and per-vendor risk

| Vendor | Candidate paths the app probes | Status |
|---|---|---|
| **MediaTek** | `/mnt/vendor/nvdata/APCFG/APRDEB/WIFI`, `/data/nvram/APCFG/APRDEB/WIFI` | **The only tested path** (MT6761, Android 11). The CLI supports MediaTek only. |
| **Qualcomm** | `/mnt/vendor/persist/wifi/wlan_mac.bin`, `/persist/wifi/wlan_mac.bin`, `/data/vendor/wifi/wlan_mac.bin` | **Untested.** Nobody has confirmed that one of these files contains the runtime MAC in a form the scanner finds, or that the driver re-reads it. |
| **Samsung** | `/efs/wifi/.mac.info`, `/efs/wifi/mac.info` | **Untested.** `/efs` holds device-critical data on Samsung hardware; it is the highest-consequence file in this list, and on many models the content is not a raw MAC at all. |
| **Unisoc** | `/productinfo/wifi_mac`, `/mnt/vendor/productinfo/wifi_mac` | **Untested.** Nobody has inspected one. |

What that means in practice on a non-MediaTek device: the app will write **only
if** one of those files exists **and** the bytes of the live runtime MAC are
found inside it. Whether the driver then uses the new value is unknown, and the
honest expectation is that it may not take, or may take and revert at the next
WiFi re-init.

When it will not write, the reason decides what else happens — the verdicts
below are not the same thing, and only one of them is an unsupported device:

* **None of the nine paths exists.** Nothing is written and nothing is
  attempted: the app says `UNSUPPORTED DEVICE — nothing was written`, lists
  every path it probed, and **does not restart WiFi and does not run the runtime
  fallback**, even if you have enabled it. That is the only case the words
  "unsupported device" describe.
* **A candidate file exists but does not carry the live MAC.** Also nothing
  written, and the verdict is
  `no MAC match · <mac> is in none of the candidate files`, with what root saw
  of each path. This is **not** the unsupported-device verdict, and it is exactly
  the case the runtime fallback exists for: with the fallback enabled the app
  restarts WiFi to re-read the calibration file and may then run the `ip link`
  command, with the MediaTek crash risk described above. With the fallback left
  off (the default), no restart happens here either and the log says
  `nothing was written · WiFi left alone`.
* **A candidate file exists but root cannot read it.** Nothing is written, and
  unlike the case above the app does **not** restart WiFi and does not run the
  fallback even when it is enabled: that unreadable file may be the one holding
  the MAC, so covering it with a runtime spoof is precisely what the code
  refuses to do. The verdict is
  `candidate NVRAM present but not usable by root · nothing was written`.

None of these cases is ever reported as a success, and none turns "I could not
find the file" — or "I could not find the MAC in it" — into a write.

Other limits worth knowing before you install:

* **Android versions.** Tested on Android 11 only. The APK declares
  `minSdkVersion 21` and `targetSdkVersion 30`; it installs and launches without
  a deprecation dialog on Android 14–16 (see
  [Why targetSdk is 30](#why-targetsdk-is-30-and-must-stay-30)). The code was
  written against Android 11 plus AOSP sources up to Android 16, but nothing
  newer than Android 11 has been run on real hardware by this project.
* **Root.** A Magisk-style root is assumed (`su`, and `/data/adb` for the
  durable record). The app also works without `/data/adb` by falling back to its
  private mirror, and says so when it does; the CLI requires `su` and busybox.
* **No assumed file layout.** Neither front end assumes a size, header or
  offset: both locate the MAC field by scanning for the MAC the driver is using,
  and both refuse to write on a device where that scan fails. What they install
  once the field is located is **not** the same thing, and the difference matters
  if you are judging how much of the file a bug can reach:
  * the **CLI** writes only the located 6-byte MAC field, in place:
    `cli/macchanger.sh`, `write_mac()`, `dd of="$_file" bs=1 seek="$_o" count=6
    conv=notrunc,fsync` — the only `dd` in that file with both an `of=` and a
    `count=6`;
  * the **app** installs a full-size copy of the *whole* file, with only the
    located MAC window changed. `setMac` reads the file with `readRoot` and
    clones every byte of it (`byte[] patched = data.clone();`), patches that
    clone in memory, and hands the clone to `writeRoot`, which stages exactly
    that full-size image and installs it by running `dd` over the whole file:
    `dd if="<staged image>" of="<calibration path>" bs=4096 conv=notrunc,fsync`
    inside the `WRITE_SCRIPT` constant
    (`app/src/com/macchanger/MainActivity.java`). Grep `WRITE_SCRIPT`,
    `writeRoot`, `readRoot` and `data.clone()` — those four names are the whole
    of the difference.
  The app's whole-file install is guarded rather than blind. Inside that one root
  invocation `WRITE_SCRIPT` reads the staged image's size and the destination's
  size, refuses unless they are equal and non-empty, checks that the destination
  is still the file the patch was derived from, writes, re-reads the destination
  size and refuses if it changed, and finally requires `cmp` to find the image in
  place before it prints `wf=ok`. Before any of that, `writeRoot` refuses a staged
  buffer shorter than `MIN_IMAGE`. So the size authority is the
  destination's own size, not the image's, and a short image can never shorten a
  calibration file. Earlier revisions of this tool did rewrite whole files
  without that authority, which is the `C1` defect recorded in `AUDIT.md:27`.

---

## Layout

```
MacChanger/
  README.md                  this file
  CHANGELOG.md               per-release APK SHA-256 and signer certificate SHA-256
  SECURITY.md                how to report, what is in scope, signing-key status
  LICENSE                    MIT
  AUDIT.md, audit/PLAN.json  the adversarial audit and the fix plan (read-only record)
  app/
    AndroidManifest.xml      zero <uses-permission> elements - deliberate
    src/com/macchanger/MainActivity.java
    build.sh                 on-device build script (Termux, run as ./build.sh)
  cli/
    macchanger.sh            standalone CLI, root, MediaTek only
  prebuilt/
    MacChanger.apk           ready-to-install APK (versionCode 1, see CHANGELOG.md)
  tools/stubcompile/       offline gate: compiles MainActivity.java against
                           hand-written Android stubs (compilation only)
  tools/clitest/           offline harness: runs the CLI against synthetic NVRAM
  tools/checks/            offline harness: greps for the hazards this audit fixed
  tools/package.sh         reproducible .tar.gz of the tree, into ../dist
```

There is **no keystore in this tree** and none in its git history: `ks.jks`,
`*.jks`, `*.p12`, `*.keystore`, `*.pk8`, `*.pem` and `*.der` are ignored, and
`app/build.sh` refuses to build without a keystore you supply. The key that
signed the shipped APK, however, is public — read
[the two signing identities](#the-two-signing-identities) before you install
anything.

---

## Install the prebuilt APK

Copy `prebuilt/MacChanger.apk` to the phone and tap it, or:

```bash
su -c 'pm install -r prebuilt/MacChanger.apk'
```

Grant root on first launch. Granting root is the only privilege this app ever
asks for: it declares no Android permission at all.

### Verify it before you install it

Verify the file **before** `pm install`, on a machine you trust:

```bash
# 1. The APK itself. Compare with the value in CHANGELOG.md.
sha256sum prebuilt/MacChanger.apk
#   1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da

# 2. Which key signed it, and the version stamped into its manifest.
keytool -printcert -jarfile prebuilt/MacChanger.apk
apksigner verify --print-certs -v prebuilt/MacChanger.apk
aapt dump badging prebuilt/MacChanger.apk | head -1
```

The shipped APK was built from source commit
`a82ffee8828585059a6ab3a7b53d2b9dec1c72ca` ("pristine MacChanger as shipped") —
that is the pre-fix tree, **not** the current one; the current source has not
been built or signed, so there is no APK for it yet. Its APK SHA-256 and its
signer certificate SHA-256 are recorded in `CHANGELOG.md`, and that certificate
fingerprint is an **identifier, not a trust anchor:** the private key that
matches it was distributed with the original archive, so a matching fingerprint
proves which key signed a file, not that the file is the one this project
produced. Do not pin it. If the SHA-256 does not match the value in
`CHANGELOG.md`, do not install the file.

On **Android 5.0–6.0 (API 21–23)** the platform ignores the APK's v2/v3
signatures and checks only the JAR signature (the Janus downgrade,
CVE-2017-13156), so an APK hash comparison matters even more there. Android 7.0+
(API 24+) verifies the whole-file signatures and is not affected.

### The two signing identities

There are two, and they cannot replace each other:

1. **The shipped `prebuilt/MacChanger.apk`** — signed by a debug keystore
   (`CN=MacChanger`, alias `mac`) that shipped **next to the APK in the
   original distribution**, with its password published in that distribution's
   README. Verified: the certificate inside the APK is byte-identical to the
   certificate inside that keystore (same SHA-256, same serial
   `f95c6faeadff3f8d`), so anyone who has the original archive also has the
   private key. **Treat this identity as compromised.** It is a
   reproducible-build-style identifier only.
2. **Anything you build yourself** — signed with a keystore you supply through
   `KS`. `build.sh` has no default key: with `KS` unset it prints a refusal and
   exits non-zero without creating anything, and generating a new key requires
   the explicit `--new-key` flag.

Because they are different identities, **`pm install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** when you try to replace an installed
`versionCode 1` with your own build (or vice versa). The only way out is
`pm uninstall com.macchanger`, and **that deletes the app's private data** —
which on the shipped build was the only place the NVRAM backup and the factory
MAC lived.

So, before you uninstall or replace anything:

* open the app and press **Export record**, which copies `WIFI.factory`,
  `WIFI.factory.path` and `factory.txt` to `/sdcard/Download` (or `/sdcard`);
* and/or copy both `/data/adb/macchanger/` and
  `/data/data/com.macchanger/files/nvram_backup/` off the device as root.

---

## Build the APK on the device (Termux)

Run it with `./build.sh`, **not** `sh build.sh`: the script uses bash-only
features and re-execs itself under bash if a POSIX shell got there first.

```bash
pkg install openjdk-21 aapt apksigner d8 zipalign coreutils
cd app
KS=/path/to/ks.p12 KS_PASS='...' ./build.sh      # -> app-signed.apk
# or, only if you accept that an installed build becomes un-updatable:
# KS=/path/to/ks.p12 KS_PASS='...' ./build.sh --new-key
```

* **`android.jar`: API 30 or newer.** The build compiles against it as the boot
  classpath and packages against it with `aapt -I`. The default path is
  `$PREFIX/share/java/android.jar`; override it with `AJ=/path/to/android.jar`.
  API 30 is the level this project is pinned to: the manifest declares
  `minSdkVersion 21` / `targetSdkVersion 30`, and the shipped APK records
  `compileSdkVersion 30` (codename `11`) in its compiled manifest. A newer
  platform also compiles this source — the runtime minimum is API 21 — but an
  older one is not the level this project is built and shipped against.
* Also required: the device's own `/system/framework/framework-res.apk` (or a
  copy, via `FRAMEWORK=`), because `aapt` needs the platform's resource table
  and this project deliberately has no `res/` directory of its own.
* Signing is mandatory and explicit: `KS` (keystore) and `KS_PASS` are
  required; `KS_TYPE` defaults to `PKCS12`; `RELEASE_CERT_SHA256` optionally
  makes the build refuse to finish unless the signed APK's certificate matches
  the value you publish.
* The script stamps `versionCode` (default `$(date +%Y%m%d)`) and `versionName`
  (default `1.0+<git-rev>[-dirty]`), fails on `javac` errors, checks the
  packaged APK (contents, alignment, badging, icon, zero permissions), and
  prints the **APK SHA-256 and signer certificate SHA-256** at the end.

**Which of the two signing identities your build gets.** There are exactly two
in play, and they are different keys:

1. the identity that signed the shipped `prebuilt/MacChanger.apk` — the
   distributed debug keystore, which is **public and must be treated as
   compromised**, and which you should not reuse;
2. the identity of the keystore you pass in `KS`, which is the only thing your
   build will be signed with. `build.sh` refuses to invent one: with `KS` unset
   it prints a refusal and exits non-zero, and `--new-key` is required before it
   will create a key at all.

So a self-built APK is inevitably signed by identity 2 while an installed
prebuilt is identity 1, and `pm install -r` between them fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Read
[The two signing identities](#the-two-signing-identities) for what that costs
and what to copy off the device first.

Record both hashes and the source commit in `CHANGELOG.md` when you publish a
build; that is the only way a user can tell your build from any other.

---

## Use the app

* **FACTORY MAC** — the record this tool exists to protect. Type the factory MAC
  and press **Save factory** if you know it; it is stored as
  `typed, unverified` and is usable for a restore. **Export record** copies the
  record to `/sdcard` (through the root channel — no storage permission is
  requested or needed).
* **SET MAC** — enter a MAC (or use *Random*) and apply. Random sets the
  locally-administered bit, which is normal for a spoof.
* **Restore factory** — writes the saved factory image back in place. It refuses
  to run without a record, refuses a record whose length does not match the
  target, and verifies the result byte-for-byte.
* **Runtime ip-link fallback (not persistent)** — off by default. Read
  [What it does](#what-it-does) before enabling it: it can crash the WiFi
  service on MediaTek, and it does not survive a reboot.
* **NETWORK row** — the connected SSID and the per-network MAC randomization
  setting, when the app can read them. Tap the privacy row to open WiFi
  settings. See [Randomization detection](#randomization-detection-what-it-can-and-cannot-see).
* **Log** — every action logs the file it touched, the offset(s) it wrote and
  the before/after values. Read it; the app no longer announces a success it did
  not verify.

The app is a single screen with a code-built UI: no XML layouts, no AndroidX, no
third-party libraries, no `res/` directory. That is deliberate, so it stays
buildable on the device itself.

---

## Use the CLI (MediaTek only)

```bash
sh cli/macchanger.sh doctor                        # read-only report — run this first
sh cli/macchanger.sh show                          # interface / factory image / nvram / live MAC
sh cli/macchanger.sh backup [--assume-factory]     # capture the factory image (do this first)
sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF         # write NVRAM in place + restart WiFi
sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF --dry-run   # print the file, offset and bytes that would change
sh cli/macchanger.sh random                        # set a random locally-administered MAC
sh cli/macchanger.sh wifi                          # restart WiFi only
sh cli/macchanger.sh restore                       # write the factory image back, in place
sh cli/macchanger.sh panic                         # recovery: put the image back even when the
                                                   # NVRAM is corrupt, unreadable or truncated
sh cli/macchanger.sh show --json                   # machine-readable output (also: doctor --json)
```

The CLI is **MediaTek only**: it targets
`/mnt/vendor/nvdata/APCFG/APRDEB/WIFI` and refuses to run if that file is
missing — it will not guess a calibration path on another vendor's device,
because writing a wrong guess into a calibration partition as root is worse than
not supporting it. **The app's multi-vendor path list does not apply here.** If
you are on Qualcomm, Samsung or Unisoc, use the app, and read
[Supported devices and per-vendor risk](#supported-devices-and-per-vendor-risk)
first.

Which commands that refusal covers, precisely, because one command is deliberately
exempt and the difference matters when you are diagnosing a device: `require_nvram()`
exits `4` ("NVRAM file not found"), and it is called by `backup`, `set` (and therefore
`random`, which calls `set`), `wifi`, `restore` and `show`. `panic` refuses the same
states itself — it needs the file to *exist* and to be a regular file (`[ -e "$NV" ]`,
`[ -f "$NV" ]`), so it still works on a corrupt or truncated one, which is the whole
point of the command. **`doctor` is the one command that runs with no calibration file
at all**, by design: it is the read-only report whose job is to tell you that the file
is missing, so it reports the absent path instead of exiting. A `doctor` that exits `0`
on such a device is not a false success — read its `verdict:` line.

Other things the CLI does that are worth knowing:

* `doctor` (alias `selftest`) is strictly read-only — it creates nothing at all,
  not even a temp file — and reports root, the busybox applets the write path
  actually depends on, the resolved interface, every candidate MTK NVRAM path
  with its size, mode, mount flags and whether the runtime MAC is in it, the
  recorded factory MAC with its provenance and digest state, the backup
  directory, and a `verdict: yes/no` with the blockers that would stop a write.
* It needs root and busybox (for `dd`, `hexdump`, `awk`, `cmp`, `sha256sum`);
  set `BB=` if busybox is not at the default Magisk path.
* It resolves the WiFi interface instead of assuming `wlan0`; pass
  `--iface=NAME` when detection is ambiguous or finds nothing.
* `set` locates the MAC field by **scanning** for the MAC the driver is using,
  and refuses to write unless the bytes at the matched offset are exactly that
  MAC. It writes 6 bytes in place with `conv=notrunc,fsync` and verifies a whole
  window around the offset afterwards. It never `cp`s or `mv`s over the file.
* `--dry-run` on `set`, `random`, `backup`, `restore` and `panic` prints the
  file, the offsets, the bytes that would change and the window before/after,
  and changes nothing. It exits with the status the real run would have **for
  the checks it evaluates**: the write plan itself, and (on `set`/`random`, when
  an image already exists) a recorded image that does not match its digest —
  that refusal is exit `7`. It is not a
  complete prediction: a real `set` that finds no image first captures one, and
  the dry run does not run that capture decision, so a refusal that only arises
  there — a live MAC that is locally administered, or a MAC field that cannot be
  located — comes back as exit `6` from the real run while the dry run still
  exits `0`. Neither does it notice an image path that is not usable as one (a
  symlink, for example), which the real run refuses with exit `7`. Treat a `0`
  as "the plan is sound", not as "the run will succeed".
  **`wifi` refuses `--dry-run` instead of ignoring it** — a restart *is* the
  command, so there is nothing to preview, and the option is parsed globally
  rather than per command. `sh cli/macchanger.sh wifi --dry-run` therefore exits
  `1` with `--dry-run is not available for 'wifi': restarting the radio is the
  whole command …` and does not touch the radio. (An earlier revision accepted
  the flag and performed the real disable/enable.) `--dry-run` is accepted and
  ignored by `show` and `doctor`, which write nothing anyway; neither is a
  command you need it on.
* It captures the factory image only while the NVRAM still holds the live
  runtime MAC, and it refuses to record a locally-administered value as
  "factory" (that bit is evidence of an existing spoof) unless you pass
  `--assume-factory`. `show`, `doctor` and `restore` never capture anything.
* `restore` needs an existing image of exactly the target's size, verifies its
  recorded digest, writes it back in place and compares the result
  byte-for-byte. `panic` is the recovery command: it needs neither a readable
  MAC nor an interface, restores a **truncated** target (which `restore`
  refuses), and refuses instead of guessing when the image has no digest
  (`--allow-undigested`), has a digest that does not match, was recorded from a
  different path (`--allow-foreign`), carries a **locally administered** value at
  its MAC offset (`--allow-nonfactory-image`), or is too short to be a faithful
  restore. That locally-administered refusal is deliberate: an intact image is
  not the same thing as a factory image, so restoring one is not a recovery — the
  same record is a blocker in `doctor` — and it takes the flag to say you meant
  it.
* At most one positional argument is accepted, so `set AA:BB:... EXTRA` is a
  usage error rather than a validation error naming `EXTRA`, and `show a b`
  fails instead of ignoring `b`. The command aliases (`check`/`diag`/`selftest`
  for `doctor`, `status`, `save`, `rand`, `reset`, `undo`) are listed in
  `help`.
* `set` exits non-zero when the driver is not using the MAC it wrote, and says
  whether that is because the driver ignored it or because `svc wifi
  enable/disable` failed. `restore` and `panic` report the verified state of the
  file instead, so a driver that has not reloaded the NVRAM yet is a warning
  there, never a false failure.
* Exit codes are per failure class, so the CLI can be scripted: `0` ok, `1`
  usage/bad MAC, `2` not root, `3` busybox unusable, `4` NVRAM path missing or
  not a file, `5` interface unresolved, `6` a precondition on the target failed
  (refusing to write blind), `7` factory image missing/damaged/foreign, `8` the
  write or its verification failed, `9` written and verified but not in effect
  (`set`/`wifi` only). `--quiet` hides the `[*] …` **progress** lines only — the
  ones the CLI emits through its `info()` helper. Results and warnings, including
  some lines that also start with `[*]` (for example `[*] saved factory image ->
  …`, which is a result, not progress), are emitted through `say()` and are
  printed even under `--quiet`. So do not use `-q` when you are scraping output;
  use `--json` where it is offered. The CLI's own help states this precisely:
  "hide progress lines ('[*] ...'); results and warnings are still printed".
* Its record lives in `/data/adb/macchanger/`, mode 700, files mode 600 — the
  same directory the app uses. Both write `WIFI.factory` and the
  `WIFI.factory.path` tag naming the calibration path the image came from, plus
  `WIFI.factory.offset` (where the MAC field sits in the image, or `?N` when that
  offset was assumed rather than located) and `WIFI.factory.sha256`; both check
  that digest before installing an image. `panic` refuses an image whose `.path`
  names a different calibration file, so an image captured for another vendor's
  path is never written into this one. **An image captured by either front end is usable
  by the other:** the app resolves the factory value from its own stores first
  (the value you typed, `factory.txt`, the app-private mirror) and then out of
  the shared pre-image, at the offset recorded beside that image — so a factory
  image captured by the CLI is usable by the app's *Restore factory* without a
  `factory.txt` next to it. In the other direction the CLI's `doctor` reads the
  app's `factory.txt`.
* **No environment variable can redirect this tool's calibration paths.** `BB`,
  `DIR`, `NV` and `NET` are assigned in the script's header, and the three device
  paths are bare literals with no `${…:-…}` and no environment reference at all:

  ```sh
  DIR=/data/adb/macchanger
  NV=/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
  NET=/sys/class/net
  ```

  Verify it yourself — `grep -n '^DIR=\|^NV=\|^NET=' cli/macchanger.sh` shows three
  literal assignments, and `grep -c MACCHANGER cli/macchanger.sh` shows only the
  comment that records why the old hook was removed. An **earlier revision of this
  tree shipped an off-device testing hook** — `MACCHANGER_TEST` together with
  `MACCHANGER_DIR`/`MACCHANGER_NV`/`MACCHANGER_NET` — that pointed the calibration
  path, and therefore the write, at a path the caller chose. Its gate was also
  looser than the documentation claimed: the code was `[ -n "$MACCHANGER_TEST" ]`,
  so `MACCHANGER_TEST=0`, `=false`, `=off` and `=no` all armed the redirect while
  the README and `help` both said "without `MACCHANGER_TEST=1` those variables are
  ignored". That was a root-write primitive reachable by whoever controls the
  environment of the invocation, and this script's own `must run as root` message
  tells users to invoke it as `su -c …`, which passes that environment through.
  **The hook is gone**; do not reintroduce it in any form, and do not re-add a
  "the environment cannot redirect a calibration write" sentence to this file
  unless the code still matches it. Read
  [Test-only redirects, and why there are none](#test-only-redirects-and-why-there-are-none)
  in `SECURITY.md` for the history and for how the CLI is tested now.
* `BB` is the one environment variable that still changes what this script
  executes (`BB=${BB:-/data/adb/magisk/busybox}`): it names the busybox
  binary used for `dd`/`hexdump`/`awk`/`cmp`/`sha256sum`. It selects a **tool**,
  never a target path, it is probed before use (`"$BB" true || die`), and only
  the built-in default may be replaced by a `busybox` found on `PATH` — but it is
  caller-controlled, so treat it as part of the same trust boundary as root
  itself.

---

## How the NVRAM patch works (MediaTek example)

```
/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
offset 0x00: 01 00 08 00              header
offset 0x04: 04 f9 93 11 36 bf        the WiFi MAC (6 bytes)
```

The offset above is what the tested device uses, but **nothing in this project
hardcodes it**: the file is scanned for the bytes of the MAC the driver is
currently using, and the write is refused unless the bytes at the matched offset
equal that MAC. The encodings differ between the front ends — **the app** searches
four (raw, byte-reversed, and the two lowercase ASCII spellings), **the CLI** only
the raw six bytes — and neither searches uppercase ASCII. See
[What it does](#what-it-does) for the consequence of that difference. The file is
opened and written in place — same inode, same SELinux label — because replacing
the file would change its SELinux label and can truncate it. Every write is
verified by re-reading the patched region, and the file's size is compared before
and after.

---

## Randomization detection: what it can and cannot see

The app reads the per-network **MAC randomization** setting
(`MacRandomizationSetting` in the framework's `WifiConfigStore.xml`) for the
network you are connected to, and shows it as:

* `device MAC` — this network uses the hardware/NVRAM MAC, so your change is
  what the router sees;
* `randomized · tap to fix` — this network randomizes on top of your change, so
  the router does not see your MAC; set Privacy to *Use device MAC*;
* `unknown` — the app could not determine it. **`unknown` is not a diagnosis.**
  It does not mean randomized, and it does not mean device MAC.

The Android 12+ limitation is real and worth stating plainly:

* Detection needs the **name (SSID) of the network you are connected to**.
  With no connection there is no SSID, so there is nothing to look up and the
  row reads `unknown`.
* The SSID comes from `cmd wifi status` run as root. On **Android 12 and newer**
  the platform's status output changed: it now prints extra lines before the
  `Wifi is connected to "…"` line for a root caller, so anything that reads only
  the first few lines of that command can never see the SSID there. An earlier
  version of this app did exactly that, and consequently explained a *failed
  write* as "this network randomizes the MAC" on Android 12+ — sending you to
  change a setting that was already correct while the real cause stayed hidden.
  The correct way to read that command is by content, not by line position — the
  app resolves the SSID (and whether it is known at all) rather than slicing the
  output — but if a build still reports a randomization problem on Android 12+
  while you are connected, **do not trust that verdict**: confirm it in WiFi
  settings before you act on it, and check the log for a failed NVRAM write
  instead.
* The framework rewrites `WifiConfigStore.xml` within seconds even as root, so
  the setting cannot be forced from the app: change it in WiFi settings →
  (network) → Privacy → **Use device MAC**.

`Restore factory` still writes the factory value into the calibration file
regardless of any of this; randomization only affects what the router sees.

---

## Safety, backups and recovery

This software runs as root and rewrites a calibration file that Android will not
regenerate. The rules below are the whole of the safety model; the code enforces
them, and it refuses to proceed rather than guess.

### What it refuses to do

* It never `mv`s or `cp`s a rewritten file over the NVRAM path — keeping the
  inode is what keeps the SELinux label and the file length intact.
* It never hardcodes an offset, and it never writes unless the bytes at the
  matched offset are the MAC the driver is actually using.
* It never writes before the factory image has been copied **and verified** into
  app-private storage; if that copy cannot be made, the write does not happen
  ("backup failed — NVRAM untouched"). Draining that copy into
  `/data/adb/macchanger/` is attempted afterwards and is **best-effort**: a
  device where that push fails (no `/data/adb` directory, which is a normal
  Magisk-less-root situation) still gets the write, on the app-private copy
  alone.
* **No timeout may ever truncate a calibration file.** An earlier version left
  the write command unwatched for exactly that reason. Now that the write is a
  size-bounded, in-place `dd conv=notrunc,fsync` that cannot shorten the file, it
  runs under its own deliberately wide deadline (`MS_WRITE`, 120 s) so that only
  a *hung* shell, never a slow one, is cut off — and a partial result is refused
  by the size and content gates instead of being reported as success. Truncating
  a calibration partition is the worst outcome in this project, worse than a
  hung operation.
* It never sets an all-zero, broadcast or multicast MAC.
* It never runs the runtime `ip-link` fallback unless you have explicitly
  enabled it.

### Where the record lives

Both front ends in this source tree keep the record in
**`/data/adb/macchanger/`** (directory mode 700), and the app additionally keeps
a copy in its private directory. Which of the two is *load-bearing* differs
between them, so read the paragraph after the listing. The shipped
`versionCode 1` APK kept the record *only* in app-private storage, which is why
it was destroyed by an uninstall — see `CHANGELOG.md`. The durable location
survives `pm uninstall com.macchanger` and "Clear data", because the app's
private directory does not.

```
/data/adb/macchanger/WIFI.factory        byte-exact copy of the calibration file as it was
                                         before the first write
/data/adb/macchanger/WIFI.factory.path   the calibration path the image was captured from,
                                         as a BARE path and nothing else - one line, no
                                         offset, no "@0x" suffix (both front ends write
                                         it that way; the CLI's writer is
                                         `echo "$NV" >"$BAK.path"`). The offset is in the
                                         .offset sidecar, not here.
/data/adb/macchanger/WIFI.factory.offset the offset (written with the image, by either
                                         front end; `?N` when it was assumed, not located)
/data/adb/macchanger/WIFI.factory.sha256 the digest of the image (written with it too)
/data/adb/macchanger/factory.txt         line 1: the factory MAC
                                         line 2: its provenance - "<path>@0x<offset>", or
                                         "typed" when you entered it yourself. This is the
                                         ONLY record that carries "@0x"; do not copy that
                                         format into .path.
```

**Do not "repair" `.path` into `<path>@0x<offset>`.** Both front ends and the CLI's
`bak_source_path()` read that file as the path verbatim — it is the first line of
`$BAK.path` and nothing is stripped from it — and the CLI compares it against `$NV`
character for character. An offset suffix appended to it makes every recorded image look
as though it came from a **foreign** calibration file: `panic` then refuses outright
(`die "$EX_NOBACKUP" … proves nothing about this file. Refusing.`, unless you pass
`--allow-foreign`), `restore` warns that the image came from somewhere else, and `doctor`
prints `[NOT <path> - a foreign image, refused by 'panic']` next to a record you were told
to write that way.

Which copy guards the write: the **CLI** captures `WIFI.factory` and its sidecars
into `/data/adb/macchanger/` *before* it writes, and refuses to write without
them — there the durable image is the precondition. The **app** writes its
app-private copy first, checks that one's length against the image it just read,
aborts the write if *that* fails, and only then tries the `/data/adb` copy. That
push is not a gate and not a promise: it can fail (no `/data/adb` directory, no
root for that path) and the write proceeds anyway, on a copy that
`pm uninstall com.macchanger` or "Clear data" deletes. So after any write, open
the recovery card and look at its `durable` line — it reports whether `/data/adb`
really holds the pre-image — and if it does not, **export the record before you
uninstall anything.**

The app additionally keeps a **fallback mirror** for devices where `/data/adb`
does not exist:

```
/data/data/com.macchanger/files/nvram_backup/<mangled path>       the image
/data/data/com.macchanger/files/nvram_backup/<mangled path>.path  the path tag
/data/data/com.macchanger/files/factory.txt                       MAC + provenance
```

**This mirror is deleted by `pm uninstall com.macchanger` and by "Clear data".**
It is a fallback, not the record. Press **Export record** in the app, or copy
both directories off the device, before you uninstall, wipe or replace anything.

**And keep a copy off the device.** `/data/adb/macchanger/` and the export
destination `/sdcard/Download` both live on the data partition: a **factory
reset or a data wipe erases the record too**, while the MAC change itself
survives on a vendor partition. That combination — a changed MAC with no record
of the original — is the worst state this tool can leave you in, and it is why
the record is worth copying to a PC or an SD card.

### If the backup is gone

You can still restore a *known* factory MAC — that is the whole reason the typed
entry exists:

1. Find the factory MAC. Look at the device's box, its label or its SIM tray —
   *some* devices print the WiFi MAC there (it may be labelled WLAN/WiFi MAC);
   not all of them do. Otherwise: a note you took before the first change, any
   exported record, or a full dump / custom-recovery backup of the vendor
   partition. `/sys/class/net/<iface>/address` will **not** help — it shows the
   current, possibly spoofed value. Nothing can recover the factory value from
   the device once it has been overwritten and the record is gone; that is why
   the backup is a precondition for writing.
2. App: type it into the **FACTORY MAC** field and press **Save factory**. It is
   recorded as `typed, unverified` and *Restore factory* will write it — that
   needs no image at all, so this path works on a device whose record is gone.
3. CLI: `sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF` **is not the same path, and
   on this device state it is refused.** The CLI has no typed record: `set`
   captures the factory image before it writes anything, and the capture is
   refused (exit `6`, nothing written) when there is no image and the live MAC
   is locally administered — the state `sh cli/macchanger.sh random` leaves
   behind, and the state you are in whenever this section applies.

   **`--assume-factory` is the last resort, not the only way through.** If you
   can obtain a **known-good full-size image** of the calibration file — the
   dump/reflash route in step 4, a copy you kept off the device, or the vendor
   partition of an identical model — put it at `/data/adb/macchanger/WIFI.factory`
   together with its sidecars (`WIFI.factory.offset`, `WIFI.factory.path`,
   `WIFI.factory.sha256`) and `set` proceeds with **no flag at all**, keeping a
   real factory record. That is what the CLI's own refusal message tells you to
   do (`… or put a known-good factory image back at $BAK first (a copy you kept
   off the device), then run '$0 set MAC'`), and it
   is verified: `ensure_backup()` returns success immediately when `$BAK` exists
   and its size relation and digest both pass, so the capture decision that
   refuses `--assume-factory` is never reached. Reconstructed in a sandbox with
   an LA live MAC and a 64-byte known-good image at `$BAK`:

   ```
   $ sh cli/macchanger.sh set 02:11:22:33:44:55     # no --assume-factory
   before:  01 00 08 00 aa bb cc dd ee ff
   after :  01 00 08 00 02 11 22 33 44 55           # written
   ```

   The exit status of that run is `0` when the driver picks the value up and `9`
   when it does not (written and verified, not in effect — `set` reports which).
   Either way the bytes above are the evidence, and no flag was needed: the
   refusal you were reading was the *capture*, and with an image in place there is
   no capture to refuse.

   `--assume-factory` is for when you have **no** such image and are willing to
   trade the record for the write:

   ```bash
   sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF --assume-factory
   ```

   **Read what that costs before you run it.** `--assume-factory` records the
   file as it is *now* — holding the spoof — as the "factory" image, so the
   record it keeps is of the address you are trying to get rid of, and a later
   `sh cli/macchanger.sh restore` puts that spoof back. It is a one-way trade:
   you get the factory MAC into the calibration file and lose the restorable
   record. If the value matters more than the record, do it; if the record
   matters more, use the app's typed path in step 2, which keeps the factory MAC
   as the restore target, a known-good image as above, or re-flash the vendor
   partition in step 4.
4. If you do not know the factory MAC either, the remaining options are a
   dump/reflash of the vendor partition for your exact model, or the vendor's
   service tool. Do not invent a value: the file holds calibration data around
   the MAC field, and a bad write there can affect WiFi calibration — the reason
   this project refuses to write blind in the first place.

If the calibration file itself was damaged (zeroed, or truncated), no app can
repair it from nothing: restore it from a dump of the same model, or reflash
that partition.

### If WiFi stops working entirely

1. **Reboot first.** If the runtime `ip link` fallback caused it, a reboot is
   the fix — that change is not persistent. Then make sure the fallback checkbox
   is off.
2. From a root shell, try `svc wifi enable` (or `sh cli/macchanger.sh wifi`).
3. Restore the factory image: **Restore factory** in the app, or
   `sh cli/macchanger.sh restore`. Both are in-place and length-checked; a
   restore that cannot verify itself tells you so instead of claiming success.
4. If the CLI refuses because there is no backup, use the app's typed-value path
   above (FACTORY MAC → **Save factory**) — the CLI will not accept a typed
   value without `--assume-factory`, and that flag trades the record for the
   write. Refusing to write is the intended behaviour, not a malfunction.
5. If the file's size changed (the tool would have refused, but a third-party
   tool may not have), reflash the vendor partition from a dump.

### Keep this in mind

The failure this project takes most seriously is not a MAC that does not change:
it is a calibration partition that gets truncated or zeroed, because that is the
one outcome no message, retry or reboot can undo. Everything above — the size
checks, the scan precondition, the in-place write, the verified backup, the
refusal to write when unsure — exists for that single reason.

---

## Why targetSdk is 30 (and must stay 30)

`targetSdkVersion 30` looks stale and is **deliberate**. Verified against AOSP:
it installs and launches with no "built for an older version of Android" dialog
on Android 14, 15 and 16 — the minimum supported target for that dialog is 28,
and the minimum installable target is 23 (24 on Android 15+). Raising it to
35/36 buys nothing on those releases and would enforce edge-to-edge display on
Android 15+, which this hand-built layout cannot absorb: it uses fixed paddings
and has no inset handling, so the title row and the log bar would sit under the
status and navigation bars. Android 16 removes the opt-out. Raising it is a
regression, not a cleanup.

`allowBackup="false"` stays too. Android's backup transport must not carry a
hardware MAC record around; durability is handled by the explicit record
described above, which the user controls and can export.

---

## Security, licence and changelog

* **Reporting a problem:** `SECURITY.md` — including what is in scope, the state
  of the shipped signing key (**compromised: the private key was distributed
  with its password**), and the fact that this repository records no contact
  address yet.
* **Licence:** MIT — see `LICENSE`. There is no warranty; this tool writes to
  your device's calibration data.
* **Hashes:** `CHANGELOG.md` lists every published APK by SHA-256 and the
  SHA-256 of the certificate that signed it. Check them before you install.
* **The audit:** `AUDIT.md` and `audit/PLAN.json` are the adversarial audit of
  the original code and the plan the current fixes follow. They are kept as a
  record — read them if you want to know what was wrong and why the code is
  shaped the way it is.
* **Offline checks:** `tools/stubcompile/` compiles `MainActivity.java` against
  hand-written `android.*` stubs at the project's Java 8 source/target levels
  (`sh tools/stubcompile/check.sh` → `STUBCOMPILE: PASS`). **It is a compilation
  gate only: nothing is executed and no behaviour is asserted** — no pure logic is
  extracted from the app, and the harness says so itself
  (`tools/stubcompile/README.md`, "What this gate does NOT prove"). What it proves
  is that the file still parses and type-checks; whether the write path behaves is
  a device fact. `tools/clitest/` runs the real CLI against synthetic NVRAM images
  under `/tmp` and asserts that refusals change nothing and that the write lands
  where it was found, not at a hardcoded offset. `tools/checks/` re-checks the
  specific claims this audit corrected (no keystore, no literal password, no
  redirection into a calibration path, no `cp` over one, zero permissions, and
  the README's claims against the code). `tools/package.sh` builds a reproducible
  `.tar.gz` with an inventory, and refuses while a signing key is still present.
  See `tools/README.md` for what each one can and cannot prove.
