#!/usr/bin/env bash
# Downloads the Robolectric android-all jar used by the JVM UI tests, so the test run works without
# network access. Safe to re-run; the file is skipped when present.
set -euo pipefail
VERSION="14-robolectric-10818077-i6"
DIR="$(cd "$(dirname "$0")" && pwd)/robolectric-deps"
mkdir -p "$DIR"
TARGET="$DIR/android-all-instrumented-$VERSION.jar"
if [ -f "$TARGET" ]; then
  echo "already present: $TARGET"
  exit 0
fi
URL="https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$VERSION/android-all-instrumented-$VERSION.jar"
echo "downloading $URL"
curl -fSL --retry 3 -o "$TARGET" "$URL"
echo "saved $TARGET"
