cmake_minimum_required(VERSION 3.22)
project(horizon_raw_isp LANGUAGES CXX)

add_library(horizon_raw_isp SHARED raw_isp.cpp)
target_compile_features(horizon_raw_isp PRIVATE cxx_std_17)
# Release math and buffering even in the debuggable, ADB-only benchmark APK.
target_compile_options(horizon_raw_isp PRIVATE -O3 -ffp-contract=off -fno-math-errno -Wall -Wextra)
