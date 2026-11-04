#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${GOVMAP_SDK:?Set GOVMAP_SDK}"
: "${GOVMAP_ECJ:?Set GOVMAP_ECJ}"
mkdir -p build/asset-tests
GOVMAP_TEST_CP="build/dark-classes:tests/assets/json.jar:$GOVMAP_SDK/platforms/android-35/android.jar"
java -jar "$GOVMAP_ECJ" -8 -nowarn -proc:none -classpath "$GOVMAP_TEST_CP" -d build/asset-tests tests/assets/*.java
java -ea -cp "build/asset-tests:$GOVMAP_TEST_CP" il.david.govmap.dark.PersistentAssetTests
java -ea -cp "build/asset-tests:$GOVMAP_TEST_CP" il.david.govmap.dark.StreamingAssetTests
java -ea -cp "build/asset-tests:$GOVMAP_TEST_CP" il.david.govmap.dark.PublicMetadataTests
node tests/theme.test.cjs
node tests/location-preload.test.cjs
