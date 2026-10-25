#!/bin/sh
# Compiles the Minecraft-independent core + its tests against the JUnit shim and runs them (no Gradle/network needed).
# Needs a JDK (javac) 17+. In the original sandbox only the jdk.compiler module was available, so a tiny wrapper
# "javac-lite" (java --add-modules jdk.compiler -m jdk.compiler/com.sun.tools.javac.Main "$@") was used instead of javac.
set -e
cd "$(dirname "$0")/../.."
JAVAC=${JAVAC:-javac}
OUT=${OUT:-/tmp/qj-verify}
rm -rf "$OUT" && mkdir -p "$OUT/shim" "$OUT/core" "$OUT/tests"
$JAVAC -source 17 -target 17 -encoding UTF-8 -d "$OUT/shim" $(find tools/sandbox-verification/junit-shim -name '*.java')
$JAVAC -source 17 -target 17 -encoding UTF-8 -Xlint:all -d "$OUT/core" $(find src/main/java/com/questjournal/core -name '*.java')
$JAVAC -source 17 -target 17 -encoding UTF-8 -cp "$OUT/shim:$OUT/core" -d "$OUT/tests" $(find src/test/java -name '*.java')
CLASSES=$(cd "$OUT/tests" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
java -cp "$OUT/shim:$OUT/core:$OUT/tests" shim.ShimRunner $CLASSES
