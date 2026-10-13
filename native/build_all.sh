#!/bin/sh
# Build libgbacore for every platform this machine can build and copy them into ../dist/natives/<platform>/.
#  - macOS host:  macos-x86_64 + macos-aarch64 (Apple clang -arch), tests run for the host architecture
#  - any host with Zig (ZIG=... or ../tools/zig/zig or `zig` on PATH): windows-x86_64 + linux-x86_64
#    (linux tests run only on a Linux host)
# CI runs the per-target scripts directly (see ../.github/workflows/build.yml).
set -e
cd "$(dirname "$0")"
HOST_OS="$(uname -s)"; HOST_ARCH="$(uname -m)"
if [ "$HOST_OS" = Darwin ]; then
  for p in macos-x86_64 macos-aarch64; do
    t=""
    case "$HOST_ARCH:$p" in arm64:macos-aarch64|x86_64:macos-x86_64) t="--test" ;; esac
    ./build_target.sh "$p" $t
  done
fi
if [ -x ../tools/zig/zig ] || [ -n "${ZIG:-}" ] || command -v zig >/dev/null 2>&1; then
  ./build_target.sh windows-x86_64
  t=""; [ "$HOST_OS" = Linux ] && [ "$HOST_ARCH" = x86_64 ] && t="--test"
  ./build_target.sh linux-x86_64 $t
else
  echo "zig not found: skipping windows-x86_64 and linux-x86_64" >&2
fi
ls -l ../dist/natives/*/
