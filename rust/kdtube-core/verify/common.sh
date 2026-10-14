# Shared by the verify scripts. Source it, don't run it.
#
# Works in bash on Linux, macOS and Windows (Git Bash / MSYS).

set -euo pipefail

VERIFY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$(dirname "$VERIFY_DIR")"
REPO_DIR="$(cd "$CRATE_DIR/../.." && pwd)"
LIB_DIR="$VERIFY_DIR/lib"
OUT_DIR="$VERIFY_DIR/out"
PKG=com/liskovsoft/googlecommon/common/converters/jsonpath
ENGINE_SRC="$REPO_DIR/MediaServiceCore/youtubeapi/src/main/java/$PKG/engine"
HARNESS_SRC="$VERIFY_DIR/java/$PKG/engine"
FIXTURES="$REPO_DIR/MediaServiceCore/youtubeapi/src/test/resources"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1; CP_SEP=';' ;;
  *) IS_WINDOWS=; CP_SEP=':' ;;
esac

# Path in the form the JDK of this platform understands
np() {
  if [ -n "$IS_WINDOWS" ]; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

# The exact versions the app resolves (see SharedModules/constants.gradle)
fetch_libs() {
  mkdir -p "$LIB_DIR"
  local base=https://repo1.maven.org/maven2 a f
  for a in \
      com/jayway/jsonpath/json-path/2.4.0/json-path-2.4.0.jar \
      com/google/code/gson/gson/2.8.2/gson-2.8.2.jar \
      net/minidev/json-smart/2.3/json-smart-2.3.jar \
      net/minidev/accessors-smart/1.2/accessors-smart-1.2.jar \
      org/ow2/asm/asm/5.0.4/asm-5.0.4.jar \
      org/slf4j/slf4j-api/1.7.25/slf4j-api-1.7.25.jar; do
    f="$LIB_DIR/$(basename "$a")"
    [ -s "$f" ] || { echo "downloading $(basename "$a")"; curl -fsSL -o "$f" "$base/$a"; }
  done
}

# Classpath made of the given files/dirs
cp_of() {
  local cp="" p
  for p in "$@"; do cp="$cp${cp:+$CP_SEP}$(np "$p")"; done
  printf '%s' "$cp"
}

JSON_LIBS() {
  cp_of "$LIB_DIR/json-path-2.4.0.jar" "$LIB_DIR/gson-2.8.2.jar" "$LIB_DIR/json-smart-2.3.jar" \
        "$LIB_DIR/accessors-smart-1.2.jar" "$LIB_DIR/asm-5.0.4.jar" "$LIB_DIR/slf4j-api-1.7.25.jar"
}

# Native lib for the machine that runs the tests
build_host_lib() {
  (cd "$CRATE_DIR" && cargo build --release --lib)
  NATIVE_DIR="$CRATE_DIR/target/release"
}

# javac without the noise, but with its exit status
javac_quiet() {
  local out status=0
  out="$("$JAVAC" -nowarn -encoding UTF-8 "$@" 2>&1)" || status=$?
  printf '%s\n' "$out" | grep -v -E '^Note:|^[0-9]+ warning|^$' || true
  return $status
}
