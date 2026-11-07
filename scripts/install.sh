#!/bin/sh
# First-time server setup. Run once, as the user the server will run as.
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
exec "$JAVA" -jar "$JAR" install "$@"
