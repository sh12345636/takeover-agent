cmake_minimum_required(VERSION 3.22.1)
project(takeoverd C)

# 输出 libtakeoverd.so（实为 PIE 可执行）到 sourceSets 的 jniLibs/<abi>/：
# jniLibs 由 AGP 标准流程打包（时机可靠，不会像 assets 那样排在 cmake 之后），
# 运行时经 applicationInfo.nativeLibraryDir 取出 → su 拷到 /data/local/tmp 执行。
add_executable(takeoverd takeoverd.c)
set_target_properties(takeoverd PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# 16KB 页对齐（Android 15+ 新机型）；NDK r28+ 默认已带，此处兜底旧 NDK
target_link_options(takeoverd PRIVATE "-Wl,-z,max-page-size=16384")
