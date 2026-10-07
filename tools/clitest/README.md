# clitest — the behavioural sandbox for `cli/macchanger.sh`

`cli/macchanger.sh` rewrites a calibration partition as root. Nothing on this machine can
run the APK, and nothing should ever point this script at a real device's NVRAM, so the
CLI was the one part of the project with no way to check a change short of flashing a
phone. `clitest.sh` runs **the real script** — never a copy with rewritten constants —
against synthetic NVRAM images under `/tmp`, with every device path redirected and the
WiFi restart driven by a stub `svc`.

```
sh tools/clitest/clitest.sh                    # run the suite (≈40 s)
sh tools/clitest/clitest.sh --self-test        # prove the suite can fail
sh tools/clitest/clitest.sh --keep             # keep the /tmp scenarios
CLI=/path/to/cli/macchanger.sh sh tools/clitest/clitest.sh   # test another revision
```

The last line is exactly `CLITEST: PASS` or `CLITEST: FAIL`, and the exit status is 0 only
on PASS (1 on FAIL, 2 on a harness or usage error). A harness problem prints
`CLITEST: ERROR` — it is never reported as a pass.

## How the CLI is redirected (and why it is not edited)

The script's device paths cannot simply be set from the environment the way `BB=` can, so
the suite uses whichever mechanism the revision under test offers. **On this tree it is
always `namespace`**, because the hook this table's first row describes no longer exists —
see the note under the table.

| redirect | how it works | when it is used |
| --- | --- | --- |
| `override` | an off-device testing hook the CLI *used* to carry: `MACCHANGER_TEST` plus `MACCHANGER_DIR`, `MACCHANGER_NV`, `MACCHANGER_NET`, announced on stderr. Assertion **A0** checks the gate — setting `MACCHANGER_NV` *without* `MACCHANGER_TEST` must redirect nothing. | only for an older revision that still carries the hook; `auto` selects it then, and `--redirect=override` demands it |
| `namespace` | `sandbox.sh` runs inside `unshare -m` and bind-mounts `$scenario/nvram`, `$scenario/record` and `$scenario/net` over `/mnt/vendor/nvdata/APCFG/APRDEB`, `/data/adb/macchanger` and `/sys/class/net`, with `tmpfs` over `/mnt`, `/data` and `/sys` so nothing is created outside the namespace. | **the current `cli/`, which has no hook**, and any other revision without one — e.g. the pre-fix revision from this repository's own history: `git worktree add /tmp/pristine a82ffee` |

