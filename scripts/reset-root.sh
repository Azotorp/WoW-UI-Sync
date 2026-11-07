#!/bin/sh
# Replace root's public key or password. Works while the server is running.
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
exec "$JAVA" -jar "$JAR" reset-root "$@"
