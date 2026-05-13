#
# Native build for the AV1 streaming upload mode.
#
# Builds three things:
#   1. SVT-AV1 (vendored as a git submodule under SVT-AV1/) as a static lib.
#   2. The thin svtav1_enc wrapper (vendored as a git submodule under
#      svtav1_enc/) compiled directly into our JNI library.
#   3. libav1enc_jni.so – the JNI bridge invoked from the Kotlin
#      Av1Encoder class.
#
cmake_minimum_required(VERSION 3.22)
project(av1enc_jni C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# ---------------------------------------------------------------------------
# SVT-AV1 sub-project
# ---------------------------------------------------------------------------
# We build SVT-AV1 as a static library to avoid shipping a second .so and to
# let the linker drop unreferenced symbols.  The encoder app, decoder app and
# the test suite are all disabled — we only need libSvtAv1Enc.
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)
set(BUILD_APPS                OFF CACHE BOOL "" FORCE)
set(BUILD_DEC                 OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING             OFF CACHE BOOL "" FORCE)
set(REPRODUCIBLE_BUILDS       ON  CACHE BOOL "" FORCE)
set(EXCLUDE_HASH              ON  CACHE BOOL "" FORCE)
set(MINIMAL_BUILD             ON  CACHE BOOL "" FORCE)
set(LOG_QUIET                 ON  CACHE BOOL "" FORCE)
set(RTC_BUILD                 ON  CACHE BOOL "" FORCE)
# The Android NDK toolchain does not always ship clang versions that accept
# the newer Arm v8.2/v9 feature flags.  Restrict to the baseline NEON +
# CRC32 ISAs that every arm64-v8a Android device supports.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ENABLE_NEON           ON  CACHE BOOL "" FORCE)
    set(ENABLE_ARM_CRC32      OFF  CACHE BOOL "" FORCE)
    set(ENABLE_NEON_DOTPROD   OFF CACHE BOOL "" FORCE)
    set(ENABLE_NEON_I8MM      OFF CACHE BOOL "" FORCE)
    set(ENABLE_SVE            OFF CACHE BOOL "" FORCE)
    set(ENABLE_SVE2           OFF CACHE BOOL "" FORCE)
    # For older Android phones (e.g. Xiaomi Redmi 5), disable all ASM
    add_compile_options(-march=armv8-a -mcpu=cortex-a53)
    set(COMPILE_C_ONLY        ON  CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # x86_64 emulator builds — let SVT-AV1 use its NASM SIMD path if NASM is
    # found on the host, otherwise fall back to plain C.
else()
    # 32-bit ABIs (armeabi-v7a, x86) are unsupported by SVT-AV1 (it warns on
    # any non-64-bit pointer size).  Force the C-only path; the wrapper will
    # fail gracefully at runtime if the host is too constrained.
    set(COMPILE_C_ONLY        ON  CACHE BOOL "" FORCE)
endif()
add_subdirectory(SVT-AV1 EXCLUDE_FROM_ALL)
# ---------------------------------------------------------------------------
# JNI library — bundles the svtav1_enc wrapper sources directly.
# ---------------------------------------------------------------------------
add_library(av1enc_jni SHARED
    av1enc_jni.c
    svtav1_enc/svtav1_wrap.c
)
target_include_directories(av1enc_jni PRIVATE
    svtav1_enc
    SVT-AV1/Source/API
)
target_link_libraries(av1enc_jni PRIVATE
    SvtAv1Enc
    log
    android
)
target_compile_options(av1enc_jni PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
