#!/bin/sh
exec "$(dirname "$0")/zig-wrap.sh" - ranlib "$@"
