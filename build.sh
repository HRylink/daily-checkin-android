#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
cd "$(dirname "$0")"
rm -rf build/classes build/dex && mkdir -p build/classes build/dex
java -jar tools/ecj.jar -1.8 -warn:none -bootclasspath "$AJ:$SDK/platforms/android-34/core-for-system-modules.jar" -cp "$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 26 --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 build/res.zip
(cd build/dex && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out 签到.apk build/aligned.apk
"$BT/zipalign" -c 4 签到.apk
"$BT/apksigner" verify --verbose 签到.apk
echo "APK: $(pwd)/签到.apk"
