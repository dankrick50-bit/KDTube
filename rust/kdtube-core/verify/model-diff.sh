#!/usr/bin/env bash
# Differential test of the whole mapper: the ORIGINAL JsonPathTypeAdapter (taken from the upstream
# commit below) vs the new one on both engines, for every model class of the app and every
# recorded response. See java/.../engine/ModelDiff.java
#
# Uses the classes that gradle has built, so build them first:
#
#   ./gradlew :youtubeapi:compileStbetaDebugJavaWithJavac          (VARIANT=release for ...StbetaRelease...)
#
# Needs: bash, curl, git, cargo, JDK 11+, ANDROID_HOME (any platform's android.jar is fine).
#
#   ./model-diff.sh --minify                       compare, recorded responses as the server sends them
#   ./model-diff.sh                                compare, recorded responses as they are stored
#   MAIN=MemBench ./model-diff.sh scan             the heaviest (document, class) pairs
#   MAIN=MemBench ./model-diff.sh run --doc <file> --class <name> --mode old|java|native
#
# HEAP=14g  VARIANT=debug|release  NOCLASSES=1 (don't append the --classes dirs, e.g. for 'MemBench run')

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

# MediaServiceCore commit the app was pinned to before the mapper was rewritten
UPSTREAM="${UPSTREAM:-c00b74d758c53f73aca5604a022b6de5bbfe6072}"
VARIANT="${VARIANT:-debug}"
FLAVOR="stbeta$(echo "${VARIANT:0:1}" | tr a-z A-Z)${VARIANT:1}"
MAIN="${MAIN:-ModelDiff}"

fetch_libs
build_host_lib

APP_CLASSES="$REPO_DIR/MediaServiceCore/youtubeapi/build/intermediates/javac/$FLAVOR/classes"
APP_KOTLIN="$REPO_DIR/MediaServiceCore/youtubeapi/build/tmp/kotlin-classes/$FLAVOR"
[ -d "$APP_CLASSES" ] || { echo "No compiled classes. Run: ./gradlew :youtubeapi:compileS${FLAVOR:1}JavaWithJavac" >&2; exit 2; }

PROD="$(cp_of "$APP_CLASSES" "$APP_KOTLIN" \
  "$REPO_DIR/SharedModules/sharedutils/build/intermediates/javac/$VARIANT/classes" \
  "$REPO_DIR/SharedModules/sharedutils/build/tmp/kotlin-classes/$VARIANT" \
  "$REPO_DIR/SharedModules/commons-io-2.8.0/build/intermediates/javac/$VARIANT/classes" \
  "$REPO_DIR/MediaServiceCore/mediaserviceinterfaces/build/intermediates/javac/$VARIANT/classes" \
  "$REPO_DIR/SharedModules/j2v8/build/intermediates/javac/$VARIANT/classes")"

# Libs the app's classes refer to. Gradle has them already.
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
gradle_jar() {
  local f; f="$(find "$GRADLE_CACHE/$1" -name "$2" 2>/dev/null | head -1 || true)"
  [ -n "$f" ] || { echo "Not in the gradle cache: $1 $2 (build the app once)" >&2; exit 2; }
  printf '%s' "$f"
}
APP_LIBS="$(cp_of \
  "$(gradle_jar com.squareup.retrofit2/retrofit 'retrofit-2.5.0.jar')" \
  "$(gradle_jar com.squareup.okhttp3/okhttp 'okhttp-3.12.13.jar')" \
  "$(gradle_jar com.squareup.okio/okio 'okio-1.15.0.jar')" \
  "$(gradle_jar io.reactivex.rxjava2/rxjava 'rxjava-2.2.21.jar')" \
  "$(gradle_jar org.reactivestreams/reactive-streams 'reactive-streams-1.0.3.jar')" \
  "$(gradle_jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-1.8.10.jar')" \
  "$(gradle_jar androidx.annotation/annotation 'annotation-1.1.0.jar')" \
  "$(gradle_jar com.github.florianingerl.util/regex 'regex-1.1.11.jar')" \
  "$(gradle_jar com.grack/nanojson 'nanojson-1.7.jar')")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort | tail -1 || true)"
[ -n "$ANDROID_JAR" ] || { echo "android.jar not found. Set ANDROID_HOME." >&2; exit 2; }
ANDROID_JAR="$(np "$ANDROID_JAR")"

# The original mapper, renamed so that it can live next to the new one
GEN="$OUT_DIR/legacy-src/$PKG/typeadapter"
mkdir -p "$GEN"
for n in JsonPathTypeAdapter JsonPathSkipTypeAdapter; do
  git -C "$REPO_DIR/MediaServiceCore" show "$UPSTREAM:youtubeapi/src/main/java/$PKG/typeadapter/$n.java" \
    | sed -e 's/JsonPathSkipTypeAdapter/LegacyJsonPathSkipTypeAdapter/g' -e 's/\bJsonPathTypeAdapter/LegacyJsonPathTypeAdapter/g' \
    > "$GEN/Legacy$n.java"
done

CLASSES="$OUT_DIR/model-diff"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
# android.util.Log of the sdk is a stub that throws. Ours goes first on the classpath.
javac_quiet -cp "$PROD$CP_SEP$(JSON_LIBS)$CP_SEP$APP_LIBS$CP_SEP$ANDROID_JAR" -d "$(np "$CLASSES")" \
  "$VERIFY_DIR/shim/android/util/Log.java" "$GEN"/*.java \
  "$HARNESS_SRC/ModelDiff.java" "$HARNESS_SRC/MemBench.java" "$HARNESS_SRC/Models.java" "$HARNESS_SRC/Fixtures.java"

ARGS=("$@")
if [ "$MAIN" = ModelDiff ] || [ "${1:-}" = scan ]; then
  [[ " $* " == *" --docs "* ]] || ARGS+=(--docs "$(np "$FIXTURES")")
  [ -n "${NOCLASSES:-}" ] || ARGS+=(--classes "$(np "$APP_CLASSES")" --classes "$(np "$APP_KOTLIN")")
fi

# --add-opens: the old mapper calls setAccessible() on everything, including fields of java.lang.String.
# Fine on Android, not allowed on a modern desktop JVM otherwise.
exec "$JAVA" -Dfile.encoding=UTF-8 "-Xmx${HEAP:-8g}" ${JVMOPTS:-} \
  --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
  "-Djava.library.path=$(np "$NATIVE_DIR")" \
  -cp "$(np "$CLASSES")$CP_SEP$PROD$CP_SEP$(JSON_LIBS)$CP_SEP$APP_LIBS$CP_SEP$ANDROID_JAR" \
  "com.liskovsoft.googlecommon.common.converters.jsonpath.engine.$MAIN" "${ARGS[@]}"
