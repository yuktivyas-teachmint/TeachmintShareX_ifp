cmake_minimum_required(VERSION 3.22.1)
project(miracast-rtp-receiver)

# Set C++ standard to C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Enable verbose output for debugging
set(CMAKE_VERBOSE_MAKEFILE ON)

# Add source files
add_library(miracast-rtp-receiver SHARED
    # Native RTP receiver implementation
    src/main/cpp/miracast/rtp_receiver.cpp
    src/main/cpp/miracast/rtp_parser.cpp
    src/main/cpp/miracast/ts_demuxer.cpp
    src/main/cpp/miracast/jni_bridge.cpp
)

# Include directories
target_include_directories(miracast-rtp-receiver PRIVATE
    src/main/cpp/miracast/include
)

# Link libraries
target_link_libraries(miracast-rtp-receiver
    # Android log library for logging
    log
    # Android native support
    android
)

# F-012: Security hardening flags for native code that processes untrusted network data
set_target_properties(miracast-rtp-receiver PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Compiler flags for optimization, debugging, and security
target_compile_options(miracast-rtp-receiver PRIVATE
    -Wall
    -Wextra
    -O3  # High optimization for performance
    -fvisibility=hidden  # Hide symbols by default
    -fstack-protector-strong  # Stack canaries for buffer overflow detection
    -D_FORTIFY_SOURCE=2  # Runtime buffer overflow checks
    # Note: Exceptions enabled for error handling in JNI bridge
)

# Linker hardening flags
target_link_options(miracast-rtp-receiver PRIVATE
    -Wl,-z,relro  # Read-only relocations
    -Wl,-z,now    # Immediate binding (full RELRO)
)

# ---------------------------------------------------------------------------
# AirPlay: FairPlay decryption JNI library (playfair)
# ---------------------------------------------------------------------------
add_library(playfair_jni SHARED
    src/main/jni/playfair/playfair.c
    src/main/jni/playfair/omg_hax.c
    src/main/jni/playfair/hand_garble.c
    src/main/jni/playfair/modified_md5.c
    src/main/jni/playfair/sap_hash.c
    src/main/jni/playfair_jni.c
)

target_include_directories(playfair_jni PRIVATE
    src/main/jni/playfair
)

find_library(log-lib log)
target_link_libraries(playfair_jni m ${log-lib})
