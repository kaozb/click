#!/usr/bin/env bash
# 不依赖 Gradle / AndroidX 的最小构建脚本，直接产出签名 APK
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
# build-tools 34 的 d8 无法处理 JDK 25 生成的 class，优先使用 JDK 17
JDK17="$HOME/.local/share/mise/installs/java/17.0.20+101"
if [ -x "$JDK17/bin/javac" ]; then
    JAVAC="$JDK17/bin/javac"
else
    JAVAC="javac"
fi
[ -x "$BT/aapt2" ] || { echo "缺少 Android SDK: $SDK"; exit 1; }

rm -rf build
mkdir -p build/gen build/classes build/dex signing

"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip
"$BT/aapt2" link \
    -o build/base.apk \
    -I "$PLATFORM" \
    --manifest app/src/main/AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code 9 \
    --version-name 1.8 \
    build/res.zip

find app/src/main/java build/gen -name '*.java' > build/sources.txt
"$JAVAC" --release 11 -encoding UTF-8 -classpath "$PLATFORM" -d build/classes @build/sources.txt

find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output build/dex @build/classes.txt

cp build/base.apk build/app-unsigned.apk
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

if [ ! -f signing/release.jks ]; then
    keytool -genkeypair -keystore signing/release.jks -alias click \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass clicktimer -keypass clicktimer \
        -dname "CN=ClickTimer,O=ClickTimer,C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
    --ks signing/release.jks --ks-key-alias click \
    --ks-pass pass:clicktimer --key-pass pass:clicktimer \
    --v4-signing-enabled false \
    --out ClickTimer.apk build/app-aligned.apk
"$BT/apksigner" verify ClickTimer.apk

ls -l ClickTimer.apk
echo "OK -> $(pwd)/ClickTimer.apk"
