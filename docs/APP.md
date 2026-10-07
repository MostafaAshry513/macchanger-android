# The app — the on-device buildable APK

The APK is a single screen with a code-built UI: no XML layouts, no AndroidX, no
third-party libraries, no `res/` directory. That is deliberate, so it stays
buildable on the device itself, with no PC and no Android SDK.

> **The APK in [`prebuilt/`](../prebuilt) is the pre-fix build.** It predates every
> fix described in [`AUDIT.md`](../AUDIT.md) and must not be installed as if it were
> this source. Build your own. The warning at the top of [../README.md](../README.md)
> is the short version.

## Install the prebuilt APK

Copy `prebuilt/MacChanger.apk` to the phone and tap it, or:

```bash
su -c 'pm install -r prebuilt/MacChanger.apk'
```

Grant root on first launch. Granting root is the only privilege this app ever asks
for: it declares no Android permission at all.

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
that is the pre-fix tree, **not** the current one; the current source has not been
built or signed, so there is no APK for it yet. Its APK SHA-256 and its signer
certificate SHA-256 are recorded in `CHANGELOG.md`, and that certificate
fingerprint is an **identifier, not a trust anchor:** the private key that matches
it was distributed with the original archive, so a matching fingerprint proves
which key signed a file, not that the file is the one this project produced. Do not
pin it. If the SHA-256 does not match the value in `CHANGELOG.md`, do not install
the file.

On **Android 5.0–6.0 (API 21–23)** the platform ignores the APK's v2/v3 signatures
and checks only the JAR signature (the Janus downgrade, CVE-2017-13156), so an APK
hash comparison matters even more there. Android 7.0+ (API 24+) verifies the
whole-file signatures and is not affected.

### The two signing identities

There are two, and they cannot replace each other:

1. **The shipped `prebuilt/MacChanger.apk`** — signed by a debug keystore
   (`CN=MacChanger`, alias `mac`) that shipped **next to the APK in the original
   distribution**, with its password published in that distribution's README.
   Verified: the certificate inside the APK is byte-identical to the certificate
   inside that keystore (same SHA-256, same serial `f95c6faeadff3f8d`), so anyone
   who has the original archive also has the private key. **Treat this identity as
   compromised.** It is a reproducible-build-style identifier only.
2. **Anything you build yourself** — signed with a keystore you supply through
   `KS`. `build.sh` has no default key: with `KS` unset it prints a refusal and
   exits non-zero without creating anything, and generating a new key requires the
   explicit `--new-key` flag.

