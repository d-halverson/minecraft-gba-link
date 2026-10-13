#!/bin/sh
exec "$(dirname "$0")/zig-wrap.sh" x86_64-windows-gnu cc "$@"
