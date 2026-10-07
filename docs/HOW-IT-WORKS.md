# How it works

Short version: find the vendor file that holds the factory WiFi MAC, locate the MAC
inside it, back the file up, change those bytes in place, restart WiFi so the driver
re-reads it, then verify what was written.

## What it does

* Scans the vendor file for the WiFi MAC the driver is **currently** using.
  **The app** tries four encodings — the six raw bytes, those bytes reversed, and
  two **lowercase** ASCII spellings (colon-separated `aa:bb:cc:dd:ee:ff` and plain
  `aabbccddeeff`) — and it rewrites every located window. Uppercase ASCII
  (`AA:BB:CC:DD:EE:FF`) is **not** searched: the encodings are built with
  `String.format("%02x", …)`, so a file holding only an uppercase copy is reported
  as not containing the live MAC, which is true of that file in the encodings this
  code knows. **The CLI** is narrower: it searches the six raw bytes only
  (`scan_mac()` matches one hex pattern), so on a file that stores the MAC as ASCII
  text the app can change the value and the CLI reports the same file as not holding
  it (exit `6`, nothing written). That is a safe refusal, not a wrong write, but it
  is a real app/CLI difference — do not read "both scan for the MAC" as "both find
  it in the same files". Whatever is located, the tool backs the file up, replaces
  those bytes, and restarts WiFi so the driver re-reads the file.
