#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

AJ="$PREFIX/share/java/android.jar"
FRAMEWORK=/system/framework/framework-res.apk
OUT=app-signed.apk

rm -rf build "$OUT" app-unsigned.apk app-aligned.apk
mkdir -p build/classes build/dex

echo "[1/6] compile java"
find src -name '*.java' > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$AJ" \
      -d build/classes @build/sources.txt 2>&1 | grep -v 'source value 8\|target value 8\|deprecat' || true

echo "[2/6] dex"
find build/classes -name '*.class' > build/classes.txt
d8 --min-api 21 --lib "$AJ" --output build/dex @build/classes.txt

echo "[3/6] package"
aapt package -f -M AndroidManifest.xml -I "$AJ" -I "$FRAMEWORK" -F app-unsigned.apk
cp build/dex/classes.dex ./classes.dex
aapt add app-unsigned.apk classes.dex >/dev/null
rm -f classes.dex

echo "[4/6] align"
zipalign -f 4 app-unsigned.apk app-aligned.apk

echo "[5/6] keystore"
if [ ! -f ks.jks ]; then
  keytool -genkeypair -keystore ks.jks -alias mac -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=MacChanger,OU=Dev,O=Dev,L=X,S=X,C=US" 2>/dev/null
fi

echo "[6/6] sign"
apksigner sign --ks ks.jks --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" app-aligned.apk
apksigner verify "$OUT"
echo "BUILT: $(pwd)/$OUT"
ls -la "$OUT"
