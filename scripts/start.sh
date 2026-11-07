#!/bin/sh
# Start the server inside the screen session "wowuisync" with an automatic restart loop.
# Attach with: screen -r wowuisync   (detach again with Ctrl+A, then D)
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
SESSION=wowuisync

if [ "$1" != "--loop" ]; then
  if command -v screen >/dev/null 2>&1; then
    if screen -list 2>/dev/null | grep -q "[.]$SESSION[[:space:]]"; then
      echo "The server is already running in screen session '$SESSION'. Attach with: screen -r $SESSION"
      exit 0
    fi
    screen -dmS "$SESSION" "$SCRIPT_DIR/start.sh" --loop "$@"
    echo "Server started in screen session '$SESSION'."
    echo "Attach with: screen -r $SESSION   (detach with Ctrl+A, then D)"
    echo "Stop with:   $SCRIPT_DIR/stop.sh"
    exit 0
  fi
  echo "screen is not installed; running in this terminal. (Install it with: sudo apt install screen)"
else
  shift
fi

DATA_DIR="$("$JAVA" -jar "$JAR" data-dir "$@")" || exit 1
STOP_FLAG="$DATA_DIR/stop.flag"
rm -f "$STOP_FLAG"

while true; do
  "$JAVA" -jar "$JAR" run "$@"
  CODE=$?
  if [ -f "$STOP_FLAG" ]; then
    rm -f "$STOP_FLAG"
    echo "Server stopped."
    exit 0
  fi
  echo "Server exited unexpectedly with code $CODE."
  i=10
  while [ $i -gt 0 ]; do
    printf "\rRestarting in %2d seconds (Ctrl+C to cancel)..." "$i"
    sleep 1
    i=$((i - 1))
  done
  echo
  if [ -f "$STOP_FLAG" ]; then
    rm -f "$STOP_FLAG"
    echo "Server stopped."
    exit 0
  fi
done
