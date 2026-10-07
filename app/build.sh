#!/data/data/com.termux/files/usr/bin/bash
# MacChanger on-device build. Run it as ./build.sh, NOT 'sh build.sh': it uses
# bash-only features (set -o pipefail, arrays) and re-execs itself under bash if
# a POSIX shell such as dash got here first.
if [ -z "${BASH_VERSION:-}" ]; then
  command -v bash >/dev/null 2>&1 || { echo "build.sh must run under bash: use ./build.sh" >&2; exit 2; }
  exec bash "$0" "$@"
fi

set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
usage: ./build.sh [--new-key]

  KS=/path/to/ks.p12 KS_PASS='...' ./build.sh             sign with your release key
  KS=/path/to/ks.p12 KS_PASS='...' ./build.sh --new-key   create that key, then sign

There is no default key and no key is ever generated silently: an APK signed
with a different key cannot update an installed com.macchanger at all (see the
refusal message for what that costs).

Environment:
  KS                    keystore to sign with. REQUIRED.
  KS_PASS               keystore (and key) password. REQUIRED.
  KS_TYPE               keystore type, default PKCS12.
  KS_ALIAS              key alias; default: the only entry in the keystore.
  KS_KEY_PASS           key password, if it differs from KS_PASS.
  VERSION_CODE, VERSION_NAME   override the stamped build identity.
  RELEASE_CERT_SHA256   refuse to finish unless the APK signer certificate
                        SHA-256 matches this (publish it with your release).
  AJ, FRAMEWORK, OUT    path overrides; defaults are the device's own files.
EOF
}

NEW_KEY=0
for arg in "$@"; do
  case "$arg" in
    --new-key) NEW_KEY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

ORIGDIR=$(pwd)
cd "$(dirname "$0")"

AJ=${AJ:-${PREFIX:-}/share/java/android.jar}
FRAMEWORK=${FRAMEWORK:-/system/framework/framework-res.apk}
OUT=${OUT:-app-signed.apk}
KS=${KS:-}
KS_PASS=${KS_PASS:-}
KS_TYPE=${KS_TYPE:-PKCS12}
KS_ALIAS=${KS_ALIAS:-}
KS_KEY_PASS=${KS_KEY_PASS:-$KS_PASS}
RELEASE_CERT_SHA256=${RELEASE_CERT_SHA256:-}

# A relative KS is taken relative to where the script was run from, then to app/.
case "$KS" in
  ""|/*) ;;
  *) if [ ! -f "$KS" ] && [ -f "$ORIGDIR/$KS" ]; then KS="$ORIGDIR/$KS"; fi ;;
esac

norm_fp() { printf '%s' "$1" | tr -d ':' | tr 'A-F' 'a-f' | tr -d ' \t'; }

hash256() {
  # The status of the hasher is the status of this function.  The old version
  # ended every branch with 'return 0', so a failing sha256sum produced an empty
  # string and a successful exit: the build then printed an empty 'APK SHA-256'
  # line - the one value a user is told to compare - and exited 0.  Callers that
  # need the value now fail loudly instead (see APK_SHA below).
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; return $?; fi
  if command -v busybox >/dev/null 2>&1; then busybox sha256sum "$1"; return $?; fi
  openssl dgst -sha256 "$1" | awk -v f="$1" '{print $NF "  " f}'
}

# ---- everything is validated before a single file is created or removed ----
problems=()
# $OUT is fed to 'rm -rf' below.  An existing directory there would be deleted
# with everything in it (OUT=src would remove the Java sources), so it is
# checked here, with the other prerequisites, and not after the removal.
case "$OUT" in
  */) problems+=("OUT must be a file name, not a directory path: $OUT") ;;
esac
if [ -d "$OUT" ]; then
  problems+=("OUT is an existing directory: $OUT - refusing to remove it; set OUT=/path/to/app-signed.apk")
