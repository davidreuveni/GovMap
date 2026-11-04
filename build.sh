#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${GOVMAP_SDK:?Set GOVMAP_SDK to your Android SDK directory}"
: "${GOVMAP_ECJ:?Set GOVMAP_ECJ to ecj-3.38.0.jar}"
GOVMAP_TOOLS="$GOVMAP_SDK/build-tools/35.0.0"
GOVMAP_PLATFORM="$GOVMAP_SDK/platforms/android-35/android.jar"
rm -rf build/dark-classes
rm -f build/classes.dex
mkdir -p build/dark-classes
"$GOVMAP_TOOLS/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$GOVMAP_PLATFORM" -F build/unsigned-dark.apk
java -jar "$GOVMAP_ECJ" -8 -nowarn -proc:none -classpath "$GOVMAP_PLATFORM" -d build/dark-classes src/il/david/govmap/dark/*.java
"$GOVMAP_TOOLS/d8" --lib "$GOVMAP_PLATFORM" --min-api 26 --output build build/dark-classes/il/david/govmap/dark/*.class
(cd build && zip -q unsigned-dark.apk classes.dex)
"$GOVMAP_TOOLS/zipalign" -f 4 build/unsigned-dark.apk build/aligned-dark.apk
"$GOVMAP_TOOLS/apksigner" sign --ks signing/release.p12 --ks-pass file:signing/password.txt --out ../GovMap-Dark.apk build/aligned-dark.apk
"$GOVMAP_TOOLS/apksigner" verify --verbose ../GovMap-Dark.apk
