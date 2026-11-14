#!/bin/sh
set -eu
WUS_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WUS_RELEASE_DIR=$WUS_SCRIPT_DIR
if [ -f "$WUS_SCRIPT_DIR/active-release" ]; then
  WUS_ACTIVE=$(cat "$WUS_SCRIPT_DIR/active-release")
  case "$WUS_ACTIVE" in .|releases/*/server) :;; *) echo "Invalid active release pointer" >&2; exit 1;; esac
  case "$WUS_ACTIVE" in *..*) [ "$WUS_ACTIVE" = . ] || exit 1;; esac
  WUS_RELEASE_DIR=$WUS_SCRIPT_DIR/$WUS_ACTIVE
fi
WUS_JAR=$WUS_RELEASE_DIR/wow-ui-sync.server.jar
if [ ! -f "$WUS_JAR" ] && [ -f "$WUS_RELEASE_DIR/wow-ui-sync-server.jar" ]; then WUS_JAR=$WUS_RELEASE_DIR/wow-ui-sync-server.jar; fi
WUS_JAVA=${WUS_JAVA:-java}
command -v "$WUS_JAVA" >/dev/null 2>&1 || { echo "Java 21+ required." >&2; exit 1; }
[ -f "$WUS_JAR" ] || { echo "Missing server JAR: $WUS_JAR" >&2; exit 1; }
WUS_MAJOR=$("$WUS_JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
[ "${WUS_MAJOR:-0}" -ge 21 ] || { echo "Java 21+ required." >&2; exit 1; }
