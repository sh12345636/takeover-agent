cmake_minimum_required(VERSION 3.22.1)
project(takeoverd C)

# 输出名按 ABI 区分，直接落到 assets/（APK 内分发，运行时按 SUPPORTED_ABIS 选）
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OUT_NAME "takeoverd_arm64")
else()
    set(OUT_NAME "takeoverd_v7a")
endif()

add_executable(${OUT_NAME} takeoverd.c)
set_target_properties(${OUT_NAME} PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../assets")

# 16KB 页对齐（Android 15+ 新机型）；NDK r28+ 默认已带，此处兜底旧 NDK
target_link_options(${OUT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
