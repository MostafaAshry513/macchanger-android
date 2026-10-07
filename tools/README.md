# tools — what can be verified without an Android SDK

There is no Android SDK, no `aapt`/`d8`/`apksigner` and no network on this machine, so the
APK cannot be rebuilt or re-signed here. These four tools are what *can* be checked
offline, and they cover different things — run all of them, not one of them.

| tool | what it proves | run it with |
| --- | --- | --- |
| [`stubcompile/`](stubcompile/README.md) | `app/src/com/macchanger/MainActivity.java` still compiles: hand-written `android.*` stubs are compiled first, then the app source against them, at the project's Java 8 source/target levels | `sh tools/stubcompile/check.sh` |
| [`clitest/`](clitest/README.md) | `cli/macchanger.sh` behaves: the **real** script runs against synthetic NVRAM images under `/tmp` with every device path redirected and the WiFi restart stubbed — read-only commands change nothing, refusals leave the target byte-identical, the write lands at the located offset, and the exit codes match the classes the CLI documents | `sh tools/clitest/clitest.sh` |
| [`checks/`](checks/README.md) | the specific claims and hazards this audit corrected have not come back: no keystore, no literal password, no redirection into a partition path, no `cp`/`mv`/`install`/`rsync`/`tee` onto one, no unbounded `seek=`, no blind sleep on the restart path, zero permissions in the manifest *and* in the shipped APK, and the README's claims still match the code. Each line is tagged `[PROOF]` or `[HEURISTIC]`, and `[PROOF]` means "this exact pattern is absent from the files scanned", never "this hazard is absent" — the two write-path checks are `[HEURISTIC]` for exactly that reason | `sh tools/checks/checks.sh` |
| [`package.sh`](package.sh) | the tree can be packaged reproducibly and honestly: a `.tar.gz` in `../dist`, built twice to prove the bytes are deterministic, with a SHA-256 and a per-file inventory — refusing outright while signing key material is anywhere in the tree, found **by name or by keystore magic bytes in any file** (including files it would not package, which is how the leaked PKCS#12 key copied to `app/ks.tmp` used to slip past a name-only gate), and scanning the archive it has just built before it calls it a release | `sh tools/package.sh` |

Each tool prints a verdict on its last line (`STUBCOMPILE:`, `CLITEST:`, `CHECKS:`,
`PACKAGE:`) and exits non-zero when it fails, so they compose in a single command:

```
sh tools/stubcompile/check.sh && sh tools/clitest/clitest.sh && sh tools/checks/checks.sh
```

## Hand-off: what the deliverable is, and how to prove it

The deliverable is **this tree** (`MacChanger/`) or **the `.tar.gz` `tools/package.sh`
writes**. It is *not* the directory that contains the tree. On the machine this was
audited on, that enclosing directory also holds the audit (`AUDIT.md`, `audit/`), the
pristine snapshot, and a signing keystore — so a hand-off made with `zip -r
../handoff.zip .` or `cp -a` of the enclosing directory re-ships the private key next to
the APK. That is how the key leaked the first time, which is why the check below exists
and why `tools/package.sh` now refuses to call an archive a release until the archive
itself has passed it.

```
sh tools/package.sh --check PATH [--check PATH ...]     # hand-off gate
```

`PATH` may be a directory, a `.tar.gz`/`.tgz`/`.tar` or a `.zip`. The gate fails on

* a signing key by **name** (`*.jks`, `*.keystore`, `*.p12`, `*.pem`, … — case-folded,
  so `KS.JKS` cannot slip through);
* a signing key by **content**, because a name is not a promise: JKS (`fe ed fe ed`),
  JCEKS (`ce ce ce ce`), a PKCS#12 PFX header (`30 82 .. .. 02 01 03 30 82`) or a PEM
  private-key header at offset 0, whatever the file is called. The key this project leaked
  is a PKCS#12 file that kept the `.jks` name, so both tests are needed;
* a signing key in a git **history** (`git log --all --name-only` over every commit),
  which a scan of the working files cannot see because the objects are compressed;
* any `AUDIT.md` or `audit/` entry — the audit quotes the removed keystore password and
  is not written for users. `--allow-audit` downgrades exactly this, and only this, from
  FAIL to WARN: it never downgrades a key;
* an archive member path that escapes the extraction root (`../x`, `/x`), which is
  reported instead of unpacked;
* any file a complete deliverable must have (`README.md`, `app/build.sh`,
  `app/AndroidManifest.xml`, `app/src/com/macchanger/MainActivity.java`,
  `cli/macchanger.sh`, `prebuilt/MacChanger.apk`, `tools/package.sh`).

It prints one `HANDOFF: PASS|WARN|FAIL <path> - <why>` block per artifact and exits 0
when every artifact passed. Run it on whatever you are about to send, whatever built it:

```
sh tools/package.sh --check /path/to/dist/MacChanger-1.0-vc1.tar.gz
sh tools/package.sh --check /some/other/directory             # FAIL: scans what is there, keys included
sh tools/package.sh --check . --allow-audit                   # WARN: tree, for an auditor
```

Packaging itself says the same thing twice: it scans the directory around the tree and
warns, naming any key it finds there, and it runs the gate above on the archive before
printing `PACKAGE: OK`. Neither is a substitute for checking the artifact that actually
leaves the machine.

`--check` on an **archive** also compares it with the tree this script lives in — every
member, byte for byte, in both directions — because an archive that no longer matches the
tree is a superseded revision, and a self-consistent stale archive (its own `.sha256` matches
itself, its own inventory lists itself) is otherwise indistinguishable from a current one.
That comparison is a `FAIL`, not a note, so `HANDOFF: PASS` can no longer be read as an
endorsement of a superseded archive; the remedy is the one the verdict line names,
`sh tools/package.sh`. Pass `--no-freshness` when checking an artifact that came from
somewhere else: the verdict then says in words that freshness was not checked.

All four are POSIX `sh` with no dependencies beyond a JDK, busybox, coreutils, `tar`,
`gzip`, `unzip`, `od`/`dd` and (for the CLI sandbox) util-linux `unshare` with mount
privileges; `git` and `unzip` are used by `package.sh --check` when they are installed and
their absence is reported rather than hidden. None
of them edits the app or the CLI, none needs the network, and none is required to build the
APK: they are verification, and they are the reason a change to this project can be
believed rather than merely described.

Known gaps, stated rather than implied:

* none of them can prove the app's behaviour on a device — that needs a phone;
* `stubcompile` checks compilation, not intent: it runs `javac`, and it does not execute the
  write path or assert a single behaviour of the Java source;
* `clitest` drives the WiFi restart through a stub, so it tests the CLI's verdicts and not
  the real driver reload;
* `checks` is grep-based, and every heuristic line says what it cannot see. Its `[PROOF]`
  lines are proofs about *the text that was scanned*, not about the code's behaviour: a
  `PASS` means "this exact pattern does not occur in these files", and the two write-path
  checks (3 and 4) are labelled `[HEURISTIC]` rather than `[PROOF]` because a fresh-hunt
  verifier reproduced them passing on a tree that contained the hazards they are named
  after;
* `package.sh --check` is a gate on *names, keystore magic bytes, git history paths and
  archive member paths*, not a byte-level proof of innocence: a key re-encoded to hide the
  magic would pass it, and on a `pre-.git` machine the history branch has nothing to read. It
  catches the mistake that actually happened — the key shipped next to the APK under its own
  name — and it says so in its own output rather than implying more. `package.sh` itself
  (not just `--check`) refuses on key material found by name *or* magic bytes anywhere in the
  tree;
* the prebuilt APK predates these fixes and is shipped as the historical artifact it is,
  with its hash published in `CHANGELOG.md`.
