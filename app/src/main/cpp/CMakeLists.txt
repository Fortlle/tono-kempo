cmake_minimum_required(VERSION 3.22.1)
project(rbjni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Siempre optimizado, también en builds debug (el procesamiento es pesado)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# El código de Rubber Band lo descarga el workflow de GitHub Actions en esta carpeta
set(RB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rubberband)

if(NOT EXISTS ${RB_DIR}/single/RubberBandSingle.cpp)
    message(FATAL_ERROR "Falta Rubber Band en ${RB_DIR}. Clona https://github.com/breakfastquay/rubberband en app/src/main/cpp/rubberband")
endif()

add_library(rbjni SHARED
    native.cpp
    ${RB_DIR}/single/RubberBandSingle.cpp
)

target_include_directories(rbjni PRIVATE ${RB_DIR})
target_link_libraries(rbjni log)
