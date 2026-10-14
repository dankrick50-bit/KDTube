#!/usr/bin/env bash
# Differential test of the json engines: the old mapper's behavior (real Jayway JsonPath + Gson)
# vs the new Java engine vs the native engine. See java/.../engine/PathDiff.java
#
# Self-contained. Needs: bash, curl, cargo, JDK 11+ (JAVA_HOME or java/javac on PATH).
# No Android SDK and no gradle.
#
#   ./path-diff.sh                         recorded responses + 300 generated documents
#   ./path-diff.sh --minify                same, recorded responses without the formatting (as the server sends them)
#   ./path-diff.sh --synthetic 2000 --random 500 --seed 7      (any PathDiff option; replaces the defaults)
#
# HEAP=14g ./path-diff.sh    the reference side needs a lot of memory for the big recorded responses

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

fetch_libs
build_host_lib

# The app's own expressions: every string literal that looks like a json path
mkdir -p "$OUT_DIR"
grep -rhoE '"\$[^"]*"' --include='*.java' --include='*.kt' "$REPO_DIR/MediaServiceCore/youtubeapi/src/main" \
  | sed -E 's/^"//; s/"$//' | grep -E '^\$(\.|\[|$)' | sort -u > "$OUT_DIR/paths.txt"
echo "app expressions found: $(wc -l < "$OUT_DIR/paths.txt")"

CLASSES="$OUT_DIR/path-diff"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
javac_quiet -cp "$(JSON_LIBS)" -d "$(np "$CLASSES")" \
  "$ENGINE_SRC"/*.java "$HARNESS_SRC/PathDiff.java" "$HARNESS_SRC/Fixtures.java"

if [ $# -eq 0 ]; then
  set -- --docs "$(np "$FIXTURES")" --synthetic 300 --random 150 --contexts 12
elif [ "$*" = "--minify" ]; then
  set -- --docs "$(np "$FIXTURES")" --minify --synthetic 300 --random 150 --contexts 12
fi

exec "$JAVA" -Dfile.encoding=UTF-8 "-Xmx${HEAP:-8g}" "-Djava.library.path=$(np "$NATIVE_DIR")" \
  -cp "$(np "$CLASSES")$CP_SEP$(JSON_LIBS)" \
  com.liskovsoft.googlecommon.common.converters.jsonpath.engine.PathDiff --paths "$(np "$OUT_DIR/paths.txt")" "$@"