**Why the hook is gone, since this suite once depended on it.** The hook let the caller
choose the calibration path of a root tool, and nothing about the redirected target was
validated; its gate was also `[ -n "$MACCHANGER_TEST" ]` rather than `=1`, so
`MACCHANGER_TEST=0` armed it while the documentation said only `=1` would. A0 could not
catch that — it tests the *unset* case, which is the one case the loosened gate still
handled correctly. The hook was removed rather than tightened, and the suite now redirects
the paths from outside the process, which needs no cooperation from the script and cannot
be re-armed by a stray environment variable. `SECURITY.md` ("Test-only redirects, and why
there are none") records the history and the regression rule. Consequence for this suite:
on the current tree A0 is **skipped**, not passed, and the run reports
`redirect=namespace` — so the total is one lower than the same revision would produce under
`--redirect=override`. Read the per-assertion lines and the id list rather than a fixed
number: the suite gains assertions as the CLI grows, and the count in the verdict line
(`clitest: N of N assertions passed … redirect=…`) is the authority. What matters is that
`0 failed` and that no assertion is silently missing — a skipped A0 is announced as such.

Nothing in `cli/` is modified by this suite: `cli/` belongs to another owner, and a test
against a patched copy would prove something about the copy. Where the suite needs a fact
about the CLI it **reads that fact out of the CLI** — the exit-code classes
(`EX_USAGE`, `EX_PRECOND`, …) are parsed from the script, so a renumbering cannot make the
suite assert a stale literal, and a revision with no per-class codes (the shipped
snapshot) falls back to "refusals are non-zero".

The WiFi restart is simulated by `stubs/svc`, which is placed first on `PATH`. It reads
the image from `$TEST_NV_FILE` and publishes the six bytes at `$TEST_MAC_OFFSET` into
`$TEST_NET_DIR/wlan0/address`, i.e. it behaves like a driver that picked the write up.
With `TEST_SVC_NORELOAD=1` it behaves like a driver that ignored it. The stub never reads
or writes a device path: on a host where someone else has left a fixture at
`/mnt/vendor/nvdata/APCFG/APRDEB/WIFI`, that file is not what this suite measures.

## What is asserted

| id | assertion |
| --- | --- |
| A0 | the CLI's test hook is gated on `MACCHANGER_TEST` — **override mode only; skipped on this tree, where `cli/` has no hook and the suite runs `redirect=namespace`** |
| A0b | the hook gate requires the literal value: `MACCHANGER_TEST=0` (and any other non-`1` value) redirects nothing — **override mode only, likewise skipped here**. This is the assertion that catches the gate the hook actually shipped with (`[ -n "$MACCHANGER_TEST" ]`, which `0` armed); it exists so that an override-mode revision cannot pass with a truthiness gate again |
| A1 | `show` creates and modifies nothing: sha256 + size + mtime + file listing of the whole device-visible tree, before and after |
| A1b | `show` really read the sandbox device, so A1 is not vacuous |
| A2 | **negative control**: the A1 assertion FAILS for `set`, proving it can detect a write |
| A3 | `restore` with no factory image: refusal exit, nothing created, target untouched |
| A4 | `restore` of a 32-byte image over a 64-byte NVRAM: refusal exit, target byte-identical, still 64 bytes |
| A5 | `set` whose live MAC is nowhere in the NVRAM: refusal exit, target byte-identical, no "factory" image captured |
| A6 | `set` while the NVRAM holds a locally administered (already spoofed) MAC: refusal exit without `--assume-factory`, target byte-identical |
| A7 | `backup` **and** `backup --assume-factory` on an NVRAM whose live MAC is **multicast** (`01:aa:bb:cc:dd:ee`): refusal exit, target byte-identical, and neither an image nor the app record is captured — a multicast address is not a station address at all, so `--assume-factory` (which acknowledges a plausibly spoofed value) must not admit it either |
| A7b | `backup` on an NVRAM whose live MAC is **all-zero**: refusal exit, target byte-identical, nothing captured |
| A8 | with a fresh lock held by the app, `set` exits without writing (M2: the two front ends cannot write the same calibration file at once) |
| A8b | an abandoned lock (400 s old) is broken as stale, the write proceeds, and the lock is released afterwards |
| B1 | live MAC at offset 40 and a decoy at offset 4: exit 0, the MAC lands at 40, the decoy is untouched |
| B2 | the write is 6 bytes in place: length unchanged, and the stub driver re-read the found offset (the svc log is the evidence) |
| C1 | driver ignores the change: the apply-class exit status, while the NVRAM does hold the new MAC |
| C2 | `restore` of a good image: exit 0, target == image byte-for-byte, length unchanged, runtime back to the factory MAC |
| C3 | the documented exit codes: help/usage 0, every refusal in its own class |
| C4 | all nine refusal/usage runs left the target byte-identical |

Every assertion prints `PASS` or `FAIL` with the observed values, so a failure is
diagnosable without re-running anything.

This table is the **id list**, and the suite's verdict line counts **lines**, so the two do
not have to match numerically and no arithmetic on them is a check. Two effects are normal
on this tree: A0 and A0b are skipped (announced as `INFO: A0/A0b skipped: this redirect
(namespace) does not use the CLI's own hook`), which removes two lines, and an id that runs
over several inputs emits one line per input — A7 emits two (`backup` and `backup
--assume-factory`), which adds one back. So 19 ids here produce 18 lines. If a line names an
id this table does not list, the table is stale; if an id this table lists produces no line
and no skip notice, the assertion is missing. Print both with
`sh tools/clitest/clitest.sh | grep -E '^(PASS|FAIL|INFO)'`.

## Proving the suite can fail

A test that cannot fail is decoration, so the failure path is demonstrated, not asserted
in prose:

* **A2** runs the read-only assertion against `set`, which writes. The assertion must
  report "the device-visible tree changed"; if it calls the tree unchanged, A2 FAILs and
  says the control is broken.
* **`--self-test`** runs the whole suite twice as a child process — once unmodified (must
  exit 0), once with `CLITEST_INJECT_FAIL=1`, which registers one deliberately false
  assertion (must exit non-zero and must print that `FAIL` line).

The suite has also been run against the pre-fix revision from this repository's own
history (`git worktree add /tmp/pristine a82ffee`, then
`CLI=/tmp/pristine/cli/macchanger.sh sh tools/clitest/clitest.sh --redirect=namespace`),
where **15 of the 22 assertion lines fail** (measured:
`clitest: 7 of 22 assertions passed, 15 failed`): `show` captures a backup (A1), `restore`
with no image fabricates one and returns 0 (A3), a short image truncates the target (A4),
`set` writes blind when the live MAC is not in the file (A5) or is already a spoof (A6),
that revision takes no cross-process lock so `set` writes while the app holds one (A8) and
leaves the lock behind (A8b), the write lands at a hardcoded offset 4 (B1/B2), the
driver-ignored case still exits 0 (C1), an intact image holding a locally administered
value is restored as if it were the factory MAC and `panic` does not exist to refuse it
(D1/D3), a second positional silently replaces the first (D4), and all-zero, broadcast and
multicast MACs are written instead of refused (C3/C4). A7/A7b and D2 pass there for a
different reason — that revision captures no factory image at all, and it writes an image
uncritically — so those passes say nothing in its favour. That is the audit's finding
list, reproduced by the harness.

## Requirements and safety

* root (the CLI refuses to write otherwise, `EX_NOTROOT`), a POSIX shell, **busybox**
  (which the CLI hard-requires), `dd`/`od`/`stat`/`sha256sum`/`cmp`.
* `--redirect=namespace` additionally needs util-linux `unshare` and mount privileges
  (Linux, `CAP_SYS_ADMIN`). The suite probes this up front and exits 2 with a specific
  message rather than reporting a pass it cannot support.
* No network, no Android device, no Android SDK, no Gradle, no `res/`.
* **Run it on a host or in a container, not on the phone.** The redirect is what keeps the
  run off the real calibration file, and on a device the stub `svc` cannot shadow the real
  one.

## Layout

```
tools/clitest/
├── clitest.sh     # the suite: scenarios, assertions, --self-test, verdicts
├── sandbox.sh     # mount-namespace redirect: the mechanism used for the current cli/
├── stubs/svc      # stand-in for Android's `svc`: simulates the driver reload
└── README.md      # this file
```

## Known limits

* It drives the WiFi restart through a stub, so it cannot judge whether the *real* restart
  works on a device. It does check that the verdict is honest when the driver ignores the
  write (C1).
* It tests the CLI, not the app. The app's pure logic is covered by
  [`tools/stubcompile`](../stubcompile/README.md) (compilation) and `tools/checks` (claims
  and hazards); the app's own behaviour needs a device.
* The scenarios are synthetic MTK images (a 4-byte header + a 6-byte MAC in a zero-filled
  file). They model the layout the project documents; they are not a dump of a real
  partition.
* `cli/macchanger.sh` restarts WiFi with a bounded poll, not a fixed sleep: `reinit_wifi`
  reads the driver's state through `wifi_state()` in a loop bounded by `WIFI_POLL_TRIES`
  (`cli/macchanger.sh`, in `reinit_wifi`), and the only sleeps are the 0.5 s poll intervals.
  This suite drives that path through the stub `svc`, so it checks the CLI's verdicts and the
  exit codes it documents, and what the stub cannot judge is whether the real driver reloaded.
  `tools/checks/checks.sh` check 6 reports the same thing and `PASS`es ("a bounded poll loop
  is present"); it does not report a FAIL. An earlier revision of this bullet claimed a fixed
  `sleep 2`/`sleep 6` and a `checks.sh` FAIL — both were stale against this tree.