fi
if [ ! -f "$AJ" ]; then
  problems+=("android.jar not found: $AJ (is \$PREFIX set? expected \$PREFIX/share/java/android.jar, or set AJ=/path/to/android.jar)")
fi
if [ ! -f "$FRAMEWORK" ]; then
  problems+=("framework-res.apk not found: $FRAMEWORK (aapt needs the device's own resource table; set FRAMEWORK=/path/to/framework-res.apk off-device)")
fi
for tool in aapt d8 zipalign apksigner keytool javac; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    problems+=("missing build tool: $tool (Termux: pkg install openjdk-21 aapt apksigner d8 zipalign)")
  fi
done
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v busybox >/dev/null 2>&1 && ! command -v openssl >/dev/null 2>&1; then
  problems+=("missing sha256 tool: none of sha256sum, busybox or openssl is on PATH (pkg install coreutils)")
fi

ks_refusal=
if [ -z "$KS" ]; then
  ks_refusal="no signing keystore was supplied (KS is unset)"
elif [ -z "$KS_PASS" ]; then
  ks_refusal="KS_PASS is unset, so $KS cannot be opened"
elif [ ! -f "$KS" ] && [ "$NEW_KEY" -ne 1 ]; then
  ks_refusal="signing keystore not found: $KS"
elif [ -f "$KS" ] && [ "$NEW_KEY" -eq 1 ]; then
  ks_refusal="--new-key will not overwrite the existing $KS (move it away first if you really mean it)"
fi

if [ "${#problems[@]}" -gt 0 ] || [ -n "$ks_refusal" ]; then
  echo >&2
  echo "===============================================================" >&2
  echo " BUILD REFUSED - nothing was compiled, packaged or signed." >&2
  echo "===============================================================" >&2
  if [ "${#problems[@]}" -gt 0 ]; then
    echo "Missing prerequisites:" >&2
    for p in "${problems[@]}"; do
      echo "  - $p" >&2
    done
  fi
  if [ -n "$ks_refusal" ]; then
    cat >&2 <<EOF

This build will NOT invent a signing key for you.
Signing: $ks_refusal

  An APK signed with a different key cannot replace an installed
  com.macchanger: 'pm install -r' fails with

      INSTALL_FAILED_UPDATE_INCOMPATIBLE

  and the only way out is 'pm uninstall com.macchanger', which deletes the
  app's private data. On older builds that is exactly where your only NVRAM
  backup and factory MAC lived:

      /data/data/com.macchanger/files/nvram_backup/

  Copy that directory off the device BEFORE uninstalling anything. Current
  builds also keep the factory record under /data/adb/macchanger/.

  Build with your release key:
      KS=/path/to/ks.p12 KS_PASS='...' ./build.sh

  Only if you accept that an already-installed build becomes un-updatable and
  must be uninstalled first, let this script create a new key:
      KS=/path/to/ks.p12 KS_PASS='...' ./build.sh --new-key
EOF
  fi
  exit 1
fi

echo "[1/8] prerequisites + signing identity"
echo "      android.jar: $AJ"
echo "      framework:   $FRAMEWORK"

# The signing identity is opened and validated BEFORE anything is removed.
# The keystore probe used to run after 'rm -rf build "$OUT" ...', so a wrong
# KS_PASS destroyed the previously built signed APK and only then reported that
# it could not open the keystore.  The probe writes to a scratch file because
# build/ does not exist yet at this point; it is moved into build/ once the
# build directory has been created, so build/keystore.txt still exists for the
# rest of the script and for a human reading the log afterwards.
KS_PROBE=${TMPDIR:-/tmp}/macchanger-ks-probe.$$.txt
if [ "$NEW_KEY" -eq 1 ]; then
  echo "      generating a new $KS_TYPE keystore: $KS"
  echo "      WARNING: builds signed with any other key can no longer be updated in place."
  keytool -genkeypair -keystore "$KS" -storetype "$KS_TYPE" -alias "${KS_ALIAS:-mac}" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=MacChanger,OU=Dev,O=Dev,L=X,S=X,C=US"
  KS_ALIAS=${KS_ALIAS:-mac}
