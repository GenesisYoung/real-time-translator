cmake_minimum_required(VERSION 3.24)
project(vct_stt LANGUAGES C CXX)

# whisper.cpp is downloaded at configure time and linked statically into vct_stt, so the result is
# one library (plus the CUDA runtime DLLs, which the CUDA Toolkit installer puts on PATH).
# To build from a local copy instead: -DFETCHCONTENT_SOURCE_DIR_WHISPER=<path to whisper.cpp>.
set(VCT_WHISPER_VERSION 1.8.5)
option(VCT_CUDA "Run Whisper on an NVIDIA GPU (needs the CUDA Toolkit)" ${WIN32})
# 89 = Ada Lovelace (RTX 40 series). Building for one architecture keeps the CUDA build short.
set(VCT_CUDA_ARCHITECTURES "89" CACHE STRING "CUDA architectures to compile for")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_CUDA ${VCT_CUDA} CACHE BOOL "" FORCE)
# Portable CPU code: the DLL must not crash on a CPU without the build machine's instruction sets.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(VCT_CUDA)
    set(CMAKE_CUDA_ARCHITECTURES ${VCT_CUDA_ARCHITECTURES})
endif()

include(FetchContent)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${VCT_WHISPER_VERSION}.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(whisper)

add_library(vct_stt SHARED src/vct_stt.cpp)
target_include_directories(vct_stt PUBLIC include)
target_compile_definitions(vct_stt PRIVATE VCT_STT_BUILD $<$<BOOL:${WIN32}>:NOMINMAX>)
target_link_libraries(vct_stt PRIVATE whisper)
set_target_properties(vct_stt PROPERTIES PREFIX "" CXX_VISIBILITY_PRESET hidden)
if(MSVC)
    target_compile_options(vct_stt PRIVATE /W4 /utf-8)
else()
    target_compile_options(vct_stt PRIVATE -Wall -Wextra)
endif()
