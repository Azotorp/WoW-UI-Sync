#!/bin/sh
set -eu
if [ "${1:-}" = --help ]; then echo "Source compatibility wrapper; prefer packaged server/stop.sh --data path"; exit 0; fi
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/common.sh"
exec "$WUS_JAVA" -jar "$WUS_JAR" stop "$@"
