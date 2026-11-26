cmake_minimum_required(VERSION 3.22)
project(horizon_raw_isp LANGUAGES CXX)

add_library(horizon_raw_isp SHARED raw_isp.cpp)
target_compile_features(horizon_raw_isp PRIVATE cxx_std_17)
# Release math and buffering even in the debuggable, ADB-only benchmark APK.
target_compile_options(horizon_raw_isp PRIVATE -O3 -ffp-contract=off -fno-math-errno -Wall -Wextra)

if(RAW_RESIDENT_SDK)
    set(LITERT_BUILD_CONFIG_DISABLE_GPU 0)
    set(LITERT_BUILD_CONFIG_DISABLE_NPU 0)
    configure_file("${RAW_RESIDENT_SDK}/LiteRT/litert/build_common/build_config.h.in"
        "${CMAKE_CURRENT_BINARY_DIR}/litert-include/litert/build_common/build_config.h")
    add_library(horizon_raw_resident SHARED raw_resident.cpp)
    target_include_directories(horizon_raw_resident PRIVATE
        "${CMAKE_CURRENT_BINARY_DIR}/litert-include"
        "${RAW_RESIDENT_SDK}/LiteRT" "${RAW_RESIDENT_SDK}/OpenCL-Headers")
    target_compile_features(horizon_raw_resident PRIVATE cxx_std_17)
    target_compile_options(horizon_raw_resident PRIVATE -O3 -Wall -Wextra)
    target_link_libraries(horizon_raw_resident PRIVATE android dl log
        "${RAW_RESIDENT_SDK}/runtime/${ANDROID_ABI}/libLiteRt.so")
endif()
