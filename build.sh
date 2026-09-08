#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
find src/main/java -name "*.java" > build/sources.txt
javac -d build/classes -encoding UTF-8 @build/sources.txt
if [ -d src/main/resources ]; then
    cp -r src/main/resources/. build/classes/
fi
echo "Build OK -> build/classes"
