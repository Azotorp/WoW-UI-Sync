#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$base"
command -v mvn >/dev/null 2>&1 || { echo 'Maven 3.9+ and JDK 21+ are required to compile. The packaged JARs only require Java 21+.' >&2; exit 1; }
mvn -B clean package
python3 -m unittest discover -s tests -v
