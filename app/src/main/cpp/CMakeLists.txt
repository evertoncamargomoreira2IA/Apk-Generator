cmake_minimum_required(VERSION 3.22.1)
# Always optimise the speech engine, even in debug APKs (debug whisper is ~10x slower).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project(voxwhisper C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# whisper.cpp — MIT licence — https://github.com/ggml-org/whisper.cpp
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(voxwhisper SHARED voxwhisper.cpp)
target_link_libraries(voxwhisper PRIVATE whisper log android)
target_compile_options(voxwhisper PRIVATE -O3)
