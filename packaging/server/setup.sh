#!/bin/sh
set -eu
case "${1:-}" in --help|-h) echo "Usage: setup.sh [--data /absolute/path]"; exit 0;; esac
 . "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/common.sh"
exec "$WUS_JAVA" "-Dwowuisync.home=$WUS_SCRIPT_DIR" -jar "$WUS_JAR" setup "$@"
