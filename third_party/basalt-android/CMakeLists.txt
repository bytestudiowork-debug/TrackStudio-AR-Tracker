cmake_minimum_required(VERSION 3.24)

project(trackstudio_basalt_android LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "This wrapper must be configured with the Android NDK toolchain.")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The initial Basalt Android build is arm64-v8a only; got: ${ANDROID_ABI}")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build only the upstream VIO runtime sources required by the Android tracker.
# The Basalt algorithm/source files remain byte-for-byte unchanged. We deliberately
# do not add the upstream top-level CMake project because it also configures
# desktop tools, calibration utilities, dataset readers, and test dependencies.
set(BASALT_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../basalt")

find_package(Eigen3 CONFIG REQUIRED)
find_package(TBB CONFIG REQUIRED)
find_package(OpenCV CONFIG REQUIRED COMPONENTS core imgproc features2d)
find_package(fmt CONFIG REQUIRED)
find_package(magic_enum CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(opengv CONFIG REQUIRED)
find_package(basalt-headers CONFIG REQUIRED)

add_library(basalt SHARED
    "${BASALT_SOURCE_DIR}/src/linearization/landmark_block.cpp"
    "${BASALT_SOURCE_DIR}/src/linearization/linearization_abs_qr.cpp"
    "${BASALT_SOURCE_DIR}/src/linearization/linearization_abs_sc.cpp"
    "${BASALT_SOURCE_DIR}/src/linearization/linearization_base.cpp"
    "${BASALT_SOURCE_DIR}/src/linearization/linearization_rel_sc.cpp"
    "${BASALT_SOURCE_DIR}/src/optical_flow/optical_flow.cpp"
    "${BASALT_SOURCE_DIR}/src/utils/keypoints.cpp"
    "${BASALT_SOURCE_DIR}/src/utils/system_utils.cpp"
    "${BASALT_SOURCE_DIR}/src/utils/time_utils.cpp"
    "${BASALT_SOURCE_DIR}/src/utils/vio_config.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/ba_base.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/landmark_database.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/marg_helper.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/nfr_mapper.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/sc_ba_base.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/sqrt_ba_base.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/sqrt_keypoint_vio.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/sqrt_keypoint_vo.cpp"
    "${BASALT_SOURCE_DIR}/src/vi_estimator/vio_estimator.cpp"
)

target_include_directories(basalt
    PUBLIC
        "${BASALT_SOURCE_DIR}/include"
        ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(basalt
    PUBLIC
        Eigen3::Eigen
        TBB::tbb
        basalt::basalt-headers
        ${OpenCV_LIBS}
    PRIVATE
        fmt::fmt
        magic_enum::magic_enum
        nlohmann_json::nlohmann_json
        opengv
)

target_compile_definitions(basalt PUBLIC
    BASALT_INSTANTIATIONS_DOUBLE
    BASALT_INSTANTIATIONS_FLOAT
    EIGEN_DONT_PARALLELIZE
)

target_compile_options(basalt PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# Upstream's Clang compatibility flag is needed by NDK 27's Clang 18.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang" AND CMAKE_CXX_COMPILER_VERSION VERSION_LESS 20)
    target_compile_options(basalt PRIVATE -frelaxed-template-template-args)
endif()

message(STATUS "Building Basalt VIO core only for Android arm64-v8a")
message(STATUS "Excluded: desktop GUI/tools, calibration apps, dataset readers, ROSbag, and tests")

# TrackStudio-owned JNI smoke bridge. Keep this adapter outside the upstream
# Basalt submodule; it deliberately does not start VIO until calibrated replay
# and estimator lifecycle handling are implemented and tested.
add_library(trackstudio_basalt_jni SHARED
    "${CMAKE_CURRENT_LIST_DIR}/basalt_jni_adapter.cpp"
)
target_compile_features(trackstudio_basalt_jni PRIVATE cxx_std_17)
target_compile_options(trackstudio_basalt_jni PRIVATE -O3 -fno-exceptions -fno-rtti)
target_link_libraries(trackstudio_basalt_jni PRIVATE basalt)
