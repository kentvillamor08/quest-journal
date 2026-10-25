#!/bin/sh
# Compiles the WHOLE mod (core + Minecraft layer) against signature-only stubs of the Forge/Minecraft APIs it uses.
# This checks the mod's own consistency; it cannot prove the stubs match the real Forge 1.20.1 API (see docs/BUILD_AND_TEST_REPORT.md).
set -e
cd "$(dirname "$0")/../.."
JAVAC=${JAVAC:-javac}
OUT=${OUT:-/tmp/qj-stubcheck}
rm -rf "$OUT" && mkdir -p "$OUT/stubs-src" "$OUT/classes"
python3 tools/sandbox-verification/mc-stubs/gen.py tools/sandbox-verification/mc-stubs/spec.txt "$OUT/stubs-src"
$JAVAC -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options,-serial -d "$OUT/classes" $(find "$OUT/stubs-src" src/main/java -name '*.java')
echo "OK: $(find "$OUT/classes/com/questjournal" -name '*.class' | wc -l) mod classes compiled against the stubs"
