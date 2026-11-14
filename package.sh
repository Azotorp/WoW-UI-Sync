#!/bin/sh
set -eu
WUS_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "${1:-}" in --help|-h) echo "Usage: package.sh [--version 1.1.1-alpha]. Always builds and tests first."; exit 0;; esac
if [ "$#" -gt 0 ]; then [ "$#" -eq 2 ] && [ "$1" = --version ] && [ "$2" = 1.1.1-alpha ] || { echo "Version must match 1.1.1-alpha." >&2; exit 2; }; fi
"$WUS_ROOT/compile.sh"
exec java -jar "$WUS_ROOT/dist/wow-ui-sync-server.jar" package --source "$WUS_ROOT"
