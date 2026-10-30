#!/usr/bin/env bash
# Renders the real app UI to PNGs on the JVM with Robolectric (no device or emulator needed).
# Needs Gradle and Maven Central. The SDK 34 image (~150 MB) is fetched into deps/ once.
# androidx.test is replaced by the small stubs in src/test/java/androidx because Google's
# Maven repository may be unreachable.
set -euo pipefail
cd "$(dirname "$0")"
APP=$(cd .. && pwd)
(cd "$APP" && ./build.sh)

JAR=android-all-instrumented-14-robolectric-10818077-i7.jar
mkdir -p deps assets src/test/resources/com/android/tools
if [ ! -f "deps/$JAR" ]; then
    curl -fL --retry 10 --retry-delay 15 -o "deps/$JAR" \
        "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/$JAR"
fi
# Robolectric 4.14 also looks up the SDK 35 jar path; pointing it at SDK 34 is enough here.
ln -sf "$JAR" deps/android-all-instrumented-15-robolectric-12650502-i7.jar

cat > src/test/resources/com/android/tools/test_config.properties <<CFG
android_resource_apk=$APP/build/res.apk
android_merged_manifest=$APP/AndroidManifest.xml
android_merged_assets=$PWD/assets
android_custom_package=com.example.audiospectrumpro
CFG

APP_CLASSES="$APP/build/classes" SHOTS_DIR="$PWD/out" gradle test --no-daemon -q
echo "Screenshots in $PWD/out"
