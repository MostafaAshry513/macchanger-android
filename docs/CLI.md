# The CLI — `cli/macchanger.sh`

The standalone CLI. Root shell, busybox, **MediaTek only**. Its own `help` text is
the authority for every command line below (`sh cli/macchanger.sh help`).

The CLI is **MediaTek only**: it targets
`/mnt/vendor/nvdata/APCFG/APRDEB/WIFI` and refuses to run if that file is missing
— it will not guess a calibration path on another vendor's device, because writing
a wrong guess into a calibration partition as root is worse than not supporting it.
**The app's multi-vendor path list does not apply here.** If you are on Qualcomm,
Samsung or Unisoc, use the app, and read [DEVICES.md](DEVICES.md) first.

## Commands

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

Aliases (the canonical spelling is preferred; these exist for muscle memory):
`selftest`, `check`, `diag` = `doctor`; `status` = `show`; `save` = `backup`;
`rand` = `random`; `reset` = `restore`; `undo` = `panic`.

Which commands the MediaTek refusal covers, precisely, because one command is
deliberately exempt and the difference matters when you are diagnosing a device:
`require_nvram()` exits `4` ("NVRAM file not found"), and it is called by `backup`,
`set` (and therefore `random`, which calls `set`), `wifi`, `restore` and `show`.
`panic` refuses the same states itself — it needs the file to *exist* and to be a
regular file (`[ -e "$NV" ]`, `[ -f "$NV" ]`), so it still works on a corrupt or
truncated one, which is the whole point of the command. **`doctor` is the one
command that runs with no calibration file at all**, by design: it is the read-only
report whose job is to tell you that the file is missing, so it reports the absent
path instead of exiting. A `doctor` that exits `0` on such a device is not a false
success — read its `verdict:` line.

## Options

