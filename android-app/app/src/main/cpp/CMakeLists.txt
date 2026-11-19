cmake_minimum_required(VERSION 3.22.1)
project(avnivo_whisper CXX)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp is fetched by CI into ./whisper.cpp (never committed).
add_subdirectory(whisper.cpp)
set_target_properties(whisper PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(avnivo_whisper SHARED avnivo_whisper_jni.cpp)
target_link_libraries(avnivo_whisper whisper log)
