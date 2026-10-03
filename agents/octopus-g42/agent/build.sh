#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")" && pwd)"
game_jar="${1:?usage: build.sh /path/to/game-lib.jar}"
classes_dir="$project_root/build/classes"
sources_file="$project_root/build/sources.txt"
output_jar="$project_root/dist/rw-agent-bootstrap.jar"

rm -rf "$classes_dir"
mkdir -p "$classes_dir" "$project_root/dist"
find "$project_root/src" -name '*.java' -printf '"%p"\n' > "$sources_file"
java -m jdk.compiler/com.sun.tools.javac.Main \
  --release 8 \
  -encoding UTF-8 \
  -cp "$game_jar" \
  -d "$classes_dir" \
  "@$sources_file"
java -m jdk.jartool/sun.tools.jar.Main \
  --create \
  --file "$output_jar" \
  --manifest "$project_root/MANIFEST.MF" \
  -C "$classes_dir" . \
  -C "$project_root/resources" .
echo "$output_jar"
