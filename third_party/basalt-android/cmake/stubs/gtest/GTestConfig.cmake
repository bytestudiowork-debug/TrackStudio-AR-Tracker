# Android-only placeholders: upstream test executables are configured but never built.
if(NOT TARGET GTest::gtest)
    add_library(GTest::gtest INTERFACE IMPORTED)
endif()
if(NOT TARGET GTest::gtest_main)
    add_library(GTest::gtest_main INTERFACE IMPORTED)
endif()
