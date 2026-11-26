cmake_minimum_required(VERSION 3.22.1)
project(avnivo_offline CXX)

set(CMAKE_CXX_STANDARD 17)

# AGP builds the debug variant as CMAKE_BUILD_TYPE=Debug, which compiles with no -O flag at all
# (compiler default -O0). ggml's matrix kernels are then ~100x slower than realtime on device.
# Appending to the per-config variables puts -O3 after the build type's own flags, so it wins;
# passing cppFlags instead lands in CMAKE_CXX_FLAGS, which CMake places *before* the per-config
# flags, leaving -O0 in effect.
foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
  set(CMAKE_C_FLAGS_${config} "${CMAKE_C_FLAGS_${config}} -O3")
  set(CMAKE_CXX_FLAGS_${config} "${CMAKE_CXX_FLAGS_${config}} -O3")
endforeach()

# Android is always a cross build, so ggml takes the `GGML_CPU_ARM_ARCH` branch and otherwise
# compiles for the plain ARMv8-A baseline: no dot-product, no fp16 arithmetic. On a Snapdragon
# 8 Gen 2 the quantised matrix kernels are the whole decode, and `ggml_vdotq_s32` collapses from
# two vmull/vpaddl pairs into one sdot with the dot-product extension. The kernels are selected by
# ggml's getauxval checks at runtime, so this flag does not change which ones execute.
# Requires ARMv8.2-A with dot-product (2018 and later phones).
# Pass -DGGML_CPU_ARM_ARCH= to build the plain baseline instead (used for A/B measurements).
if (ANDROID_ABI STREQUAL "arm64-v8a")
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "ggml: CPU architecture for ARM")
endif()

# whisper.cpp is fetched by CI into ./whisper.cpp (never committed).
add_subdirectory(whisper.cpp)
set_target_properties(whisper PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(avnivo_whisper SHARED avnivo_whisper_jni.cpp)
target_link_libraries(avnivo_whisper whisper log android)

# NLLB runs on the ONNX Runtime that the sherpa-onnx AAR already packages; the C API is resolved
# with dlopen at runtime so no second copy of libonnxruntime.so is linked or shipped.
add_library(avnivo_nllb SHARED avnivo_nllb_jni.cpp)
target_include_directories(avnivo_nllb PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(avnivo_nllb log dl)
