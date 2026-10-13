# Linux x64 via Zig (clang + bundled glibc stubs), targeting glibc 2.17 so the .so loads on old distros too.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
get_filename_component(_d "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
set(CMAKE_C_COMPILER "${_d}/zig-cc-linux.sh")
set(CMAKE_CXX_COMPILER "${_d}/zig-cxx-linux.sh")
set(CMAKE_AR "${_d}/zig-ar.sh")
set(CMAKE_RANLIB "${_d}/zig-ranlib.sh")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
