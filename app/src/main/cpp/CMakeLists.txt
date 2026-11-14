# Native part of the answer translator: slimt (an inference engine for Mozilla's Firefox
# Translations models) and a small JNI wrapper, built into libanswertranslator.so.
# slimt is the git submodule in ./slimt (pinned); it brings SentencePiece and ruy with it.
cmake_minimum_required(VERSION 3.22.1)
project(answertranslator CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Only slimt's static library is needed: no tests, Python or Java bindings of its own.
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_JNI OFF CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
# NEON is always there on arm64; slimt uses it for its vectorised softmax and adds.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(USE_NEON ON CACHE BOOL "" FORCE)
endif()

# Strict IEEE float maths, as slimt's own Android builds use: fused multiply-adds or fast-math
# shortcuts change rounding on ARM enough to flip tokens into garbage over a long sentence.
# No absolute paths in the library (F-Droid rebuilds it and must get identical bytes): the
# source tree, the build tree and the NDK are mapped to fixed names, for debug info and for
# __FILE__ in asserts alike; no build ID, which hashes the unstripped library (machine-specific).
# And no unused code in the library.
set(PATH_MAPS "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=/src -ffile-prefix-map=${CMAKE_BINARY_DIR}=/build -ffile-prefix-map=${ANDROID_NDK}=/ndk")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${PATH_MAPS} -ffunction-sections -fdata-sections -fvisibility=hidden -Wno-builtin-macro-redefined")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${PATH_MAPS} -ffp-contract=off -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -Wno-builtin-macro-redefined -Wno-unused-command-line-argument -Wno-nan-infinity-disabled")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--build-id=none")

# slimt's sources include "slimt/*.hh" relative to the top-level source directory, and its
# generated headers (Version.hh, Export.hh) land in the build tree.
set(SLIMT_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/slimt)
include_directories(${SLIMT_SOURCE_DIR} ${CMAKE_BINARY_DIR}/slimt)
add_subdirectory(${SLIMT_SOURCE_DIR} slimt EXCLUDE_FROM_ALL)

add_library(answertranslator SHARED translator_jni.cpp)
target_include_directories(answertranslator PRIVATE ${SLIMT_SOURCE_DIR})
target_link_libraries(answertranslator PRIVATE slimt-static log)
