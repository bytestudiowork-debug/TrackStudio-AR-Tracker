# Isolated Basalt ARM64 Android triplet.
# The wrapper only sets the required CMake policy before including the stock NDK toolchain.
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 28)
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${CMAKE_CURRENT_LIST_DIR}/../cmake/android-toolchain-policy.cmake")

# Apply CMP0057 to every vcpkg dependency configure, including NDK toolchain includes.
set(VCPKG_CMAKE_CONFIGURE_OPTIONS "-DCMAKE_POLICY_DEFAULT_CMP0057=NEW")
