#!/bin/sh
# Ask the running server to stop cleanly (it exits within a few seconds).
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
exec "$JAVA" -jar "$JAR" stop "$@"
