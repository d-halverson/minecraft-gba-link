#!/bin/sh
exec "$(dirname "$0")/zig-wrap.sh" x86_64-linux-gnu.2.17 c++ "$@"
