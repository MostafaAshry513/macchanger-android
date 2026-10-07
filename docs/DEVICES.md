# Devices, vendors and the NVRAM file

Tested on: **Infinix SMART 5 (MediaTek MT6761), Android 11, Magisk** — one device,
one SoC family. Everything else is untested.

## Supported devices and per-vendor risk

| Vendor | Candidate paths the app probes | Status |
|---|---|---|
| **MediaTek** | `/mnt/vendor/nvdata/APCFG/APRDEB/WIFI`, `/data/nvram/APCFG/APRDEB/WIFI` | **The only tested path** (MT6761, Android 11). The CLI supports MediaTek only. |
| **Qualcomm** | `/mnt/vendor/persist/wifi/wlan_mac.bin`, `/persist/wifi/wlan_mac.bin`, `/data/vendor/wifi/wlan_mac.bin` | **Untested.** Nobody has confirmed that one of these files contains the runtime MAC in a form the scanner finds, or that the driver re-reads it. |
| **Samsung** | `/efs/wifi/.mac.info`, `/efs/wifi/mac.info` | **Untested.** `/efs` holds device-critical data on Samsung hardware; it is the highest-consequence file in this list, and on many models the content is not a raw MAC at all. |
| **Unisoc** | `/productinfo/wifi_mac`, `/mnt/vendor/productinfo/wifi_mac` | **Untested.** Nobody has inspected one. |

The CLI has no such list: it is MediaTek only, targeting
`/mnt/vendor/nvdata/APCFG/APRDEB/WIFI`, and refuses to run if that file is missing.
See [CLI.md](CLI.md).

What that means in practice on a non-MediaTek device: the app will write **only if**
one of those files exists **and** the bytes of the live runtime MAC are found inside
it. Whether the driver then uses the new value is unknown, and the honest
expectation is that it may not take, or may take and revert at the next WiFi
re-init.

When it will not write, the reason decides what else happens — the verdicts below
are not the same thing, and only one of them is an unsupported device:

* **None of the nine paths exists.** Nothing is written and nothing is attempted:
  the app says `UNSUPPORTED DEVICE — nothing was written`, lists every path it
  probed, and **does not restart WiFi and does not run the runtime fallback**, even
  if you have enabled it. That is the only case the words "unsupported device"
  describe.
* **A candidate file exists but does not carry the live MAC.** Also nothing written,
  and the verdict is `no MAC match · <mac> is in none of the candidate files`, with
  what root saw of each path. This is **not** the unsupported-device verdict, and it
  is exactly the case the runtime fallback exists for: with the fallback enabled the
  app restarts WiFi to re-read the calibration file and may then run the `ip link`
  command, with the MediaTek crash risk described in
  [HOW-IT-WORKS.md](HOW-IT-WORKS.md#what-it-does). With the fallback left off (the
  default), no restart happens here either and the log says
  `nothing was written · WiFi left alone`.
* **A candidate file exists but root cannot read it.** Nothing is written, and
  unlike the case above the app does **not** restart WiFi and does not run the
  fallback even when it is enabled: that unreadable file may be the one holding the
  MAC, so covering it with a runtime spoof is precisely what the code refuses to do.
  The verdict is `candidate NVRAM present but not usable by root · nothing was
  written`.

None of these cases is ever reported as a success, and none turns "I could not find
the file" — or "I could not find the MAC in it" — into a write.

## Other limits worth knowing before you install

* **Android versions.** Tested on Android 11 only. The APK declares
  `minSdkVersion 21` and `targetSdkVersion 30`; it installs and launches without a
  deprecation dialog on Android 14–16 (see
  [Why targetSdk is 30](APP.md#why-targetsdk-is-30-and-must-stay-30)). The code was
  written against Android 11 plus AOSP sources up to Android 16, but nothing newer
  than Android 11 has been run on real hardware by this project.
* **Root.** A Magisk-style root is assumed (`su`, and `/data/adb` for the durable
  record). The app also works without `/data/adb` by falling back to its private
  mirror, and says so when it does; the CLI requires `su` and busybox.
* **No assumed file layout.** Neither front end assumes a size, header or offset:
  both locate the MAC field by scanning for the MAC the driver is using, and both
  refuse to write on a device where that scan fails. What each front end then
  installs is not the same thing — the CLI writes the located 6-byte field in
  place, while the app installs a guarded full-size copy of the whole file. That
  difference, and the four names in the source that are the whole of it, are in
  [HOW-IT-WORKS.md](HOW-IT-WORKS.md#the-two-write-paths).

## The NVRAM file, on the tested device

```
/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
offset 0x00: 01 00 08 00              header
offset 0x04: 04 f9 93 11 36 bf        the WiFi MAC (6 bytes)
```

The offset above is what the tested device uses, but **nothing in this project
hardcodes it**: the file is scanned for the bytes of the MAC the driver is currently
using, and the write is refused unless the bytes at the matched offset equal that
MAC. The encodings differ between the front ends — **the app** searches four (raw,
byte-reversed, and the two lowercase ASCII spellings), **the CLI** only the raw six
bytes — and neither searches uppercase ASCII. See
[HOW-IT-WORKS.md](HOW-IT-WORKS.md#what-it-does) for the consequence of that
difference.

The file is opened and written in place — same inode, same SELinux label — because
replacing the file would change its SELinux label and can truncate it. Every write
is verified by re-reading the patched region, and the file's size is compared before
and after. This is the file whose change survives a reboot and, on this path, a
factory reset: [SAFETY.md](SAFETY.md).

Other things worth knowing about this file:

* On MediaTek there is a second, older candidate path,
  `/data/nvram/APCFG/APRDEB/WIFI`. The CLI names it only to report it and never
  writes it.
* A calibration file is not just a MAC: it holds calibration data around the MAC
  field, which is why a bad write there can affect WiFi calibration and why this
  project refuses to write blind.
