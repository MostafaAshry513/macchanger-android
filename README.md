# MacChanger — persistent WiFi MAC changer (rooted Android)
Your phone stores its factory WiFi MAC — the hardware address the network sees — in a vendor file that Android will not
regenerate. This tool copies that file, replaces the MAC in it as root, and restarts WiFi so the driver re-reads it. The
change lives in that file, not in Android's settings, so it survives reboots, and on the tested phone a factory reset too.

Two ways in: a **MediaTek only** command-line script for a root shell, and an **app you build on the phone** (no PC), which
probes Qualcomm, Samsung and Unisoc locations as well.

> **Do not install the APK in `prebuilt/` unless you know what it is.** It is the **pre-fix** build, compiled before the audit
> in `AUDIT.md`: it writes at a hardcoded offset instead of locating the MAC, can truncate the calibration file when it reads
> a short image, accepts a multicast address, and reports success it has not verified. It is kept only as the artifact this
> project shipped, with its hash recorded so you can recognise it. **Build your own instead** — on the phone, no PC, no SDK;
> the commands are under [The app](#the-app-build-it-install-it-use-it) below. Anything here about safety describes the
> source in this tree, not that binary.

**One device has ever been tested:** Infinix SMART 5 (MediaTek MT6761), Android 11, Magisk. Everything else is untested —
[docs/DEVICES.md](docs/DEVICES.md).

## What this does to your phone
The honest 30-second version. Do not skip it.

* It **edits the calibration file as root** — on the tested phone `/mnt/vendor/nvdata/APCFG/APRDEB/WIFI`. It writes **in
place**: same file, same permissions, and only the MAC bytes change — six of them if the file stores the MAC raw, more if it
stores it as text. It never copies a rewritten file over the original.
* The change **persists across reboots**. On the vendor-partition paths — MediaTek `/mnt/vendor/nvdata/...`, Qualcomm
`/mnt/vendor/persist/...`, Samsung `/efs/...`, Unisoc `/productinfo/...` — it also **survives a factory reset**: neither a
factory reset nor Android's per-network Privacy setting restores the factory MAC. The two `/data` paths (MediaTek's older
`/data/nvram/...`, Qualcomm's `/data/vendor/...`) are the exception: a data wipe takes those with it.
* **Keep the backup, and keep a copy off the phone.** The backup is an exact byte-for-byte copy of the file — "the factory
image". The CLI writes it to `/data/adb/macchanger/WIFI.factory`, with three companions it needs, `WIFI.factory.path`,
`WIFI.factory.offset` and `WIFI.factory.sha256`, so copy all four together. **In a root shell, after `backup`:**

  ```bash
  mkdir -p /sdcard/macchanger-backup && cp /data/adb/macchanger/WIFI.factory* /sdcard/macchanger-backup/
  ```

  Then send that copy to a PC or the cloud, because `/sdcard` is internal storage: the same factory reset you are protecting
  against wipes it. Two more ways to lose the record: if `/data/adb` is missing, the app can keep its copy only inside the
  app, and uninstalling the app deletes it (the app's `durable` line says which case you are in); and a data wipe takes both
  copies at once. After that nothing on the phone can tell you what the original MAC was. To put the record back —
  `cp /sdcard/macchanger-backup/WIFI.factory* /data/adb/macchanger/` in a root shell, then `restore`.
  [docs/SAFETY.md](docs/SAFETY.md#where-the-record-lives) has the way back.
* **Only this tool can undo it** normally: **Restore factory** in the app, or `sh cli/macchanger.sh restore`.
* There is a runtime `ip link` fallback for phones where no calibration file matches. It is off by default, the CLI never runs
it, on MediaTek it **crashes the WiFi service**, and it is **non-persistent — it does not survive a reboot**. Do not use it as
your way of changing a MAC.
* The app declares zero <uses-permission> elements: zero Android permissions, nothing to grant at install time; everything
goes through the root (`su`) channel you granted it. Deliberate, and not to be traded away.
* No scanning, no deauthentication, no packet injection, no cloning of MACs seen on the air, and no attack on anything except
the WiFi adapter of the phone it runs on.
* On **Android 12 and newer** the app can misread whether a network is randomizing its MAC — do not trust that one verdict
there. For the address the driver really uses, read `show` (app or CLI), or run `su -c 'cat /sys/class/net/wlan0/address'`,
which prints just the address, like `02:11:22:33:44:55`. Android Settings always shows the per-network randomized address,
a different number.
* The one outcome nothing here can undo is a calibration file truncated or zeroed: no message, retry or reboot repairs that,
and everything here exists to avoid it.

## Before you start
Every line must be true for your phone. If one is not, stop here.

* [ ] **Root**, Magisk-style (`su`), and ideally **`/data/adb`**, where the durable record lives. Check `su -c 'id; ls -d
/data/adb /data/adb/magisk/busybox'`: you need `uid=0(root)` and both paths listed.
* [ ] **busybox** for the CLI — a single binary supplying the small tools it needs (`dd`, `hexdump`, `awk`, `cmp`,
`sha256sum`) — at the `/data/adb/magisk/busybox` path that check proves; Magisk ships one. Without it the CLI is closed to
you. The app does not need busybox, but without `/data/adb` its only record is the copy an uninstall deletes.
* [ ] **A MediaTek phone, for the CLI.** It writes one file, `/mnt/vendor/nvdata/APCFG/APRDEB/WIFI` — check it with `ls -l`.
If it is absent, the CLI cannot write on your phone (another vendor, or a different MediaTek layout): use the app.
* [ ] **Termux** (a terminal app), if you want the app: built on the phone, no PC, no Android SDK, and it needs a signing key
you supply.
* [ ] You accept that this rewrites a calibration partition as root, and that a bad write there is not fixed by a reboot or a
factory reset.

## Quick start: change the MAC (CLI)
Replace `02:11:22:33:44:55` with the address you want (see [Choosing a MAC value](#choosing-a-mac-value)). The output below is
real, from a synthetic test device; `NVRAM` is the CLI's name for the calibration file.

**1 · Once, in Termux** (no root yet).

```bash
pkg install git        # Termux ships no git
# the maintained copy; if you got this tree elsewhere, clone that URL instead
git clone https://github.com/MostafaAshry513/macchanger-android MacChanger
cd MacChanger && pwd   # note this path: step 2 needs it
```

**2 · In the root shell.** `su` asks for root, and it can start in `/`, where the relative paths below would fail — so `cd`
back to the path `pwd` printed. `su` is a shell on this phone, not a network connection, so the WiFi restart in the last
command does not close it.

```bash
su
cd /data/data/com.termux/files/home/MacChanger          # your path from step 1
sh cli/macchanger.sh doctor                             # read-only report: run this first
sh cli/macchanger.sh backup                             # save the factory image. Do not skip.
sh cli/macchanger.sh set 02:11:22:33:44:55 --dry-run    # prints what would change, writes nothing
sh cli/macchanger.sh set 02:11:22:33:44:55              # writes in place; WiFi restarts, you drop off briefly
sh cli/macchanger.sh show                               # what the file holds / the driver uses
echo $?                                                 # 0 = success. Run it right after a command.
exit
```

**3 · What the commands print**, so you can tell success from a refusal. This is real output from a 64-byte synthetic file,
trimmed to the lines that matter.

`doctor` on your **first** run, before any `backup` — `verdict : no` and a `blocker:` is correct here: there is no factory
image yet. It is not a fault, and nothing has been written.

```
supported      : yes - MTK NVRAM path present
factory image  : (none at /data/adb/macchanger/WIFI.factory - run 'cli/macchanger.sh backup' while the NVRAM holds the MAC you want to keep)
verdict        : no
blocker        : no factory image at /data/adb/macchanger/WIFI.factory
```

`backup` — this is your way back, so check that it says `saved factory image`:

```
[*] interface wlan0, runtime MAC 04:f9:93:11:36:bf
[*] capturing factory image of /mnt/vendor/nvdata/APCFG/APRDEB/WIFI (MAC field at offset 4 = 04:f9:93:11:36:bf)
[*] saved factory image -> /data/adb/macchanger/WIFI.factory (offset 4, sha256 6b6a67e7…a26e)
[*] 'cli/macchanger.sh restore' writes this image back in place.
```

`doctor` **after** `backup` — `verdict : yes` appears only when root, busybox, the interface, the calibration path **and** a
verified factory image are all in place. The name it quotes is however you invoked the script.

```
verdict        : yes - root, usable busybox, a resolved interface, an NVRAM path and a verified factory image 'cli/macchanger.sh restore' can write back
```

`set … --dry-run` — **read this before the real write.** It names the file, the offset, and the exact bytes that would change,
and writes nothing. `offset` is simply where in the file the MAC starts.

```
[*] dry run: nothing was written
    file      : /mnt/vendor/nvdata/APCFG/APRDEB/WIFI (64 bytes)
    new MAC   : 02:11:22:33:44:55
    offset    : 4
      before  : 01 00 08 00 04 f9 93 11 36 bf 00 00 00 00 00 00 
      after   : 01 00 08 00 02 11 22 33 44 55 00 00 00 00 00 00 
      changes : 4..9 of /mnt/vendor/nvdata/APCFG/APRDEB/WIFI (6 bytes, of which 6 are the MAC)
      factory : kept (/data/adb/macchanger/WIFI.factory, digest ok)
```

`set` for real — WiFi comes back on the new address:

```
[*] writing NVRAM (/mnt/vendor/nvdata/APCFG/APRDEB/WIFI) at byte offset(s) 4 ...
[*] reinitialising WiFi (this briefly disconnects)...
[+] nvram   MAC : 02:11:22:33:44:55  (offset 4)
[+] runtime MAC : 02:11:22:33:44:55
```

`show` — `factory (image)` is the address you can go back to; `nvram` and `runtime` are what the file and the driver hold now:

```
factory (image): 04:f9:93:11:36:bf  [offset 4]
nvram          : 02:11:22:33:44:55  [offset 4]
runtime        : 02:11:22:33:44:55
```

A refusal prints no `[+]` lines and changes nothing — look for `error:`, `blocker :` or a non-zero exit. If `supported` says
the path "is missing (another vendor, or a different layout)", this CLI is not for your phone: use the app, and read
[docs/DEVICES.md](docs/DEVICES.md).
* Exit `0` is success; anything else is a refusal or a failure, and `echo $?` must be run immediately after the command,
before typing anything else. Every code and option is in [docs/CLI.md](docs/CLI.md). **To put the factory MAC back:**

```bash
sh cli/macchanger.sh restore   # writes the saved factory image back, in place
sh cli/macchanger.sh panic     # the same, for a file that is corrupt, unreadable or truncated
```

`restore` refuses without a factory image, so run `backup` first. `panic` does not invent a MAC: it puts the saved image back
even when the calibration file is damaged — the one case `restore` refuses.

## Choosing a MAC value
* **Simplest safe choice:** run `sh cli/macchanger.sh random`, which generates one for you. Otherwise copy
`02:11:22:33:44:55` and change only the last two pairs — keeping the first pair `02` keeps it valid.
* A MAC is six hex pairs, written `02:11:22:33:44:55`. The tool refuses an all-zero address, a broadcast address
(`ff:ff:ff:ff:ff:ff`) and any multicast address — first pair odd, like `01:...` — and writes nothing.
* A first pair of `02`, `06`, `0a`, `0e`, `12` … marks a **locally administered** address, which is what a spoof should be.
A *globally* administered value (`04:...`) is a real vendor's address and impersonates their device. Do not reuse an address
another device already uses on that network: the router sees the conflict and both devices lose connectivity.

## The app: build it, install it, use it
**Build** in Termux. `KS` is the file that holds your signing key (a keystore) and `KS_PASS` its password — replace
`choose-a-password` with your own, and reuse both for every later build, or updates to your own app will be rejected.
`--new-key` creates the key on the first build.

```bash
pkg install openjdk-21 aapt apksigner d8 zipalign coreutils
cd ~/MacChanger/app
KS=~/ks.p12 KS_PASS='choose-a-password' ./build.sh --new-key   # first build: creates the key
KS=~/ks.p12 KS_PASS='choose-a-password' ./build.sh             # every build after that
```

It ends with `BUILT: /…/MacChanger/app/app-signed.apk`; anything else means it did not build. With `KS` unset it prints
`BUILD REFUSED - nothing was compiled, packaged or signed.` and exits `1`: it will not invent a key, and an APK signed by a
different key cannot replace an installed one.

**Install** — tap the APK in a file manager, or from the phone. It prints `Success` when it worked. The first launch asks for
root once: approve the Magisk prompt, the app's only privilege.

```bash
su -c 'pm install /data/data/com.termux/files/home/MacChanger/app/app-signed.apk'
```

**Use.** `FACTORY RECORD` card: **Save factory** does **not** read your phone — it stores an address you type in, so use it
only if you already know the factory MAC (from the router's client list, or from `sh cli/macchanger.sh show` run *before* your
first change). Type a spoofed value and **Restore factory** will faithfully put the spoof back. Prefer
`sh cli/macchanger.sh backup`, which copies the real file and needs nothing typed. **Export record** copies the record files
to `/sdcard/Download`. `SET MAC` card: **Set MAC** applies what you typed, **Random** picks one, **Restore factory** writes
the saved image back, and the checkbox **Runtime ip-link fallback (not persistent)** — leave it off — sits under it.
`RECOVERY` card: its `durable` line says whether `/data/adb` holds your backup. Every row and button, and why
`targetSdkVersion 30` (the Android version it declares) is deliberate: [docs/APP.md](docs/APP.md).

## The prebuilt APK, if you have one
Check the file before you run it — on a PC, or in Termux after `pkg install openjdk-21 aapt apksigner`, never on the phone
you install it on:

```bash
sha256sum prebuilt/MacChanger.apk
# expected: 1760a8cb0d78ba68c83eed14214f3ee0c77d5144cdcf2c494025d7407937a6da
```

Anything other than that hash means the file is **not** the one this project shipped — do not install it. A matching hash
means it *is* the known-bad pre-fix build from the top of this page, so do not install it either: the hash only identifies
the file. The `keytool`, `aapt` and `apksigner` checks, the certificate record, and why no v2/v3 signature can be relied on
at all on Android 5.0–6.0 (API 21–23) are in [docs/APP.md](docs/APP.md).

* **The two signing identities cannot replace each other.** The prebuilt was signed with a debug keystore that shipped with
its password published — **treat it as compromised**; a matching fingerprint proves which key signed a file, not that this
project produced it, so do not pin it. Your build is signed with **your** keystore, and `pm install -r` between the two fails
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
* The way out is `pm uninstall com.macchanger`, and **that deletes the app's private data**, including its copy of the factory
image — the same bytes as `/data/adb/macchanger/WIFI.factory`. Press **Export record**, or copy that record off, before you
uninstall anything.

## If something goes wrong
* **Reboot first**: if the runtime `ip link` fallback caused it, a reboot is the fix, because that change is not persistent.
Then untick **Runtime ip-link fallback (not persistent)** in the app's `SET MAC` card.
* From a root shell try `svc wifi enable` (or `sh cli/macchanger.sh wifi`), then put the factory image back: **Restore
factory** in the app, or `sh cli/macchanger.sh restore`; if that refuses, `sh cli/macchanger.sh panic`. If the app will not
start, the CLI still works: `/data/adb/macchanger/` is what `restore` reads.
* If the record is gone, if WiFi is still dead, or if the calibration file was damaged, [docs/SAFETY.md](docs/SAFETY.md) has
the commands and says what is still possible without a backup. Refusing to write is intended behaviour, not a malfunction.

## Where to read more
| File | The question it answers |
| --- | --- |
| [docs/SAFETY.md](docs/SAFETY.md), [docs/CLI.md](docs/CLI.md) | What is risky, what survives a wipe, how to recover — and every command, option and exit code. |
| [docs/APP.md](docs/APP.md), [docs/DEVICES.md](docs/DEVICES.md) | The app's rows and build, and why `targetSdkVersion 30` stays 30; supported phones and per-vendor risk. |
| [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md), [docs/DEVELOPING.md](docs/DEVELOPING.md) | The write path, the lock and randomization detection; the repository layout and the offline gates `tools/stubcompile/`, `tools/clitest/`, `tools/checks/`, `tools/package.sh`. |
| [SECURITY.md](SECURITY.md), [CHANGELOG.md](CHANGELOG.md) | Reporting a problem, scope, the shipped signing key, every published APK by hash. |
| [VERIFICATION.md](VERIFICATION.md), [AUDIT.md](AUDIT.md), [tools/README.md](tools/README.md) | What was verified and what was not, the audit, and what the gates prove. |

## Intended use and licence
For **phones you own** and **networks you are authorized to use**: privacy on your own network and on networks without MAC
filtering; repairing a corrupted, zeroed or wrongly flashed factory MAC, where the radio comes up with an address the AP or
driver rejects; replacing a MAC burned into a captive-portal or asset database you control; and testing your own access
point's allow/deny lists. Changing the MAC to a value that is not yours to evade per-device billing, a block, an allow/deny
list or a portal limit is misuse, and in many jurisdictions a computer-misuse offence: a MAC is a credential.

MIT licence, no warranty — see `LICENSE` and [LICENSE-NOTES.md](LICENSE-NOTES.md). Security reports and scope:
[SECURITY.md](SECURITY.md).
