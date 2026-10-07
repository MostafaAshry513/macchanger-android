# Developing, and what can be verified offline

This file is for developers and ROM maintainers: what the tree holds, and which
claims a machine can check without a phone. For a first read, start at
[../README.md](../README.md).

## Repository layout

```
MacChanger/
  README.md                  the short guide (start here)
  docs/                      the depth this README points at:
    SAFETY.md                  risks, persistence, recovery, the record
    CLI.md                     every CLI command, option and exit code
    APP.md                     the APK: install, verify, build, UI rows, targetSdk
    DEVICES.md                 supported devices, per-vendor risk, the NVRAM layout
    HOW-IT-WORKS.md            the write path, locating the MAC, verification, the lock
    DEVELOPING.md              this file
  CHANGELOG.md               per-release APK SHA-256 and signer certificate SHA-256
  SECURITY.md                how to report, what is in scope, signing-key status
  LICENSE                    MIT
  AUDIT.md, audit/PLAN.json  the adversarial audit and the fix plan (read-only record)
  VERIFICATION.md            the independent final sign-off, and what it did not cover
  app/
    AndroidManifest.xml      zero <uses-permission> elements - deliberate
    src/com/macchanger/MainActivity.java
    build.sh                 on-device build script (Termux, run as ./build.sh)
  cli/
    macchanger.sh            standalone CLI, root, MediaTek only
  prebuilt/
    MacChanger.apk           the pre-fix APK (versionCode 1, see CHANGELOG.md)
  tools/stubcompile/       offline gate: compiles MainActivity.java against
                           hand-written Android stubs (compilation only)
  tools/clitest/           offline harness: runs the CLI against synthetic NVRAM
  tools/checks/            offline harness: greps for the hazards this audit fixed
  tools/package.sh         reproducible .tar.gz of the tree, into ../dist
```

There is **no keystore in this tree** and none in its git history: `ks.jks`, `*.jks`,
`*.p12`, `*.keystore`, `*.pk8`, `*.pem` and `*.der` are ignored, and `app/build.sh`
refuses to build without a keystore you supply. The key that signed the shipped APK,
however, is public — read
[the two signing identities](APP.md#the-two-signing-identities) before you install
anything.

The shipped `prebuilt/MacChanger.apk` is the **pre-fix** artifact and is kept only
because it is what this project shipped; its hash is published in
[../CHANGELOG.md](../CHANGELOG.md) so you can recognise it.

## The gates under `tools/`

Run all of them, not one of them. Each prints a verdict line and exits non-zero when
it fails, so they compose in a single command:

```bash
sh tools/stubcompile/check.sh && sh tools/clitest/clitest.sh && sh tools/checks/checks.sh
sh tools/package.sh
```

* **`tools/stubcompile/`** compiles `MainActivity.java` against hand-written
  `android.*` stubs at the project's Java 8 source/target levels
  (`sh tools/stubcompile/check.sh` → `STUBCOMPILE: PASS`). **It is a compilation gate
  only: nothing is executed and no behaviour is asserted** — no pure logic is
  extracted from the app, and the harness says so itself
  (`tools/stubcompile/README.md`, "What this gate does NOT prove"). What it proves is
  that the file still parses and type-checks; whether the write path behaves is a
  device fact.
* **`tools/clitest/`** runs the real CLI against synthetic NVRAM images under `/tmp`
  and asserts that refusals change nothing and that the write lands where it was
  found, not at a hardcoded offset. It redirects the device paths with a private
  mount namespace rather than by editing the script.
* **`tools/checks/`** re-checks the specific claims this audit corrected (no
  keystore, no literal password, no redirection into a calibration path, no `cp` over
  one, zero permissions, and the README's claims against the code). Each line is
  tagged `[PROOF]` or `[HEURISTIC]`, and `[PROOF]` means "this exact pattern is
  absent from the files scanned", never "this hazard is absent".
  **Where its doc-versus-code checks read:** they test the documentation as a set —
  `README.md` plus every `docs/*.md` — so a claim may live in either, and each check
  says which file now owns its claim: the CLI's MediaTek-only scope (`docs/CLI.md`),
  the `WIFI.factory.*` sidecar names (`docs/CLI.md`, `docs/SAFETY.md`), every
  `targetSdkVersion` stated anywhere (`docs/APP.md`, `docs/DEVICES.md`), the
  `zero <uses-permission>` claim (`docs/DEVELOPING.md`), the prebuilt APK's SHA-256
  (in `README.md`, in `CHANGELOG.md`, and in every file that tells the reader to hash
  it), and the non-persistent `ip link` and Android 12 sentences
  (`docs/HOW-IT-WORKS.md`). A `docs/` file that is missing counts as its claim being
  missing, so deleting one fails the gate rather than skipping the check. One
  exception: the check that every entry point under `tools/` is named reads
  `README.md` alone, because a tool named only in a docs file is one the reader who
  never opens `docs/` will not find.
* **`tools/package.sh`** builds a reproducible `.tar.gz` with an inventory, and
  refuses while a signing key is still present. `sh tools/package.sh --check PATH` is
  the hand-off gate: run it on whatever you are about to send.

See `tools/README.md` for what each one can and cannot prove, including its known
gaps: none of them can prove the app's behaviour on a device, `stubcompile` does not
execute anything, `clitest` drives the WiFi restart through a stub, and `checks` is
grep-based.

## Security, licence and changelog

* **Reporting a problem:** `SECURITY.md` — including what is in scope, the state of
  the shipped signing key (**compromised: the private key was distributed with its
  password**), and the fact that this repository records no contact address yet.
* **Licence:** MIT — see `LICENSE`. There is no warranty; this tool writes to your
  device's calibration data.
* **Hashes:** `CHANGELOG.md` lists every published APK by SHA-256 and the SHA-256 of
  the certificate that signed it. Check them before you install.
* **The audit:** `AUDIT.md` and `audit/PLAN.json` are the adversarial audit of the
  original code and the plan the current fixes follow. They are kept as a record —
  read them if you want to know what was wrong and why the code is shaped the way it
  is. `tools/package.sh` excludes them from the release archive on purpose.
* **The verification record:** `VERIFICATION.md` says which claims were re-run
  independently, which were not, and what risk remains. Its README line-number
  references are historical: `README.md` was restructured after that verification, so
  cite the section names, not the line numbers.
