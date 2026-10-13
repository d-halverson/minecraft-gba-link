cmake_minimum_required(VERSION 3.16)
project(gbacore C)

# mGBA's own CMakeLists declares cmake_minimum_required(3.1), which CMake >= 4 rejects.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()
set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG")
# mGBA relies on wrapping signed arithmetic (flag computation); modern clang/gcc miscompile it otherwise.
if(NOT MSVC)
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fwrapv")
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_C_STANDARD 11)

# ---- mGBA: minimal static GBA-only build (LIBMGBA_ONLY is implied by EXCLUDE_FROM_ALL) ----
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE OFF CACHE BOOL "" FORCE)
set(USE_FFMPEG OFF CACHE BOOL "" FORCE)
set(USE_ZLIB OFF CACHE BOOL "" FORCE)
set(USE_MINIZIP OFF CACHE BOOL "" FORCE)
set(USE_PNG OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "" FORCE)
set(USE_SQLITE3 OFF CACHE BOOL "" FORCE)
set(USE_ELF OFF CACHE BOOL "" FORCE)
set(USE_LUA OFF CACHE BOOL "" FORCE)
set(USE_LZMA OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "" FORCE)
set(BUILD_QT OFF CACHE BOOL "" FORCE)
set(BUILD_SDL OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_SUITE OFF CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS ON CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(MGBA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mgba)
add_subdirectory(${MGBA_SRC} mgba EXCLUDE_FROM_ALL)

# mGBA's compile definitions (struct layouts depend on them) must be mirrored in our code.
get_target_property(MGBA_DEFS mgba COMPILE_DEFINITIONS)

# ---- gbacore ----
add_library(gbacore_obj STATIC src/gbacore.c)
target_include_directories(gbacore_obj PUBLIC include PRIVATE src)
target_include_directories(gbacore_obj PRIVATE ${MGBA_SRC}/include ${MGBA_SRC}/src ${CMAKE_BINARY_DIR}/mgba/include)
target_compile_definitions(gbacore_obj PRIVATE ${MGBA_DEFS})
target_link_libraries(gbacore_obj PUBLIC mgba)
add_dependencies(gbacore_obj mgba)

add_library(gbacore SHARED src/gbacore.c)
target_include_directories(gbacore PUBLIC include PRIVATE src)
target_include_directories(gbacore PRIVATE ${MGBA_SRC}/include ${MGBA_SRC}/src ${CMAKE_BINARY_DIR}/mgba/include)
target_compile_definitions(gbacore PRIVATE ${MGBA_DEFS} GBACORE_BUILD_SHARED)
target_link_libraries(gbacore PRIVATE mgba)
if(APPLE)
  target_link_options(gbacore PRIVATE -Wl,-dead_strip
    -Wl,-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/src/exports.macos.txt)
elseif(WIN32)
  # gba_* are marked __declspec(dllexport) in gbacore.h
elseif(UNIX)
  target_link_options(gbacore PRIVATE -Wl,--gc-sections -Wl,--strip-all
    -Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map)
endif()
set_target_properties(gbacore PROPERTIES PREFIX "lib")
if(WIN32)
  set_target_properties(gbacore PROPERTIES PREFIX "")
endif()

# ---- tests ----
enable_testing()
add_subdirectory(tests)

# ---- dist copy ----
if(APPLE)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
    set(GBA_PLATFORM macos-aarch64)
  else()
    set(GBA_PLATFORM macos-x86_64)
  endif()
elseif(WIN32)
  set(GBA_PLATFORM windows-x86_64)
else()
  set(GBA_PLATFORM linux-x86_64)
endif()
set(GBA_DIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../dist/natives/${GBA_PLATFORM} CACHE PATH "")
add_custom_command(TARGET gbacore POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E make_directory ${GBA_DIST_DIR}
  COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:gbacore> ${GBA_DIST_DIR}/)
