# Android-only placeholder: Basalt's desktop executables are not built.
if(NOT TARGET CLI11::CLI11)
    add_library(CLI11::CLI11 INTERFACE IMPORTED)
endif()
