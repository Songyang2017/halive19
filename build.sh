#!/bin/sh
# 手动构建 halive19：javac + aapt2 + d8 + zipalign + apksigner（不依赖 Gradle）
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
SDK="$ROOT/../android-sdk"
JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
OUT="$ROOT/build"
SRC="$ROOT/app/src/main"
KS="${TVHOME_KEYSTORE:-$ROOT/../tvhome19/debug.keystore}"
# 没有签名密钥就现生成一个 debug keystore，保证 clone 下来即可构建
if [ ! -f "$KS" ]; then
  KS="$ROOT/debug.keystore"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
      -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    echo "生成签名密钥: $KS"
  fi
fi

[ -f "$JAR" ] || { echo "缺少 $JAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "缺少 build-tools" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dexout"

echo "[1/5] 资源"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled.flata"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 19 --target-sdk-version 22 \
  "$OUT/compiled.flata"

echo "[2/5] 编译 Java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -cp "$JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v 'bootstrap class path' || true

echo "[3/5] dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$JAR" --min-api 19 --output "$OUT/dexout" @"$OUT/classes.txt"

echo "[4/5] 打包"
cd "$OUT"
cp dexout/classes.dex classes.dex
cp base.apk unsigned.apk
zip -q -u unsigned.apk classes.dex
"$BT/zipalign" -f 4 unsigned.apk aligned.apk

echo "[5/5] 签名"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
  --key-pass pass:android --min-sdk-version 19 \
  --out "$ROOT/halive19-debug.apk" aligned.apk
"$BT/apksigner" verify --min-sdk-version 19 "$ROOT/halive19-debug.apk"
ls -lh "$ROOT/halive19-debug.apk"
echo "APK=$ROOT/halive19-debug.apk"
