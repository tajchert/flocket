cmake_minimum_required(VERSION 3.22.1)
project(flocket_ghostty LANGUAGES CXX)

# Prebuilt libghostty-vt (see android/scripts/build-ghostty.sh). Its SONAME is "libghostty-vt.so.0",
# which would not be found inside the APK, so link it by file name instead.
set(GHOSTTY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
add_library(ghostty-vt SHARED IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_ROOT}/src/main/jniLibs/${ANDROID_ABI}/libghostty-vt.so
    IMPORTED_NO_SONAME TRUE)

add_library(flocket_ghostty SHARED flocket_ghostty.cpp)
set_target_properties(flocket_ghostty PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(flocket_ghostty PRIVATE ${GHOSTTY_ROOT}/third_party/ghostty-vt/include)
target_compile_options(flocket_ghostty PRIVATE -Wall -Wextra -fvisibility=hidden -O2)
target_link_libraries(flocket_ghostty PRIVATE ghostty-vt)
target_link_options(flocket_ghostty PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
