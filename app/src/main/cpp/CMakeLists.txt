cmake_minimum_required(VERSION 3.22.1)
project(touch_mux LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(touch_mux SHARED
    touch_mux.cpp
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(touch_mux PRIVATE
    ${log-lib}
)

target_link_options(touch_mux PRIVATE "-Wl,-z,max-page-size=16384")
