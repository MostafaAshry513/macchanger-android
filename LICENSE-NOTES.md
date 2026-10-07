# Why this licence, and what it means here

`LICENSE` holds the plain MIT text and nothing else, so that licence detection
(including GitHub's) reads it correctly. The reasoning behind the choice lives
here instead.

* **Why MIT rather than a copyleft licence.** So that ROM packagers, mirror
  operators and downstream maintainers can redistribute a fixed build. That is
  the point: the fixes to the write path and the recovery path are only useful if
  people are legally allowed to ship them.
* **The disclaimer is not boilerplate here.** This software runs as root and
  rewrites a Wi-Fi calibration file on your device. It is provided with no
  warranty of any kind, and you accept that risk by using it. Read
  [README.md](README.md) — in particular *Safety, backups and recovery* — before
  installing it.
* **The licence covers the source in this repository only.** It grants no right
  to use this software against a device you do not own or a network you are not
  authorized to use; see *Intended use and authorization* in
  [README.md](README.md).
* **Copyright holder.** Recorded as "MacChanger contributors". This distribution
  is maintained at <https://github.com/MostafaAshry513/macchanger-android>; if you
  fork and redistribute it, put a real identity in `LICENSE` as the copyright
  holder.
* **Trademarks and vendor names** (MediaTek, Qualcomm, Samsung, Unisoc, Magisk,
  Termux, Android) belong to their owners and are used here descriptively, to say
  which calibration paths and tools the code targets. No affiliation or
  endorsement is implied.
