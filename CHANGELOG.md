# Changelog

Every release entry is keyed to the `versionCode` its APK was built with.
`app/build.sh` stamps that itself — by default `versionCode = $(date +%Y%m%d)`
and `versionName = 1.0+<short-git-rev>[-dirty]` — and refuses to finish if the
stamp did not take, so a higher `versionCode` is always a newer build and two
builds of different sources can never look identical. The values baked into
`app/AndroidManifest.xml` (`1` / `1.0`) are fallbacks only.

**Every APK published from this project must be listed here with two hashes:**
the APK's own SHA-256, and the SHA-256 of the certificate that signed it. A user
who cannot compare a hash cannot tell an audited build from a trojan — which
matters more here than for most projects, because this app runs as root and the
first version shipped its signing key with its password published beside it
(see `SECURITY.md`).

The newest entry is the newest *released* APK. The change set that went into it is
listed under it, and every APK published from this project carries both hashes —
an unbuilt source tree has no artefact to hash, so nothing is listed without one.

---

## versionCode 20261007 — versionName 1.0+6ca70ae — the released APK

* File: `MacChanger-release.apk`, attached to
  [the v1.0.0 release](https://github.com/MostafaAshry513/macchanger-android/releases/tag/v1.0.0)
* APK SHA-256:
  `fa3ac5f26c6c81febe6f3d67b042756e2f9256fdacf2312f5d402558b6a68ebc`
* Signer certificate SHA-256:
  `41:49:05:58:E9:73:7F:61:BD:7A:67:18:9A:D8:FB:11:3E:58:3C:61:8D:89:5E:A1:A2:5F:78:2A:0F:A0:94:9B`
  (`CN=MacChanger release, OU=Android, O=MacChanger, C=US`, 4096-bit RSA,
  self-signed, valid to 2054). The same value is pinned in the repository variable
  `RELEASE_CERT_SHA256`, and `app/build.sh` refuses to finish unless the signed APK
  carries it.
* Source: commit `6ca70ae1ecc8c2a9e60a043a71cc36d55e29c371`, built, gated and
  verified by `.github/workflows/build.yml` on GitHub's runners. The private key is
  **not** in this repository: CI signs with the `KEYSTORE_BASE64` and
  `KEYSTORE_PASSWORD` secrets.
* Compiled manifest: `versionCode 20261007`, `versionName 1.0+6ca70ae`,
  `minSdkVersion 21`, `targetSdkVersion 30`, `compileSdkVersion 30` (codename `11`),
  package `com.macchanger`, and **no `<uses-permission>` element**.
* Contents: every fix in `AUDIT.md` — the change set listed below.
* **This APK cannot replace the prebuilt above.** They are signed by different keys,
  so `pm install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only
  way through is an uninstall — which deletes the app's private data. Export the
  record first (`docs/SAFETY.md`).

**Verification — against the file a user actually downloads:**

```
$ sha256sum MacChanger-release.apk
fa3ac5f26c6c81febe6f3d67b042756e2f9256fdacf2312f5d402558b6a68ebc  MacChanger-release.apk

$ apksigner verify --print-certs MacChanger-release.apk
Signer #1 certificate SHA-256 digest: 41490558e9737f61bd7a67189ad8fb113e583c618d895ea1a25f782a0fa0949b
```

`apksigner` prints that digest without separators; it is the same certificate as the
colon-separated fingerprint above.

---

## versionCode 1 — versionName 1.0 — the shipped prebuilt APK

* File: `prebuilt/MacChanger.apk`
* APK SHA-256:
  `1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da`
* Signer certificate SHA-256:
  `8F:ED:CA:F1:8E:92:BB:19:24:E5:DE:1C:BB:A5:EA:6F:8B:31:FB:03:91:08:EE:AF:FC:4F:9C:F5:11:86:F1:AF`
  (`CN=MacChanger`, serial `f95c6faeadff3f8d`, 2048-bit RSA, self-signed,
  SHA384withRSA)
* Source: commit `a82ffee8828585059a6ab3a7b53d2b9dec1c72ca`
  ("pristine MacChanger as shipped") — the pristine tree, i.e. **before** every
  fix described in `AUDIT.md`. The working tree's corrections are not in this
  APK.
* Compiled manifest: `versionCode 1`, `versionName 1.0`, `minSdkVersion 21`,
  `targetSdkVersion 30`, `compileSdkVersion 30` (codename `11`), package
  `com.macchanger`, and **no `<uses-permission>` element** (the APK's string
  pool contains no `uses-permission` name, so the zero-permission property holds
  in the artefact and not only in the source).

**Verification (run from the repository root; these are the actual outputs):**

```
$ sha256sum prebuilt/MacChanger.apk
1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da  prebuilt/MacChanger.apk

$ keytool -printcert -jarfile prebuilt/MacChanger.apk
Signer #1:
Certificate #1:
Owner: CN=MacChanger, OU=Dev, O=Dev, L=X, ST=X, C=US
Issuer: CN=MacChanger, OU=Dev, O=Dev, L=X, ST=X, C=US
Serial number: f95c6faeadff3f8d
Valid from: Tue Oct 06 15:12:42 UTC 2026 until: Sat Feb 21 15:12:42 UTC 2054
Certificate fingerprints:
	 SHA1: 05:1E:EB:FE:96:62:35:91:58:28:C1:42:ED:8F:7D:97:1B:FC:95:46
	 SHA256: 8F:ED:CA:F1:8E:92:BB:19:24:E5:DE:1C:BB:A5:EA:6F:8B:31:FB:03:91:08:EE:AF:FC:4F:9C:F5:11:86:F1:AF
Signature algorithm name: SHA384withRSA
Subject Public Key Algorithm: 2048-bit RSA key
```

**Status of that signing identity: compromised — do not trust it.** The same
certificate (identical SHA-256 *and* identical serial) is in the debug keystore
that shipped next to this APK in the original distribution, whose password was
published in that distribution's README. The fingerprint above is an
*identifier*, published so that you can confirm which key signed a file you
already have; it is not a trust anchor and must not be pinned or allowlisted.

**What this release does, in one line:** it is the original tool, with the
defects catalogued in `AUDIT.md` — including a backup that was not a
precondition for writing, a `restore` path that could truncate the calibration
file, and a published signing key. If you are running it, back your record up
before anything else: `README.md` describes the export and recovery paths.

---

## What went into versionCode 20261007 — the change set

This was listed as "not yet released" until the entry above was built and signed.
It is the change set that release contains, taken from `AUDIT.md`/`audit/PLAN.json`
and re-diffed against the tree before the tag was pushed rather than assumed to
have landed: this file is the only place a user is told what a `versionCode`
contains, so an inaccurate list is worse than a short one.

The release is signed by the identity recorded above and **cannot replace
`versionCode 1` in place**: `pm install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way out is an uninstall, which
deletes the app's private data. Export the record first.

Behaviour this release changes (documentation in `README.md` and
`SECURITY.md` describes the new behaviour, not the old):

* The pre-write NVRAM image is copied into **app-private storage**, length-checked
  against the image that was just read, and a failure there now aborts the write
  ("backup failed — NVRAM untouched") instead of being ignored. The push into
  `/data/adb/macchanger/` is attempted afterwards and is **best-effort** — its
  result is not a gate, so a device without `/data/adb` still writes, holding a
  recovery copy that an uninstall or "Clear data" destroys. The old wording
  presented `/data/adb` as the verified precondition; it is not one.
* The vendor/NVRAM scan reports per-path status (which candidate files exist,
  which root cannot read, and where the live MAC was not found) and names the
  case it hit instead of a blanket "no nvram match": `UNSUPPORTED DEVICE — nothing
  was written` only when *none* of the candidate paths exists (and then no WiFi
  restart and no runtime fallback are attempted at all), `no MAC match · <mac> is
  in none of the candidate files` when a readable file exists but does not carry
  the live MAC (the one case where, with the runtime fallback enabled, WiFi is
  restarted and the `ip link` command may run), and `candidate NVRAM present but
  not usable by root` when a candidate exists that root cannot read (no restart,
  no fallback).
* The write primitive is a size-bounded in-place `dd conv=notrunc,fsync` with a
  re-read of the patched window; the partition keeps its inode and the write is
  content-verified before anything is called a success.
* `restore` is in-place, length-checked and digest-verified; the CLI no longer
  fabricates a "factory" backup from an already-spoofed image, and `show` no
  longer captures anything.
* The runtime `ip link` fallback is opt-in, off by default, and is labelled
  `runtime only — NOT persistent, will not survive reboot`; it can no longer
  produce a green success verdict.
* Randomization detection parses `cmd wifi status` by content rather than by
  line position — on Android 12+ the SSID line moved, and reading only the first
  lines of that command made "I could not resolve your SSID" look like "your
  network randomizes the MAC". `unknown` stays `unknown` (AUDIT.md H5).
* `app/build.sh` requires an externally supplied keystore, fails the build on
  `javac` errors, stamps `versionCode`/`versionName`, and prints the APK SHA-256
  and signer certificate SHA-256 after post-sign gates.
* Added `LICENSE` (MIT), `SECURITY.md` and this file, and removed the published
  keystore password line from `README.md`.
* The CLI gained `doctor` (a read-only readiness report), `backup` (explicit
  capture), `panic`/`undo` (recovery from a damaged or truncated target),
  `--dry-run`, `--json`, `--quiet` and one exit code per failure class, so it can
  be scripted instead of read.
* Both front ends now take the same cross-process lock on
  `/data/adb/macchanger/lock` before reading the calibration file. The lock is an
  optimisation, not the safety property: the write itself refuses a target whose
  content changed since it was read (the interleaved-writer case).
* The off-device `MACCHANGER_TEST` path-redirect hook was **removed** from the
  shipped CLI. It let whoever controlled the environment point a root write at a
  path of their choosing, its gate was looser than its documentation, and its
  only purpose was testing — `tools/clitest/sandbox.sh` now redirects the paths
  from outside the process, under a private mount namespace, without the shipped
  script cooperating at all.
* `tools/` was added: `stubcompile` (compiles `MainActivity.java` against
  hand-written `android.*` stubs, with negative controls), `clitest` (22
  behavioural assertions against synthetic NVRAM images), `checks` (pattern gates
  labelled PROOF or HEURISTIC) and `package.sh` (builds the distributable archive
  and refuses while key material is present).
* `restore`/`panic` refuse an image whose recorded MAC is locally administered
  (a recorded spoof rather than a factory burn-in) unless
  `--allow-nonfactory-image` acknowledges it, so an unverified value can no
  longer be written back with exit 0; `wifi` exits `9` when the restart cannot be
  observed rather than reporting success; and a second positional argument is a
  usage error instead of silently replacing the first.
* **The build now works off the phone, and CI builds it.**
  `.github/workflows/build.yml` runs the gates and builds the APK on GitHub's
  runners; a `v*` tag attaches it to a Release. Three defects had to be fixed first,
  all found by actually running the build rather than reading it:
  * the manifest's launcher icon was `@android:drawable/ic_menu_manage`, which
    `aapt1` cannot resolve when it links against an SDK `android.jar` (it fails with
    "attribute value reference does not exist", on API 30 and API 34 alike). It only
    ever worked against the phone's own `framework-res.apk`, so every off-device
    build failed. The icon attribute is gone — the launcher shows the system default,
    as this app always did before the audit — and `build.sh` now fails only on an
    icon that is *declared* and fails to resolve, which is the defect that gate was
    added for.
  * the `versionCode`/`versionName` stamp used `aapt --version-code/--version-name`,
    which `aapt1` silently ignores (they are `aapt2` options, and `aapt2` only injects
    them when the manifest has none — this one carries fallbacks). Measured with
    build-tools 34: the flags exit 0 and the packaged APK still says
    `versionCode='1'`. The stamp is now substituted into a copy of the manifest, named
    `AndroidManifest.xml` in a directory of its own because `aapt1` refuses any other
    basename, and the read-back gates prove it took: a build of this source reports
    `versionCode 20261007 / versionName 1.0+<commit>[-dirty]` in the packaged APK.
  * `build.sh` printed `BUILT: <cwd>/<OUT>` even when `OUT` was absolute, naming a
    path that did not exist.
* `tools/checks/checks.sh` now requires that working stamping mechanism (computation,
  substitution and both read-back gates) instead of the aapt flags that look right
  but do nothing.
* The first CI runs were worth more than the review that produced the workflow: they
  found six things that no amount of reading had, listed here because each is now a
  documented reason for a line of `.github/workflows/build.yml` or a fix in `tools/`.
  `tools/stubcompile/selftest.sh` needed a sibling `../.pristine/` that only existed in
  the audit container (fixed: the baseline comes from the root commit, or is skipped);
  `tools/clitest/sandbox.sh` assumed `/data` exists before mounting tmpfs on it (fixed:
  the mount point is created); there is no `unshare` package to install because
  `unshare(1)` is part of util-linux, and the namespace suite needs `sudo`;
  `android-actions/setup-android@v3` now crashes trying to install the retired `tools`
  package, so the workflow calls `sdkmanager` directly; `app/build.sh` cannot be
  executed as `./build.sh` off a phone at all, because its shebang is Termux's own bash
  (the documented off-device invocation is `bash app/build.sh`); and its `OUT` is
  resolved against `app/`, so a CI that wants the APK elsewhere must pass an absolute
  path.
* `tools/stubcompile/selftest.sh` required a sibling `../.pristine/` directory for its
  second control, which only ever existed in the container the harness was written
  in: the control failed in every fresh clone, and CI found it on the first run. It
  now takes the independent baseline from the repository's own root commit through
  `git`, and skips that control with a note — rather than failing — when the checkout
  holds no distinct revision, as a shallow clone does not. The suite still fails when
  the gate it checks is broken, which is verified in the same run.
