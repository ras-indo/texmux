# Android CMake build for libtermux-api.so (JNI bridge)
#
# This builds only the shared library for inclusion in the APK.
# Source files are shared with the standalone Termux-native-package build
# at termux-api/native/.
#
# Build output: libtermux-api.so → jniLibs/<abi>/ for System.loadLibrary("termux-api")

cmake_minimum_required(VERSION 3.10.0)
project(termux-api-android C)

# Path to shared C source files (relative to this file's directory)
# CMakeLists.txt: termux-api/app/src/main/cpp/
# Source files:   termux-api/native/
set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native)

add_library(termux-api SHARED
    ${NATIVE_DIR}/termux-api.c
    ${NATIVE_DIR}/termux-api-jni.c
)

target_include_directories(termux-api PRIVATE
    ${NATIVE_DIR}
    # JNI headers provided by NDK automatically
)

target_compile_definitions(termux-api PRIVATE
    # Termux prefix on-device — used by build_json_command() for script paths
    PREFIX="/data/data/texmux.app/files/apps/texmux"
)

target_link_libraries(termux-api PRIVATE
    # Android logging (__android_log_write, etc.)
    log
)
