# Safety, backups and recovery

This software runs as root and rewrites a calibration file that Android will not
regenerate. The rules below are the whole of the safety model; the code enforces
them, and it refuses to proceed rather than guess.

If you have not yet done anything with this tool, read this file first, not last.
The short version is in [../README.md](../README.md).

## What persists, and what does not

**Understand what you are doing before you do it.** This is not the same thing as
Android's "randomized MAC" privacy setting, and it is not reversible by any
normal Android mechanism:

* The change is written into the vendor calibration file, so it **persists across
  reboots** and, on the vendor-partition paths (MediaTek `nvdata`, Qualcomm
  `persist`, Samsung `efs`, Unisoc `productinfo`), it **survives a factory
  reset**: a factory reset does not restore the factory MAC, and neither does the
  per-network Privacy setting. The two candidate paths that live under `/data`
  (MediaTek's older `/data/nvram/...`, Qualcomm's `/data/vendor/...`) are the
  exception: a data wipe takes those with it.
* It is **invisible to Android's per-network MAC randomization setting**: the two
  mechanisms are independent, and the app warns you when a saved network is
  randomizing on top of your change.
* **Only this app or this CLI can undo it** in the normal case. The way back is
  the saved factory record: press **Restore factory** in the app, or run
  `sh cli/macchanger.sh restore`, both of which write the recorded factory image
  back in place and verify the result. If that record is gone, you need the
  factory MAC from somewhere else, and the two front ends differ: the app takes it
  **typed** into the FACTORY RECORD card's field (see
  [If the backup is gone](#if-the-backup-is-gone)), while the CLI has no typed
  record at all — `sh cli/macchanger.sh set MAC` still captures the factory image
  first, and on a device whose live MAC is already locally administered (the state
  `random` itself creates) that capture is **refused**: `set` exits `6` and writes
  nothing until you add `--assume-factory`, which records the current spoof as the
  "factory" value. Re-flashing the vendor partition is the alternative that costs
  nothing.
* If you change the MAC to a value that is not yours, you are impersonating a
  device on that network. Doing so to **evade per-device billing, a MAC allow/deny
  list, a captive-portal limit, or a block imposed by an operator who does not own
  the device** is misuse, and in many jurisdictions it is a computer-misuse
  offence. The CLI's own warning — *"your whitelisted WiFi will drop unless this
  MAC is whitelisted"* — is the practical version of the same point: a MAC is a
  credential.

## What it refuses to do

* It never `mv`s or `cp`s a rewritten file over the NVRAM path — keeping the inode
  is what keeps the SELinux label and the file length intact.
* It never hardcodes an offset, and it never writes unless the bytes at the
  matched offset are the MAC the driver is actually using.
* It never writes before the factory image has been copied **and verified** into
  app-private storage; if that copy cannot be made, the write does not happen
  ("backup failed — NVRAM untouched"). Draining that copy into
  `/data/adb/macchanger/` is attempted afterwards and is **best-effort**: a device
  where that push fails (no `/data/adb` directory, which is a normal
  Magisk-less-root situation) still gets the write, on the app-private copy alone.
* **No timeout may ever truncate a calibration file.** An earlier version left the
  write command unwatched for exactly that reason. Now that the write is a
  size-bounded, in-place `dd conv=notrunc,fsync` that cannot shorten the file, it
  runs under its own deliberately wide deadline (`MS_WRITE`, 120 s) so that only a
  *hung* shell, never a slow one, is cut off — and a partial result is refused by
  the size and content gates instead of being reported as success. Truncating a
  calibration partition is the worst outcome in this project, worse than a hung
  operation.
* It never sets an all-zero, broadcast or multicast MAC.
* It never runs the runtime `ip-link` fallback unless you have explicitly enabled
  it.

## Where the record lives

Both front ends in this source tree keep the record in **`/data/adb/macchanger/`**
(directory mode 700), and the app additionally keeps a copy in its private
directory. Which of the two is *load-bearing* differs between them, so read the
paragraph after the listing. The shipped `versionCode 1` APK kept the record
*only* in app-private storage, which is why it was destroyed by an uninstall — see
[../CHANGELOG.md](../CHANGELOG.md). The durable location survives
`pm uninstall com.macchanger` and "Clear data", because the app's private
directory does not.

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
character for character. An offset suffix appended to it makes every recorded image
look as though it came from a **foreign** calibration file: `panic` then refuses
outright (`die "$EX_NOBACKUP" … proves nothing about this file. Refusing.`, unless
you pass `--allow-foreign`), `restore` warns that the image came from somewhere
else, and `doctor` prints `[NOT <path> - a foreign image, refused by 'panic']` next
to a record you were told to write that way.

Which copy guards the write: the **CLI** captures `WIFI.factory` and its sidecars
into `/data/adb/macchanger/` *before* it writes, and refuses to write without them
— there the durable image is the precondition. The **app** writes its app-private
copy first, checks that one's length against the image it just read, aborts the
write if *that* fails, and only then tries the `/data/adb` copy. That push is not a
gate and not a promise: it can fail (no `/data/adb` directory, no root for that
path) and the write proceeds anyway, on a copy that `pm uninstall com.macchanger`
or "Clear data" deletes. So after any write, open the recovery card and look at its
`durable` line — it reports whether `/data/adb` really holds the pre-image — and if
it does not, **export the record before you uninstall anything.**

The app additionally keeps a **fallback mirror** for devices where `/data/adb`
does not exist:

```
/data/data/com.macchanger/files/nvram_backup/<mangled path>       the image
/data/data/com.macchanger/files/nvram_backup/<mangled path>.path  the path tag
/data/data/com.macchanger/files/factory.txt                       MAC + provenance
```

**This mirror is deleted by `pm uninstall com.macchanger` and by "Clear data".**
It is a fallback, not the record. Press **Export record** in the app, or copy both
directories off the device, before you uninstall, wipe or replace anything.

**And keep a copy off the device.** `/data/adb/macchanger/` and the export
destination `/sdcard/Download` both live on the data partition: a **factory reset
or a data wipe erases the record too**, while the MAC change itself survives on a
vendor partition. That combination — a changed MAC with no record of the original
— is the worst state this tool can leave you in, and it is why the record is worth
copying to a PC or an SD card.

## If the backup is gone

You can still restore a *known* factory MAC — that is the whole reason the typed
entry exists:

1. Find the factory MAC. Look at the device's box, its label or its SIM tray —
   *some* devices print the WiFi MAC there (it may be labelled WLAN/WiFi MAC); not
   all of them do. Otherwise: a note you took before the first change, any exported
   record, or a full dump / custom-recovery backup of the vendor partition.
   `/sys/class/net/<iface>/address` will **not** help — it shows the current,
   possibly spoofed value. Nothing can recover the factory value from the device
   once it has been overwritten and the record is gone; that is why the backup is a
   precondition for writing.
2. App: type it into the field in the **FACTORY RECORD** card and press **Save
   factory**. It is recorded as `typed, unverified` and *Restore factory* will
   write it — that needs no image at all, so this path works on a device whose
   record is gone.
3. CLI: `sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF` **is not the same path, and
   on this device state it is refused.** The CLI has no typed record: `set`
   captures the factory image before it writes anything, and the capture is refused
   (exit `6`, nothing written) when there is no image and the live MAC is locally
   administered — the state `sh cli/macchanger.sh random` leaves behind, and the
   state you are in whenever this section applies.

   **`--assume-factory` is the last resort, not the only way through.** If you can
   obtain a **known-good full-size image** of the calibration file — the
   dump/reflash route in step 4, a copy you kept off the device, or the vendor
   partition of an identical model — put it at `/data/adb/macchanger/WIFI.factory`
   together with its sidecars (`WIFI.factory.offset`, `WIFI.factory.path`,
   `WIFI.factory.sha256`) and `set` proceeds with **no flag at all**, keeping a real
   factory record. That is what the CLI's own refusal message tells you to do (`… or
   put a known-good factory image back at $BAK first (a copy you kept off the
   device), then run '$0 set MAC'`), and it is verified: `ensure_backup()` returns
   success immediately when `$BAK` exists and its size relation and digest both
   pass, so the capture decision that refuses `--assume-factory` is never reached.
   Reconstructed in a sandbox with an LA live MAC and a 64-byte known-good image at
   `$BAK`:

   ```
   $ sh cli/macchanger.sh set 02:11:22:33:44:55     # no --assume-factory
   before:  01 00 08 00 aa bb cc dd ee ff
   after :  01 00 08 00 02 11 22 33 44 55           # written
   ```

   The exit status of that run is `0` when the driver picks the value up and `9`
   when it does not (written and verified, not in effect — `set` reports which).
   Either way the bytes above are the evidence, and no flag was needed: the refusal
   you were reading was the *capture*, and with an image in place there is no
   capture to refuse.

   `--assume-factory` is for when you have **no** such image and are willing to
   trade the record for the write:

   ```bash
   sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF --assume-factory
   ```

   **Read what that costs before you run it.** `--assume-factory` records the file
   as it is *now* — holding the spoof — as the "factory" image, so the record it
   keeps is of the address you are trying to get rid of, and a later
   `sh cli/macchanger.sh restore` puts that spoof back. It is a one-way trade: you
   get the factory MAC into the calibration file and lose the restorable record. If
   the value matters more than the record, do it; if the record matters more, use
   the app's typed path in step 2, which keeps the factory MAC as the restore
   target, a known-good image as above, or re-flash the vendor partition in step 4.
4. If you do not know the factory MAC either, the remaining options are a
   dump/reflash of the vendor partition for your exact model, or the vendor's
   service tool. Do not invent a value: the file holds calibration data around the
   MAC field, and a bad write there can affect WiFi calibration — the reason this
   project refuses to write blind in the first place.

If the calibration file itself was damaged (zeroed, or truncated), no app can
repair it from nothing: restore it from a dump of the same model, or reflash that
partition.

The commands themselves, with every option and exit code:
[CLI.md](CLI.md). The app's record rows: [APP.md](APP.md).

## If WiFi stops working entirely

1. **Reboot first.** If the runtime `ip link` fallback caused it, a reboot is the
   fix — that change is not persistent. Then make sure the fallback checkbox is
   off.
2. From a root shell, try `svc wifi enable` (or `sh cli/macchanger.sh wifi`).
3. Restore the factory image: **Restore factory** in the app, or
   `sh cli/macchanger.sh restore`. Both are in-place and length-checked; a restore
   that cannot verify itself tells you so instead of claiming success.
4. If the CLI refuses because there is no backup, use the app's typed-value path
   above (**FACTORY RECORD** → type the MAC → **Save factory**) — the CLI will
   not accept a typed value without `--assume-factory`, and that flag trades the
   record for the write.
   Refusing to write is the intended behaviour, not a malfunction.
5. If the file's size changed (the tool would have refused, but a third-party tool
   may not have), reflash the vendor partition from a dump.

## Keep this in mind

The failure this project takes most seriously is not a MAC that does not change:
it is a calibration partition that gets truncated or zeroed, because that is the
one outcome no message, retry or reboot can undo. Everything above — the size
checks, the scan precondition, the in-place write, the verified backup, the refusal
to write when unsure — exists for that single reason.
