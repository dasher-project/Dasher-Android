# ── Dasher-Android native build ───────────────────────────────────────────
# Builds two shared libraries per ABI:
#   libdasher.so     — DasherCore compiled with its C API (BUILD_CAPI ON)
#   libdasher_jni.so — thin JNI shim that wraps dasher.h for Kotlin
#
# libdasher_jni.so records a NEEDED entry on libdasher.so, so a single
# System.loadLibrary("dasher_jni") brings both up.

cmake_minimum_required(VERSION 3.22.1)
project(dasher_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── DasherCore (as submodule at third_party/DasherCore) ───────────────────
# Produce the C API shared library only; skip its test executables.
set(BUILD_CAPI ON CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(DASHER_WERROR OFF CACHE BOOL "" FORCE)

# cpp/ → main/ → src/ → app/ → repo root, then into third_party/DasherCore.
set(DASHER_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/DasherCore")

if(NOT EXISTS "${DASHER_CORE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "DasherCore submodule missing at ${DASHER_CORE_DIR}. "
        "Run: git submodule update --init --recursive")
endif()

add_subdirectory("${DASHER_CORE_DIR}" "${CMAKE_BINARY_DIR}/DasherCore")

# ── JNI shim ──────────────────────────────────────────────────────────────
add_library(dasher_jni SHARED
    jni_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(dasher_jni PRIVATE
    "${DASHER_CORE_DIR}/Src"
)

# Link the C API surface (declared in dasher.h) + Android platform libs.
target_link_libraries(dasher_jni
    PRIVATE
        dasher          # libdasher.so (NEEDED at runtime)
        ${log-lib}
        ${android-lib}
)
