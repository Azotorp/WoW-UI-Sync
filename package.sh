#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$base"
if [ "${1:-}" != '--no-build' ]; then ./build.sh; fi
python3 package.py
