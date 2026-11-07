cmake_minimum_required(VERSION 3.24)

project(trackstudio_basalt_android LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "This wrapper must be configured with the Android NDK toolchain.")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The initial Basalt Android build is arm64-v8a only; got: ${ANDROID_ABI}")
endif()

# Upstream sets STD_CXX_FS=stdc++fs on UNIX. Android's libc++ already provides
# std::filesystem, so satisfy that upstream link item with an empty interface target.
if(NOT TARGET stdc++fs)
    add_library(stdc++fs INTERFACE IMPORTED GLOBAL)
endif()

# Keep upstream source untouched. These are cross-compilation/build options only.
set(Pangolin_DIR "${CMAKE_CURRENT_LIST_DIR}/cmake/pangolin" CACHE PATH "No-GUI config for the Basalt core-only Android build" FORCE)
set(BASALT_BUILD_SHARED_LIB ON CACHE BOOL "Build Basalt as a shared library" FORCE)
set(BASALT_FULLY_STATIC_BINARIES OFF CACHE BOOL "Do not request Linux static executable linking" FORCE)
set(CXX_MARCH "armv8-a" CACHE STRING "Explicit Android arm64 architecture" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "Do not build upstream tests in the Android app" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "Do not build upstream desktop tools" FORCE)

add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../basalt" basalt_upstream)
