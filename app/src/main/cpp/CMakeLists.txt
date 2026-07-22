cmake_minimum_required(VERSION 3.22.1)
project("jing_camera")

add_library(
    jing_camera
    SHARED
    native-lib.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    jing_camera
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
