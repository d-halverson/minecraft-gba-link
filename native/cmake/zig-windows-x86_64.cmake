# Cross toolchain: Windows x64 via Zig's bundled mingw-w64 (static, no mingw runtime DLLs).
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
get_filename_component(_d "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
set(CMAKE_C_COMPILER "${_d}/zig-cc-win.sh")
set(CMAKE_CXX_COMPILER "${_d}/zig-cxx-win.sh")
set(CMAKE_AR "${_d}/zig-ar.sh")
set(CMAKE_RANLIB "${_d}/zig-ranlib.sh")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