fi

keytool_alias_args=()
if [ -n "$KS_ALIAS" ]; then
  keytool_alias_args=(-alias "$KS_ALIAS")
fi
if ! keytool -list -v -keystore "$KS" -storetype "$KS_TYPE" -storepass "$KS_PASS" \
     "${keytool_alias_args[@]}" >"$KS_PROBE" 2>&1; then
  cat "$KS_PROBE" >&2
  rm -f "$KS_PROBE"
  die "cannot open keystore $KS (type $KS_TYPE) with the supplied KS_PASS - check KS_PASS, and set KS_TYPE=JKS for an old JKS keystore. Nothing was removed: the previous build output is still there."
fi
KEYSTORE_FP=$(sed -n 's/.*SHA256: *//p' "$KS_PROBE")
KEYSTORE_FP=${KEYSTORE_FP%%$'\n'*}
if [ -z "$KEYSTORE_FP" ]; then
  rm -f "$KS_PROBE"
  die "no certificate SHA-256 in $KS (it holds no key?); check KS_TYPE and KS_ALIAS"
fi
echo "      keystore:   $KS ($KS_TYPE)"
echo "      signer certificate SHA-256: $KEYSTORE_FP"

rm -rf build "$OUT" app-unsigned.apk app-aligned.apk
mkdir -p build/classes build/dex
mv -f "$KS_PROBE" build/keystore.txt 2>/dev/null || cp "$KS_PROBE" build/keystore.txt
rm -f "$KS_PROBE"

