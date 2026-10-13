#!/bin/sh
# Configure, build and run every test for the host platform. Usage: ./run_tests.sh [path-to-cmake]
set -e
cd "$(dirname "$0")"
[ -n "${1:-}" ] && CMAKE="$1"
. ./cmake_env.sh
"$CMAKE" -S . -B build
"$CMAKE" --build build -j"$JOBS"
cd build
"$CTEST" --output-on-failure
