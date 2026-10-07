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

The newest entry is the newest *released* APK. Fixes that have not been built and
signed yet are listed under "Not yet released" below it and carry no hashes on
purpose: an unbuilt source tree has no artefact to hash.

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

## Not yet released — no APK has been built or signed for this source

The working tree carries the fixes from `AUDIT.md`. **No APK has been built
from it here** (this environment has no `aapt`/`d8`/`zipalign`/`apksigner`), so
there is nothing to hash and nothing to publish. When the maintainer builds it
on-device, `app/build.sh` prints the APK SHA-256 and the signer certificate
SHA-256 and this section must be turned into a real entry with both, keyed to
the stamped `versionCode`, plus the source commit.

The list below is the intended change set, taken from `AUDIT.md`/`audit/PLAN.json`
— it is what the source in this tree is being fixed *to*, not a claim that has
already been verified against a built artefact. **Re-diff the tree and drop
anything that did not land before you publish the entry**: this file is the only
place a user is told what a new `versionCode` contains, so an inaccurate list is
worse than a short one.

The next build will be signed by a key supplied through `KS` and is **not** the
identity above, so it cannot replace `versionCode 1` in place: `pm install -r`
fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the only way out is an
uninstall, which deletes the app's private data. Export the record first.

Behaviour the next build changes (documentation in `README.md` and
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
