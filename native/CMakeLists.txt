cmake_minimum_required(VERSION 3.20)
project(vct_audio LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "vct_audio uses WASAPI and builds on Windows only (MSVC, or MinGW-w64 for a compile check).")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static C++ runtime: the DLL works without installing the Visual C++ Redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(vct_audio SHARED src/capture.cpp src/devices.cpp)
target_include_directories(vct_audio PUBLIC include)
target_compile_definitions(vct_audio PRIVATE VCT_AUDIO_BUILD UNICODE _UNICODE NOMINMAX _WIN32_WINNT=0x0A00)
# mmdevapi: ActivateAudioInterfaceAsync. avrt: MMCSS thread priority.
target_link_libraries(vct_audio PRIVATE ole32 avrt mmdevapi)
set_target_properties(vct_audio PROPERTIES PREFIX "")

if(MSVC)
    target_compile_options(vct_audio PRIVATE /W4 /permissive- /utf-8)
else()
    target_compile_options(vct_audio PRIVATE -Wall -Wextra)
    target_link_options(vct_audio PRIVATE -static)
endif()
