#!/bin/sh
set -eu
WUS_SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WUS_JAR=$WUS_SOURCE_ROOT/dist/wow-ui-sync-server.jar
[ -f "$WUS_JAR" ] || { echo "Build with compile.sh, or use the flat server/ release wrappers." >&2; exit 1; }
WUS_JAVA=${WUS_JAVA:-java}
