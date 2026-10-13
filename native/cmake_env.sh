# Sourced by the build scripts. Sets CMAKE, CTEST, JOBS, DIST (all safe with spaces in paths).
#   CMAKE=/path/to/cmake   explicit override
#   otherwise: `cmake` from PATH, otherwise the bundled copy in ../tools/cmake (local dev only)
HERE="$(pwd)"   # every caller cd's into native/ before sourcing this
if [ -z "${CMAKE:-}" ]; then
  if command -v cmake >/dev/null 2>&1; then
    CMAKE="$(command -v cmake)"
  elif [ -x "$HERE/../tools/cmake/CMake.app/Contents/bin/cmake" ]; then
    CMAKE="$HERE/../tools/cmake/CMake.app/Contents/bin/cmake"
  else
    echo "cmake not found: install CMake >= 3.16 or set CMAKE=/path/to/cmake" >&2
    exit 1
  fi
fi
if [ -x "$(dirname "$CMAKE")/ctest" ]; then CTEST="$(dirname "$CMAKE")/ctest"; else CTEST=ctest; fi
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
DIST="${GBA_DIST_ROOT:-$HERE/../dist/natives}"
