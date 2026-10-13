#!/bin/sh
# Build libgbacore for ONE platform and copy it to <dist>/natives/<platform>/.
#   ./build_target.sh <macos-aarch64|macos-x86_64|windows-x86_64|linux-x86_64> [--test]
# --test additionally runs ctest (only possible when the host can execute the target).
# Environment: CMAKE=<cmake binary>, ZIG=<zig binary> (windows/linux), GBA_DIST_ROOT=<output root>.
set -e
cd "$(dirname "$0")"
. ./cmake_env.sh
PLATFORM="${1:?usage: build_target.sh <macos-aarch64|macos-x86_64|windows-x86_64|linux-x86_64> [--test]}"
RUN_TESTS=0
[ "${2:-}" = "--test" ] && RUN_TESTS=1
OUT="$DIST/$PLATFORM"
BUILD="build-$PLATFORM"

echo "== $PLATFORM"
case "$PLATFORM" in
  macos-aarch64|macos-x86_64)
    ARCH=arm64; [ "$PLATFORM" = macos-x86_64 ] && ARCH=x86_64
    "$CMAKE" -S . -B "$BUILD" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
      -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 "-DGBA_DIST_DIR=$OUT" >/dev/null
    ;;
  windows-x86_64|linux-x86_64)
    "$CMAKE" -S . -B "$BUILD" -DCMAKE_BUILD_TYPE=Release \
      "-DCMAKE_TOOLCHAIN_FILE=$HERE/cmake/zig-$PLATFORM.cmake" "-DGBA_DIST_DIR=$OUT" >/dev/null
    ;;
  *) echo "unknown platform: $PLATFORM" >&2; exit 2 ;;
esac
if [ "$PLATFORM" = windows-x86_64 ]; then
  # the static test helper library is not buildable for Windows (dllimport), only the DLL is
  "$CMAKE" --build "$BUILD" --target gbacore -j"$JOBS"
else
  "$CMAKE" --build "$BUILD" -j"$JOBS"
fi

case "$PLATFORM" in
  macos-*)  # arm64 macOS refuses unsigned code, so ad-hoc sign (harmless for x86_64)
    codesign --force -s - "$OUT/libgbacore.dylib"
    codesign --verify "$OUT/libgbacore.dylib"
    ;;
esac

if [ "$RUN_TESTS" = 1 ]; then
  (cd "$BUILD" && "$CTEST" --output-on-failure -j"$JOBS")
fi
ls -l "$OUT"
