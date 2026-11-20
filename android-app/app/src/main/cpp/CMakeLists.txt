cmake_minimum_required(VERSION 3.22.1)
project(avnivo_offline CXX)

set(CMAKE_CXX_STANDARD 17)

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
