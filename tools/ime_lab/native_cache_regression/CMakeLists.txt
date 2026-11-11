cmake_minimum_required(VERSION 3.22)
project(CyimeNativeCacheRegression LANGUAGES C CXX)
get_filename_component(JNI "${CMAKE_CURRENT_LIST_DIR}/../../../app/src/main/jni" ABSOLUTE)
include("${JNI}/cmake/CyimePatches.cmake")
cyime_apply_patch("${JNI}/librime/deps/marisa-trie" "${JNI}/cmake/MarisaCacheValidation.patch")
cyime_apply_patch("${JNI}/librime" "${JNI}/cmake/RimeTableValidation.patch")
cyime_apply_patch("${JNI}/librime" "${JNI}/cmake/RimeTableBuildRelocation.patch")
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory("${JNI}/librime/deps/marisa-trie" marisa)
target_compile_options(marisa PRIVATE -UNDEBUG)
find_path(BOOST_INCLUDE_DIR boost/version.hpp REQUIRED)
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/rime")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rime/build_config.h" "// Standalone table regression: logging disabled.\n")
add_executable(native_cache_regression regression.cc build_growth.cc
  "${JNI}/librime/src/rime/dict/table.cc"
  "${JNI}/librime/src/rime/dict/string_table.cc"
  "${JNI}/librime/src/rime/dict/mapped_file.cc")
set_target_properties(native_cache_regression PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
target_compile_options(native_cache_regression PRIVATE -UNDEBUG -ffunction-sections -fdata-sections)
target_include_directories(native_cache_regression PRIVATE
  "${CMAKE_CURRENT_BINARY_DIR}" "${BOOST_INCLUDE_DIR}"
  "${JNI}/librime/include" "${JNI}/librime/src" "${JNI}/librime-t9/src"
  "${JNI}/librime/deps/marisa-trie/lib")
target_link_libraries(native_cache_regression PRIVATE marisa)
target_link_options(native_cache_regression PRIVATE -Wl,--gc-sections -Wl,--wrap=munmap)
enable_testing()
add_test(NAME native_cache_regression COMMAND native_cache_regression "${CMAKE_CURRENT_BINARY_DIR}/fixtures")