# Anything after this point must pass every gate, or the half-verified APK is
# removed instead of being left on disk looking installable.
KEEP_OUT=0
cleanup() {
  if [ "$KEEP_OUT" -ne 1 ] && [ -f "$OUT" ]; then
    rm -f "$OUT"
    echo "FAILED: removed unverified $OUT (the build did not pass every gate)" >&2
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# The keystore this project shipped with is compromised: the audit published its
# password, and prebuilt/MacChanger.apk is signed with it (alias mac, serial
# f95c6faeadff3f8d).  Signing with it is not refused - rebuilding the historical
# artifact with the historical key is a legitimate thing to want - but it is
# said out loud, at the last moment it can still be acted on.  Both the keystore
# file and the signer certificate are matched, so a re-exported copy of the same
# key (a different file, the same identity) is caught as well.
LEAKED_KS_SHA256=91a447f33ebd648d99bd2b26b3daa01d1f07e5faeb52266040b712b227ffae3a
LEAKED_CERT_SHA256=8fedcaf18e92bb1924e5de1cbba5ea6f8b31fb039108eeaffc4f9cf51186f1af
KS_FILE_SHA=$(hash256 "$KS") || die "cannot hash the keystore $KS (none of sha256sum, busybox or openssl worked)"
KS_FILE_SHA=${KS_FILE_SHA%% *}
LEAKED_BY=
if [ "$KS_FILE_SHA" = "$LEAKED_KS_SHA256" ]; then
  LEAKED_BY="the keystore file itself (SHA-256 $KS_FILE_SHA)"
elif [ "$(norm_fp "$KEYSTORE_FP")" = "$LEAKED_CERT_SHA256" ]; then
  LEAKED_BY="its signer certificate (SHA-256 $KEYSTORE_FP), a different file holding the same key"
fi
if [ -n "$LEAKED_BY" ]; then
  echo >&2
  echo "===============================================================" >&2
  echo " WARNING: THIS IS THE KEY THAT LEAKED." >&2
  echo "===============================================================" >&2
  cat >&2 <<EOF
You are signing with $LEAKED_BY.

That key's password was published in this project's audit, so its private half
must be treated as compromised: whoever holds it can build an APK that Android
accepts as an update of com.macchanger on every device with the shipped build,
which is the ability to hand a user a modified root tool that rewrites their
Wi-Fi calibration.

  This APK is still usable for reproducing the historical artifact and for
  testing on a device you are going to wipe.  It is NOT fit to be published as
  a new release.

  A new release needs a new key, and an APK signed with a new key cannot update
  an installed com.macchanger: 'pm install -r' fails with
  INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only way out is
  'pm uninstall com.macchanger', which deletes the app's private data.  Copy the
  user's record off the device before that: /data/adb/macchanger/ and, for older
  builds, /data/data/com.macchanger/files/nvram_backup/.

The build continues: this is a warning, not a refusal.
EOF
  echo >&2
fi

echo "[2/8] compile java"
find src -name '*.java' > build/sources.txt
[ -s build/sources.txt ] || die "no .java sources under $(pwd)/src"
# javac's own exit status is the build's status. The old
# 'javac ... 2>&1 | grep -v ... || true' pipeline reported grep's status and
# could package a partial class set as if the build had succeeded.
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -classpath "$AJ" \
      -d build/classes @build/sources.txt || die "javac failed (see the errors above; nothing was packaged)"
find build/classes -name '*.class' > build/classes.txt
[ -s build/classes.txt ] || die "javac produced no class files at all"
[ -f build/classes/com/macchanger/MainActivity.class ] || die "expected class was not produced: build/classes/com/macchanger/MainActivity.class"
echo "      $(wc -l < build/classes.txt) class files"

echo "[3/8] dex"
d8 --min-api 21 --lib "$AJ" --output build/dex @build/classes.txt

echo "[4/8] package"
GIT_REV=$(git rev-parse --short HEAD 2>/dev/null || echo nogit)
if [ "$GIT_REV" != nogit ]; then
  # 'git diff --quiet' sees the worktree against the index only, so a purely
  # staged change or an untracked source file produced a clean '1.0+<rev>' and
  # two different trees could share one identity.  CHANGELOG.md states that two
  # builds of different sources can never look identical, so the whole status is
  # consulted (ignored build outputs stay invisible, as they should).
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then GIT_REV="$GIT_REV-dirty"; fi
fi
VC=${VERSION_CODE:-$(date +%Y%m%d)}
VN=${VERSION_NAME:-1.0+$GIT_REV}
echo "      versionCode $VC / versionName $VN"
aapt package -f -M AndroidManifest.xml -I "$AJ" -I "$FRAMEWORK" \
  --version-code "$VC" --version-name "$VN" -F app-unsigned.apk
cp build/dex/classes.dex ./classes.dex
aapt add app-unsigned.apk classes.dex >/dev/null
rm -f classes.dex

echo "[5/8] align"
zipalign -f 4 app-unsigned.apk app-aligned.apk

echo "[6/8] sign"
apksigner_alias_args=()
if [ -n "$KS_ALIAS" ]; then
  apksigner_alias_args=(--ks-key-alias "$KS_ALIAS")
fi
apksigner sign --ks "$KS" --ks-type "$KS_TYPE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_KEY_PASS" "${apksigner_alias_args[@]}" \
  --out "$OUT" app-aligned.apk

echo "[7/8] post-sign gates"
if ! aapt list "$OUT" > build/contents.txt 2>&1; then
  cat build/contents.txt >&2
  die "gate: 'aapt list $OUT' failed - the APK is not readable as a zip"
fi
if ! grep -q 'classes\.dex' build/contents.txt; then
  die "gate: $OUT contains no classes.dex (aapt add put the dex nowhere?)"
fi
if ! zipalign -c -v 4 "$OUT" > build/zipalign.txt 2>&1; then
  cat build/zipalign.txt >&2
  die "gate: $OUT is not 4-byte aligned"
fi
if ! aapt dump badging "$OUT" > build/badging.txt 2>&1; then
  cat build/badging.txt >&2
  die "gate: 'aapt dump badging $OUT' failed - the packaged manifest is not readable"
fi
if ! grep -q '^launchable-activity' build/badging.txt; then
  die "gate: the built manifest declares no launchable activity"
fi
if ! grep -qE "^application: .*icon='[^']" build/badging.txt && ! grep -q '^application-icon' build/badging.txt; then
  die "gate: the built manifest declares no application icon (see android:icon in AndroidManifest.xml). An empty icon='' does not pass this gate"
fi
if ! grep -qF "versionCode='$VC'" build/badging.txt; then
  die "gate: versionCode stamping did not take (wanted $VC) - this aapt ignores --version-code"
fi
if ! grep -qF "versionName='$VN'" build/badging.txt; then
  die "gate: versionName stamping did not take (wanted $VN) - this aapt ignores --version-name"
fi
if grep -q '^uses-permission' build/badging.txt; then
  die "gate: $OUT requests a permission; MacChanger must ship with none (AndroidManifest.xml)"
fi
grep -E '^(package|application):' build/badging.txt || true

echo "[8/8] identity + hashes"
if ! apksigner verify --print-certs -v "$OUT" > build/certs.txt 2>&1; then
  cat build/certs.txt >&2
  die "gate: apksigner verify failed - $OUT is not correctly signed"
fi
# API 21-23 ignore the v2/v3 signature (Janus, CVE-2017-13156): without the v1
# JAR signature this APK cannot even be installed on the documented minimum.
if ! grep -q 'Verified using v1 scheme.*: true' build/certs.txt; then
  cat build/certs.txt >&2
  die "gate: $OUT has no v1 (JAR) signature, so API 21-23 would refuse to install it"
fi
APK_FP=$(sed -n 's/.*Signer #1 certificate SHA-256 digest: *//p' build/certs.txt)
APK_FP=${APK_FP%%$'\n'*}
APK_FP=$(norm_fp "$APK_FP")
if [ -z "$APK_FP" ]; then
  if keytool -printcert -jarfile "$OUT" > build/printcert.txt 2>&1; then
    APK_FP=$(sed -n 's/.*SHA256: *//p' build/printcert.txt)
    APK_FP=${APK_FP%%$'\n'*}
    APK_FP=$(norm_fp "$APK_FP")
  fi
fi
if [ -z "$APK_FP" ]; then
  cat build/certs.txt >&2
  die "gate: could not read $OUT's signer certificate SHA-256 from apksigner or keytool"
fi
if [ "$APK_FP" != "$(norm_fp "$KEYSTORE_FP")" ]; then
  cat build/certs.txt >&2
  die "gate: $OUT was signed by a different certificate than $KS (set KS_ALIAS if that keystore holds more than one key)"
fi
if [ -n "$RELEASE_CERT_SHA256" ]; then
  if [ "$APK_FP" != "$(norm_fp "$RELEASE_CERT_SHA256")" ]; then
    die "gate: $OUT's signer certificate is not the published release certificate (RELEASE_CERT_SHA256)"
  fi
fi
APK_SHA_LINE=$(hash256 "$OUT") || die "gate: cannot hash $OUT (none of sha256sum, busybox or openssl worked)"
APK_SHA=${APK_SHA_LINE%% *}
[ -n "$APK_SHA" ] || die "gate: hashing $OUT produced an empty SHA-256; refusing to report a release hash that does not exist"
echo "      $APK_SHA_LINE"
echo "      signer certificate SHA-256: $KEYSTORE_FP (matches the keystore)"
cat build/certs.txt

KEEP_OUT=1
echo
echo "  version    versionCode $VC / versionName $VN"
echo "  git        $GIT_REV"
echo "  keystore   $KS ($KS_TYPE)"
echo "  signer     $KEYSTORE_FP"
echo "  APK SHA-256 $APK_SHA"
echo "  Publish the APK SHA-256 and the signer certificate SHA-256 with every release."
echo "  Note: on API 21-23 the platform ignores the v2/v3 signatures (Janus,"
echo "  CVE-2017-13156), so only the v1 JAR signature protects the APK there."
echo "BUILT: $(pwd)/$OUT"
ls -la "$OUT"
