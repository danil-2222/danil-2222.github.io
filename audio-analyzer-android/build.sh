#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Debian/Ubuntu Android tool packages:
#   sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
APK=SpectrumAnalyzerPro.apk
KEYSTORE=${KEYSTORE:-release.keystore}

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

aapt package -f -m -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -J "$OUT/gen" -F "$OUT/res.apk"

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')

dalvik-exchange --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"

cp "$OUT/res.apk" "$OUT/unaligned.apk"
(cd "$OUT" && aapt add -f unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias spectrum \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Spectrum PRO" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --ks-key-alias spectrum \
    --out "$APK" "$OUT/aligned.apk"
apksigner verify --print-certs "$APK" | head -n 1
echo "Built $APK ($(du -h "$APK" | cut -f1))"
