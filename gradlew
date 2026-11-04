#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo 'Downloading Gradle 8.13 official wrapper from services.gradle.org ...'
  BASE='https://services.gradle.org/distributions/gradle-8.13-wrapper.jar'
  curl --fail --location --silent --show-error "$BASE" -o "$JAR.tmp"
  expected=$(curl --fail --location --silent --show-error "$BASE.sha256" | cut -d ' ' -f 1)
  actual=$(sha256sum "$JAR.tmp" | cut -d ' ' -f 1)
  if [ "$actual" != "$expected" ]; then
    rm -f "$JAR.tmp"
    echo 'Wrapper SHA-256 verification failed' >&2
    exit 1
  fi
  mv "$JAR.tmp" "$JAR"
fi
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
exec "$JAVA" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
