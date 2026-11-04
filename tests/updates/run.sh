#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${GOVMAP_SDK:?Set GOVMAP_SDK}"
: "${GOVMAP_ECJ:?Set GOVMAP_ECJ}"
mkdir -p build/update-tests
GOVMAP_TEST_CP="build/dark-classes:tests/assets/json.jar"
java -jar "$GOVMAP_ECJ" -17 -nowarn -proc:none -classpath "$GOVMAP_TEST_CP" -d build/update-tests tests/updates/*.java
java -ea -cp "build/update-tests:$GOVMAP_TEST_CP" il.david.govmap.dark.UpdateReleaseTests
python3 tests/updates/publisher_test.py