Because they are different identities, **`pm install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** when you try to replace an installed
`versionCode 1` with your own build (or vice versa). The only way out is
`pm uninstall com.macchanger`, and **that deletes the app's private data** — which
on the shipped build was the only place the NVRAM backup and the factory MAC lived.

So, before you uninstall or replace anything:

* open the app and press **Export record**, which copies `WIFI.factory`,
  `WIFI.factory.path` and `factory.txt` to `/sdcard/Download` (or `/sdcard`);
* and/or copy both `/data/adb/macchanger/` and
  `/data/data/com.macchanger/files/nvram_backup/` off the device as root.

The full record layout, and what a wipe destroys, is in [SAFETY.md](SAFETY.md).

## Build the APK on the device (Termux)

Run it with `./build.sh`, **not** `sh build.sh`: the script uses bash-only features
and re-execs itself under bash if a POSIX shell got there first.

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
  `compileSdkVersion 30` (codename `11`) in its compiled manifest. A newer platform
  also compiles this source — the runtime minimum is API 21 — but an older one is
  not the level this project is built and shipped against.
* Also required: the device's own `/system/framework/framework-res.apk` (or a copy,
  via `FRAMEWORK=`), because `aapt` needs the platform's resource table and this
  project deliberately has no `res/` directory of its own.
* Signing is mandatory and explicit: `KS` (keystore) and `KS_PASS` are required;
  `KS_TYPE` defaults to `PKCS12`; `RELEASE_CERT_SHA256` optionally makes the build
  refuse to finish unless the signed APK's certificate matches the value you
  publish.
* The script stamps `versionCode` (default `$(date +%Y%m%d)`) and `versionName`
  (default `1.0+<git-rev>[-dirty]`), fails on `javac` errors, checks the packaged
  APK (contents, alignment, badging, icon, zero permissions), and prints the **APK
  SHA-256 and signer certificate SHA-256** at the end.

**Which of the two signing identities your build gets.** There are exactly two in
play, and they are different keys:

1. the identity that signed the shipped `prebuilt/MacChanger.apk` — the distributed
   debug keystore, which is **public and must be treated as compromised**, and
   which you should not reuse;
2. the identity of the keystore you pass in `KS`, which is the only thing your
   build will be signed with. `build.sh` refuses to invent one: with `KS` unset it
   prints a refusal and exits non-zero, and `--new-key` is required before it will
   create a key at all.

So a self-built APK is inevitably signed by identity 2 while an installed prebuilt
is identity 1, and `pm install -r` between them fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Read
[The two signing identities](#the-two-signing-identities) for what that costs and
what to copy off the device first.

Record both hashes and the source commit in `CHANGELOG.md` when you publish a build;
that is the only way a user can tell your build from any other.

## Use the app

* **FACTORY RECORD** (card) — the record this tool exists to protect. Type the
  factory MAC and press **Save factory** if you know it; it is stored as
  `typed, unverified` and is usable for a restore. **Export record** copies the
  `WIFI.factory.*` record files and `factory.txt` out of `/data/adb/macchanger` to
  `/sdcard/Download` (or `/sdcard`), through the root channel — no storage
  permission is requested or needed. It reports how many files it copied, so
  `exported 0 file(s)` is the signal that the durable directory holds nothing.
* **MAC ADDRESS** (card) — the `runtime` row (what the driver publishes), the
  `factory` row (the recorded factory MAC) and the `record` row (where that record
  came from). Long-press a row to copy its value.
* **SET MAC** — enter a MAC (or press **Random**) and apply. Random sets the
  locally-administered bit, which is normal for a spoof.
* **Restore factory** — writes the saved factory image back in place. It refuses to
  run without a record, refuses a record whose length does not match the target,
  and verifies the result byte-for-byte.
* **Runtime ip-link fallback (not persistent)** — the checkbox in the **SET MAC**
  card, directly under **Restore factory**. Off by default. Read
  [What it does](HOW-IT-WORKS.md#what-it-does) before enabling it: it can crash the
  WiFi service on MediaTek, and it does not survive a reboot.
* **NETWORK** (card) — the connected SSID, and the per-network MAC randomization
  label with the count over every saved network (`randomized on N of M saved
  networks`). Tap the privacy row to open WiFi settings. See
  [Randomization detection](HOW-IT-WORKS.md#randomization-detection-what-it-can-and-cannot-see).
* **Log bar** — the one-line bar at the bottom of the screen. Tap it to expand the
  retained lines, long-press to copy them to the clipboard. It records the file each
  action touched, the offset(s) it wrote and the before/after values. Read it; the
  app no longer announces a success it did not verify.

The recovery card's `durable` line reports whether `/data/adb` really holds the
pre-image; what to do when it does not is in
[SAFETY.md](SAFETY.md#where-the-record-lives).

## Why targetSdk is 30 (and must stay 30)

`targetSdkVersion 30` looks stale and is **deliberate**. Verified against AOSP: it
installs and launches with no "built for an older version of Android" dialog on
Android 14, 15 and 16 — the minimum supported target for that dialog is 28, and the
minimum installable target is 23 (24 on Android 15+). Raising it to 35/36 buys
nothing on those releases and would enforce edge-to-edge display on Android 15+,
which this hand-built layout cannot absorb: it uses fixed paddings and has no inset
handling, so the title row and the log bar would sit under the status and
navigation bars. Android 16 removes the opt-out. Raising it is a regression, not a
cleanup.

`allowBackup="false"` stays too. Android's backup transport must not carry a
hardware MAC record around; durability is handled by the explicit record described
in [SAFETY.md](SAFETY.md#where-the-record-lives), which the user controls and can
export.
