# Mac Changer (rooted Android)

A lightweight, persistent WiFi MAC changer for rooted Android phones, with an
on-device buildable APK (no PC required) plus a CLI shell tool.

Tested on: **Infinix SMART 5 (MediaTek MT6761), Android 11, Magisk**.

---

## What it does

- Finds the vendor **NVRAM** file that stores the factory WiFi MAC, backs it up,
  replaces the MAC bytes, then restarts WiFi so the driver re-reads it.
- Detects multiple vendors:
  - MediaTek: `/mnt/vendor/nvdata/APCFG/APRDEB/WIFI` (and older `/data/nvram/...`)
  - Qualcomm: `/mnt/vendor/persist/wifi/wlan_mac.bin`, `/persist/...`, `/data/vendor/...`
  - Samsung: `/efs/wifi/.mac.info`, `/efs/wifi/mac.info`
  - Unisoc:  `/productinfo/wifi_mac`, `/mnt/vendor/productinfo/wifi_mac`
- Falls back to a runtime `ip link` change when no NVRAM file matches.
- Because it patches NVRAM, the change **survives reboots**.

The APK also detects Android's per-network **MAC randomization** and warns you
when it will override the spoof (`privacy: randomized`).

---

## Layout

```
MacChanger/
  app/                     Android app source
    AndroidManifest.xml
    src/com/macchanger/MainActivity.java
    build.sh               on-device build script (Termux)
    ks.jks                 debug signing keystore (alias: mac / pass: android)
  cli/
    macchanger.sh          standalone CLI (run as root)
  prebuilt/
    MacChanger.apk         ready-to-install APK
```

---

## Install the prebuilt APK

```bash
su -c 'pm install -r prebuilt/MacChanger.apk'
```

Or copy it to the phone and tap it. Grant root on first launch.

---

## Build the APK on the device (Termux)

Requires Termux packages: `openjdk-21 aapt apksigner d8 zipalign` and an
`android.jar` in `$PREFIX/share/java`. The build also uses the device's
`/system/framework/framework-res.apk` for resource IDs.

```bash
cd app
./build.sh
# -> app-signed.apk
```

---

## Use the CLI

```bash
# persistent + applies immediately (writes NVRAM, restarts WiFi)
sh cli/macchanger.sh set AA:BB:CC:DD:EE:FF
sh cli/macchanger.sh random
sh cli/macchanger.sh restore      # back to factory MAC
sh cli/macchanger.sh show         # factory / nvram / runtime
```

The script needs root. It stores the factory backup at
`/data/adb/macchanger/WIFI.factory`.

---

## How the NVRAM patch works (MediaTek example)

```
/mnt/vendor/nvdata/APCFG/APRDEB/WIFI
offset 0x00: 01 00 08 00              header
offset 0x04: 04 f9 93 11 36 bf        the WiFi MAC (6 bytes)
```

The app searches the file for the current MAC bytes (also reversed / ASCII forms)
and replaces them, so it is not tied to a fixed offset.

---

## Known limitation: Android MAC randomization

On Android 11+, a saved network can use a **randomized MAC**. When it does, the
framework overrides the hardware/NVRAM MAC, so your spoof will not reach the
router. The framework rewrites `WifiConfigStore.xml` within seconds even as root,
so it cannot be forced from the app.

**Fix:** WiFi settings -> (network) -> Privacy -> **Use device MAC**.
The app's `privacy` row turns green (`device MAC`) once this is set.

Note: `Restore factory` still sets the hardware/NVRAM MAC to the factory value on
any network; randomization only changes what the router sees.

---

## Safety

- The app always copies the original NVRAM before writing.
- A bad write could affect WiFi calibration; keep the backup
  (`/data/adb/macchanger/` for the CLI, app-private storage for the APK).
- Use at your own risk.
