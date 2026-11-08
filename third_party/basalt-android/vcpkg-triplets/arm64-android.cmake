# Isolated Basalt ARM64 Android triplet.
# The wrapper only sets the required CMake policy before including the stock NDK toolchain.
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 28)
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${CMAKE_CURRENT_LIST_DIR}/../cmake/android-toolchain-policy.cmake")

# Apply CMP0057 to every vcpkg dependency configure, including NDK toolchain includes.
# Match the triplet architecture explicitly in the NDK toolchain. Without this,
# vcpkg can fall back to armeabi-v7a even though the triplet is arm64-android.
set(VCPKG_CMAKE_CONFIGURE_OPTIONS
    -DANDROID_ABI=arm64-v8a
    -DCMAKE_POLICY_DEFAULT_CMP0057=NEW
)
