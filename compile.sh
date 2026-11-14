#!/bin/sh
set -eu
WUS_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "${1:-}" in --help|-h) echo "Usage: compile.sh [--clean] [--skip-tests] (JDK 21+, Maven 3.9+)"; exit 0;; esac
command -v javac >/dev/null 2>&1 || { echo "JDK 21+ required (javac is missing)." >&2; exit 1; }
WUS_MAJOR=$(javac -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
[ "${WUS_MAJOR:-0}" -ge 21 ] || { echo "JDK 21+ required." >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "Maven 3.9+ is required." >&2; exit 1; }
WUS_SKIP=false
for WUS_ARG in "$@"; do case "$WUS_ARG" in --clean) :;; --skip-tests) WUS_SKIP=true;; *) echo "Unknown option: $WUS_ARG" >&2; exit 2;; esac; done
cd "$WUS_ROOT"
exec mvn -B clean package -DskipTests="$WUS_SKIP"
