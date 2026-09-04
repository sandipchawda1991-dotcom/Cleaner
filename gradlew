#!/bin/sh
# Gradle wrapper shell script
GRADLE_OPTS="${GRADLE_OPTS:-""}"
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
