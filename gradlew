#!/bin/sh
# Minimal Gradle wrapper launcher (equivalent to the official script for normal use).
APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar is missing from gradle/wrapper/." >&2
  echo "Fix: copy gradle/wrapper/gradle-wrapper.jar from the official Forge 1.20.1 MDK," >&2
  echo "or run 'gradle wrapper --gradle-version 8.1.1' once with a locally installed Gradle," >&2
  echo "or just open the project in IntelliJ IDEA (it downloads Gradle itself)." >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
