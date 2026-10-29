#!/usr/bin/env bash
# Regenerates the sample reports in sample-reports/ from the synthetic fixtures.
#
# The reports are produced by running the real analysis pipeline over fixtures/fixture-*.apk, so
# they contain genuine analyser output. They are marked as sample data in the report metadata.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f fixtures/fixture-insecure.apk ]; then
  echo "fixtures missing - running tools/make-fixtures.py first"
  python3 tools/make-fixtures.py
fi

mkdir -p sample-reports
./gradlew :tools:sampleReport --console=plain -q || {
  echo "The sample-report task requires the Gradle wrapper and an Android SDK; see docs/BUILD.md."
  exit 1
}
echo "sample reports written to sample-reports/"