| Option | Applies to | What it does |
| --- | --- | --- |
| `--iface=NAME` | all | Use this WiFi interface. Required when more than one `wlan*` exists, or when detection finds none. |
| `--assume-factory` | `backup`, `set`, `random` | Capture the factory image even when the NVRAM cannot be shown to hold the live runtime MAC; the image is then recorded as unverified. Read what that costs in [SAFETY.md](SAFETY.md#if-the-backup-is-gone) before you use it. |
| `-n`, `--dry-run` | every writing command | Print the file, the offset and the bytes that would change, change nothing, and exit with the status the real run would have for the checks it evaluates. See the limits below. |
| `--no-reinit` | `restore`, `panic` | Write the image but do not restart WiFi (reboot instead). |
| `--allow-damaged-backup` | `set`, `random` | Proceed even though the recorded factory image does not match its digest. |
| `--allow-undigested` | `panic` | Restore an image that has no recorded digest and therefore cannot be proven intact. |
| `--allow-foreign` | `panic`, `set`, `random` | Use an image recorded as captured from a different calibration path. |
| `--allow-nonfactory-image` | `restore`, `panic` | Write back an image whose recorded MAC is locally administered, i.e. a recorded spoof rather than a factory burn-in. Without it such an image is refused (exit `7`): an intact image is not the same thing as a factory image, and `doctor` already reports the same record as a blocker. |
| `-q`, `--quiet` | all | Hide progress lines (`[*] ...`); results and warnings are still printed. |
| `--json` | `show`, `doctor` | One JSON object on stdout instead of the human lines; all other output goes to stderr. |
| `-h`, `--help` | — | The CLI's own help text. |

## Behaviour worth knowing

* `doctor` (alias `selftest`) is strictly read-only — it creates nothing at all,
  not even a temp file — and reports root, the busybox applets the write path
  actually depends on, the resolved interface, every candidate MTK NVRAM path with
  its size, mode, mount flags and whether the runtime MAC is in it, the recorded
  factory MAC with its provenance and digest state, the backup directory, and a
  `verdict: yes/no` with the blockers that would stop a write.
* It needs root and busybox (for `dd`, `hexdump`, `awk`, `cmp`, `sha256sum`); set
  `BB=` if busybox is not at the default Magisk path.
* It resolves the WiFi interface instead of assuming `wlan0`; pass `--iface=NAME`
  when detection is ambiguous or finds nothing.
* `set` locates the MAC field by **scanning** for the MAC the driver is using, and
  refuses to write unless the bytes at the matched offset are exactly that MAC. It
  writes 6 bytes in place with `conv=notrunc,fsync` and verifies a whole window
  around the offset afterwards. It never `cp`s or `mv`s over the file.
* `--dry-run` on `set`, `random`, `backup`, `restore` and `panic` prints the file,
  the offsets, the bytes that would change and the window before/after, and changes
  nothing. It exits with the status the real run would have **for the checks it
  evaluates**: the write plan itself, and (on `set`/`random`, when an image already
  exists) a recorded image that does not match its digest — that refusal is exit
  `7`. It is not a complete prediction: a real `set` that finds no image first
  captures one, and the dry run does not run that capture decision, so a refusal
  that only arises there — a live MAC that is locally administered, or a MAC field
  that cannot be located — comes back as exit `6` from the real run while the dry
  run still exits `0`. Neither does it notice an image path that is not usable as
  one (a symlink, for example), which the real run refuses with exit `7`. Treat a
  `0` as "the plan is sound", not as "the run will succeed". `--dry-run` writes
  nothing and therefore takes no lock.
  **`wifi` refuses `--dry-run` instead of ignoring it** — a restart *is* the
  command, so there is nothing to preview, and the option is parsed globally rather
  than per command. `sh cli/macchanger.sh wifi --dry-run` therefore exits `1` with
  `--dry-run is not available for 'wifi': restarting the radio is the whole
  command …` and does not touch the radio. (An earlier revision accepted the flag
  and performed the real disable/enable.) `--dry-run` is accepted and ignored by
  `show` and `doctor`, which write nothing anyway; neither is a command you need it
  on.
* It captures the factory image only while the NVRAM still holds the live runtime
  MAC, and it refuses to record a locally-administered value as "factory" (that bit
  is evidence of an existing spoof) unless you pass `--assume-factory`. `show`,
  `doctor` and `restore` never capture anything.
* `restore` needs an existing image of exactly the target's size, verifies its
  recorded digest, writes it back in place and compares the result byte-for-byte.
  `panic` is the recovery command: it needs neither a readable MAC nor an
  interface, restores a **truncated** target (which `restore` refuses), and refuses
  instead of guessing when the image has no digest (`--allow-undigested`), has a
  digest that does not match, was recorded from a different path
  (`--allow-foreign`), carries a **locally administered** value at its MAC offset
  (`--allow-nonfactory-image`), or is too short to be a faithful restore. That
  locally-administered refusal is deliberate: an intact image is not the same thing
  as a factory image, so restoring one is not a recovery — the same record is a
  blocker in `doctor` — and it takes the flag to say you meant it.
* At most one positional argument is accepted, so `set AA:BB:... EXTRA` is a usage
  error rather than a validation error naming `EXTRA`, and `show a b` fails instead
  of ignoring `b`.
* `set` exits non-zero when the driver is not using the MAC it wrote, and says
  whether that is because the driver ignored it or because `svc wifi
  enable/disable` failed. `restore` and `panic` report the verified state of the
  file instead, so a driver that has not reloaded the NVRAM yet is a warning there,
  never a false failure.
* The WiFi restart is polled, not slept through: the state the ROM reports (or the
  MAC the driver publishes) is read back within a bounded wait, so a slow-but-legal
  reload, a toggle the platform refused (airplane mode) and a ROM that reports no
  WiFi state at all are told apart, instead of all being reported as the driver
  ignoring the MAC.
* `--quiet` hides the `[*] …` **progress** lines only — the ones the CLI emits
  through its `info()` helper. Results and warnings, including some lines that also
  start with `[*]` (for example `[*] saved factory image -> …`, which is a result,
  not progress), are emitted through `say()` and are printed even under `--quiet`.
  So do not use `-q` when you are scraping output; use `--json` where it is offered.
  The CLI's own help states this precisely: "hide progress lines ('[*] ...');
  results and warnings are still printed".
* Its record lives in `/data/adb/macchanger/`, mode 700, files mode 600 — the same
  directory the app uses. Both write `WIFI.factory` and the `WIFI.factory.path` tag
  naming the calibration path the image came from, plus `WIFI.factory.offset` (where
  the MAC field sits in the image, or `?N` when that offset was assumed rather than
  located) and `WIFI.factory.sha256`; both check that digest before installing an
  image. `panic` refuses an image whose `.path` names a different calibration file,
  so an image captured for another vendor's path is never written into this one.
  **An image captured by either front end is usable by the other:** the app resolves
  the factory value from its own stores first (the value you typed, `factory.txt`,
  the app-private mirror) and then out of the shared pre-image, at the offset
  recorded beside that image — so a factory image captured by the CLI is usable by
  the app's *Restore factory* without a `factory.txt` next to it. In the other
  direction the CLI's `doctor` reads the app's `factory.txt`. The full record
  layout is in [SAFETY.md](SAFETY.md#where-the-record-lives).

## The cross-process lock

Every writing command (`backup`, `set`, `random`, `restore`, `panic`) takes the
same cross-process lock as the Android app — `mkdir /data/adb/macchanger/lock`,
abandoned after 300 s — before it reads the NVRAM, and releases it only while it
still owns it. While the app holds it the CLI refuses with exit `6` and writes
nothing, and the app refuses while the CLI holds it, so the two front ends cannot
rewrite the same calibration file at the same time. `--dry-run` writes nothing and
therefore takes no lock.

## Environment variables

**No environment variable can redirect this tool's calibration paths.** `BB`,
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
path, and therefore the write, at a path the caller chose. Its gate was also looser
than the documentation claimed: the code was `[ -n "$MACCHANGER_TEST" ]`, so
`MACCHANGER_TEST=0`, `=false`, `=off` and `=no` all armed the redirect while the
README and `help` both said "without `MACCHANGER_TEST=1` those variables are
ignored". That was a root-write primitive reachable by whoever controls the
environment of the invocation, and this script's own `must run as root` message
tells users to invoke it as `su -c …`, which passes that environment through. **The
hook is gone**; do not reintroduce it in any form, and do not re-add a "the
environment cannot redirect a calibration write" sentence to any document unless the
code still matches it. Read
[Test-only redirects, and why there are none](../SECURITY.md#test-only-redirects-and-why-there-are-none)
in `SECURITY.md` for the history and for how the CLI is tested now. Off-device runs
redirect the paths with a private mount namespace instead (`tools/clitest/`), which
the script needs to know nothing about.

`BB` is the one environment variable that still changes what this script executes
(`BB=${BB:-/data/adb/magisk/busybox}`): it names the busybox binary used for
`dd`/`hexdump`/`awk`/`cmp`/`sha256sum`. It selects a **tool**, never a target path,
it is probed before use (`"$BB" true || die`), and only the built-in default may be
replaced by a `busybox` found on `PATH` — but it is caller-controlled, so treat it
as part of the same trust boundary as root itself.

## Exit codes

Exit codes are per failure class, so the CLI can be scripted:

| Code | Meaning |
| --- | --- |
| `0` | OK. |
| `1` | Usage error, bad MAC, unknown option or command. |
| `2` | Not root. |
| `3` | busybox missing, or an applet unusable. |
| `4` | NVRAM path missing, or not a regular file. |
| `5` | WiFi interface not resolved, or ambiguous. |
| `6` | A precondition on the target failed (refusing to write blind), or another macchanger operation holds the lock. |
| `7` | Factory image missing, damaged, foreign, or refusing to overwrite one. |
| `8` | The write, or its verification, failed. |
| `9` | `set`/`random`: written and verified, but the driver is not using the MAC. `wifi`: the restart was issued but could not be observed on this ROM, so the caller is not told "reloaded" (a ROM that reports no WiFi state and a driver that publishes nothing leaves the outcome unknown). |

## Where to go next

* What a refusal means for your device, and what a wipe does not undo:
  [SAFETY.md](SAFETY.md).
* The file being edited, and what it looks like: [DEVICES.md](DEVICES.md).
* The write path, the scan and the verification, in detail:
  [HOW-IT-WORKS.md](HOW-IT-WORKS.md).