* Detects the SoC vendor and probes **candidate paths for several vendors**
  (MediaTek, Qualcomm, Samsung, Unisoc) — see the table in
  [DEVICES.md](DEVICES.md#supported-devices-and-per-vendor-risk). Only MediaTek has
  ever been tested.
* Refuses to write when it cannot be sure: the file must exist, the runtime MAC must
  actually be found inside it, and the position and length must be verifiable.
  Otherwise it writes nothing, and it says *which* case it hit — none of the
  candidate files exists (`UNSUPPORTED DEVICE`), a file exists but does not carry
  the driver's MAC (`no MAC match`), or a file exists that root could not read —
  instead of pretending it changed something. Those are three different verdicts
  with different follow-ups, and only the first one also suppresses the WiFi restart
  and the runtime fallback; all three are spelled out under
  [Supported devices and per-vendor risk](DEVICES.md#supported-devices-and-per-vendor-risk).
* Copies the calibration file into **app-private storage before the first write, and
  aborts the write if that copy cannot be made and verified** — it is re-read and
  its length checked against the image that was just read out of the partition. It
  *then* also tries to copy that image into `/data/adb/macchanger/`, and that push
  is **best-effort: its result is not a gate.** On a device with no `/data/adb`
  directory the write therefore still proceeds, with only the copy an uninstall or a
  "Clear data" destroys. Read
  [Where the record lives](SAFETY.md#where-the-record-lives): the app-private copy is
  what guards the write, and the `/data/adb` copy is the one that survives an
  uninstall. (An earlier version ignored even the app-private copy's failure and
  patched the file anyway; the old claim "the app always copies the original NVRAM
  before writing" was false on that path. The private copy is a gate now.)
* Verifies what it wrote by re-reading the patched region and comparing it with an
  image computed from the pre-write bytes, and tells you plainly when the driver did
  not pick the value up.

There is also a **runtime `ip link` fallback** for devices where no NVRAM file
matches. It is a last resort, it is **off by default** in the app, and the CLI never
runs it at all. The reason is the same sentence the CLI carries in its source: on
MediaTek, touching `ip link` this way **crashes the WiFi service**. It is also
**non-persistent — it does not survive a reboot**, so treat it as a temporary
experiment on a device whose calibration file you have already backed up, never as a
way to change a MAC.

## Locating the MAC field

Nothing in this project assumes a size, a header or an offset. Both front ends scan
the candidate file for the bytes of the MAC the driver is **currently** using, and
both refuse to write unless the bytes at the matched offset are exactly that MAC.
Where they scan, and in which encodings, is the difference described in
[What it does](#what-it-does) above.

Two refusals follow from that rule and are worth recognising when you read a
verdict:

* a file that does not contain the live MAC is `no MAC match`, never a write;
* a MAC that occurs **more than 8 times** is refused rather than guessed at: the CLI
  reports the count and exits `6` (`… occurs N times in …; too ambiguous to patch
  safely`), and the app abandons the file whole (`unexpected layout — N matches in …,
  refusing`), with no backup and no write.
* **Between one and eight occurrences, both front ends rewrite every one of them.**
  The CLI writes at each offset its scan found, and the app rewrites every located
  window across its four encodings up to its own cap of 8 (`PATCH_MAX_HITS`). A MAC
  stored twice in a file is therefore patched in both places, not refused — do not
  read "ambiguous match" as "safe no-write". The two caps are counts of *matches*,
  and the app's count is the sum over the encodings it searched, so two raw windows
  plus an ASCII copy is three of its eight.

The tested device's layout, and why the file is written in place rather than
replaced, are in [DEVICES.md](DEVICES.md#the-nvram-file-on-the-tested-device).

## The two write paths

What each front end installs once the field is located is **not** the same thing,
and the difference matters if you are judging how much of the file a bug can reach:

* the **CLI** writes only the located 6-byte MAC field, in place:
  `cli/macchanger.sh`, `write_mac()`, `dd of="$_file" bs=1 seek="$_o" count=6
  conv=notrunc,fsync` — the only `dd` in that file with both an `of=` and a
  `count=6`;
* the **app** installs a full-size copy of the *whole* file, with only the located
  MAC window changed. `setMac` reads the file with `readRoot` and clones every byte
  of it (`byte[] patched = data.clone();`), patches that clone in memory, and hands
  the clone to `writeRoot`, which stages exactly that full-size image and installs it
  by running `dd` over the whole file:
  `dd if="<staged image>" of="<calibration path>" bs=4096 conv=notrunc,fsync`
  inside the `WRITE_SCRIPT` constant
  (`app/src/com/macchanger/MainActivity.java`). Grep `WRITE_SCRIPT`, `writeRoot`,
  `readRoot` and `data.clone()` — those four names are the whole of the difference.

The app's whole-file install is guarded rather than blind. Inside that one root
invocation `WRITE_SCRIPT` reads the staged image's size and the destination's size,
refuses unless they are equal and non-empty, checks that the destination is still
the file the patch was derived from, writes, re-reads the destination size and
refuses if it changed, and finally requires `cmp` to find the image in place before
it prints `wf=ok`. Before any of that, `writeRoot` refuses a staged buffer shorter
than `MIN_IMAGE`. So the size authority is the destination's own size, not the
image's, and a short image can never shorten a calibration file. Earlier revisions of
this tool did rewrite whole files without that authority, which is the `C1` defect
recorded in `AUDIT.md:27`.

## Verification, and how a write can fail honestly

* Every write is verified by re-reading the patched region and comparing it with an
  image computed from the pre-write bytes; the CLI verifies a whole window around
  the offset, so verification cannot confirm a write that landed elsewhere.
* The file's size is compared before and after. A calibration file that got shorter
  is treated as unrecoverable, not as a warning.
* A write that is verified but not in effect is its own outcome, not a success:
  `set`/`random` exit `9` when the driver is not using the MAC. `restore` and `panic`
  report the verified state of the *file* instead, so a driver that has not reloaded
  the NVRAM yet is a warning there, never a false failure.
* No timeout may truncate the file: the write is a size-bounded, in-place
  `dd conv=notrunc,fsync`, run under a deliberately wide deadline (120 s) so that a
  *hung* shell, never a slow one, is cut off. The list of things the code refuses to
  do is in [SAFETY.md](SAFETY.md#what-it-refuses-to-do).

## The lock: one writer at a time

Both front ends take the **same** cross-process lock —
`/data/adb/macchanger/lock`, created with `mkdir`, whose holder writes a timestamp
and an owner token — before they touch the calibration file, so the app and the CLI
cannot rewrite it at the same time. A lock whose stamp is older than 300 s
(`LOCK_STALE_S`) is treated as abandoned and broken in, so a killed process does not
lock the device forever; that is also why a stale CLI run can be refused, with exit
`6`, until the window passes.

Two limits are recorded in the code rather than hidden: the CLI does not renew its
stamp while it works, so a CLI operation that outlived the 300 s window could be
broken as abandoned while it is still writing, whereas the app renews its stamp and
does not have that exposure. And `--dry-run` writes nothing, so it takes no lock.

## Randomization detection: what it can and cannot see

The app reads the per-network **MAC randomization** setting
(`MacRandomizationSetting` in the framework's `WifiConfigStore.xml`) for the network
you are connected to, and labels it with the app's own strings (`privacyLabel`):

* `device MAC` — the stored value is `0`: this network uses the hardware/NVRAM MAC,
  so your change is what the router sees;
* `randomized · persistent · tap to fix` (`1`), `randomized · non-persistent · tap to
  fix` (`2`) and `auto (framework decides) · tap to fix` (`3`) — all three can
  override the hardware MAC, so the router may not see your change; set Privacy to
  *Use device MAC*;
* `unknown` — the stored value is anything else, or nothing. **`unknown` is not a
  diagnosis.** It does not mean randomized, and it does not mean device MAC.

The same row carries the count over every **saved** network — `randomized on N of M
saved networks` — because the setting is per network. A spoof that works on the
network you are on can stop working the moment the phone joins another SSID whose
Privacy randomizes, and that count is what warns you; the label alone describes only
the connected one.

The Android 12+ limitation is real and worth stating plainly:

* Detection needs the **name (SSID) of the network you are connected to**. With no
  connection there is no SSID, so there is nothing to look up and the row reads
  `unknown`.
* The SSID comes from `cmd wifi status` run as root. On **Android 12 and newer** the
  platform's status output changed: it now prints extra lines before the
  `Wifi is connected to "…"` line for a root caller, so anything that reads only the
  first few lines of that command can never see the SSID there. An earlier version of
  this app did exactly that, and consequently explained a *failed write* as "this
  network randomizes the MAC" on Android 12+ — sending you to change a setting that
  was already correct while the real cause stayed hidden. The correct way to read
  that command is by content, not by line position — the app resolves the SSID (and
  whether it is known at all) rather than slicing the output — but if a build still
  reports a randomization problem on Android 12+ while you are connected, **do not
  trust that verdict**: confirm it in WiFi settings before you act on it, and check
  the log for a failed NVRAM write instead.
* The framework rewrites `WifiConfigStore.xml` within seconds even as root, so the
  setting cannot be forced from the app: change it in WiFi settings → (network) →
  Privacy → **Use device MAC**.

`Restore factory` still writes the factory value into the calibration file regardless
of any of this; randomization only affects what the router sees.
