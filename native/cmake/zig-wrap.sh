#!/bin/sh
# Runs zig as a CMake compiler/archiver: zig-wrap.sh <zig-target|-> <subcommand> args...
# zig binary: $ZIG, else ../../tools/zig/zig (local dev), else `zig` on PATH.
here="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${ZIG:-}" ]; then zig="$ZIG"
elif [ -x "$here/../../tools/zig/zig" ]; then zig="$here/../../tools/zig/zig"
else zig=zig; fi
target="$1"; shift
if [ "$target" = "-" ]; then exec "$zig" "$@"; fi
sub="$1"; shift
exec "$zig" "$sub" -target "$target" "$@"
