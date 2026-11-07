# Shared helpers for WoW UI Sync server scripts. Sourced, not run.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/wow-ui-sync-server.jar" ]; then
  JAR="$SCRIPT_DIR/wow-ui-sync-server.jar"
elif [ -f "$SCRIPT_DIR/../wow-ui-sync-server.jar" ]; then
  JAR="$(cd "$SCRIPT_DIR/.." && pwd)/wow-ui-sync-server.jar"
else
  echo "wow-ui-sync-server.jar not found next to this script or one folder up." >&2
  exit 1
fi
JAVA="${JAVA:-java}"
if ! command -v "$JAVA" >/dev/null 2>&1; then
  echo "Java was not found. Install Java 21 (for example: sudo apt install openjdk-21-jre-headless)." >&2
  exit 1
fi
JAVA_MAJOR="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
  echo "Java 21 or newer is required (found Java $JAVA_MAJOR)." >&2
  exit 1
fi
