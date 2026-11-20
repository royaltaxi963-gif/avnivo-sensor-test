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

# MADLAD-400 3B INT4 translation. Same dlopen-based ONNX Runtime resolution as the NLLB bridge,
# so no second libonnxruntime.so is shipped.
add_library(avnivo_madlad SHARED avnivo_madlad_jni.cpp)
target_include_directories(avnivo_madlad PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(avnivo_madlad log dl)
