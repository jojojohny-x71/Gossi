cmake_minimum_required(VERSION 3.22.1)
project(gossi_whisper C CXX)

# Always optimise: whisper is unusably slow without -O3, even in debug builds.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Phones: portable build (ARM flags below). Desktop self-test builds: use the host CPU.
if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product + fp16 instructions: every arm64 phone core since Cortex-A55/A75.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

if (DEFINED WHISPER_SOURCE_DIR)
    add_subdirectory(${WHISPER_SOURCE_DIR} whisper)
else()
    include(FetchContent)
    FetchContent_Declare(whisper
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp
        GIT_TAG v1.7.6
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(whisper)
endif()

add_library(gossi_whisper SHARED whisper_jni.cpp)
target_link_libraries(gossi_whisper PRIVATE whisper)
if (ANDROID)
    target_link_libraries(gossi_whisper PRIVATE log)
endif()
