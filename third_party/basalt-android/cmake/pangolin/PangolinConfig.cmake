# Basalt's top-level CMake requires Pangolin for its desktop tools. The Android
# spike builds only the core 'basalt' library, whose link interface does not use
# Pangolin. This package shim satisfies the upstream configure-time lookup.
set(Pangolin_FOUND TRUE)
set(Pangolin_LIBRARIES "")
set(Pangolin_INCLUDE_DIRS "")
