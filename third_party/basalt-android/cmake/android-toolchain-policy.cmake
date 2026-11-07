# Keep the NDK toolchain unchanged; establish the CMake policy it expects before loading it.
cmake_policy(SET CMP0057 NEW)

if(NOT DEFINED ENV{ANDROID_NDK_HOME})
    message(FATAL_ERROR "ANDROID_NDK_HOME must point to the installed Android NDK.")
endif()

include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
