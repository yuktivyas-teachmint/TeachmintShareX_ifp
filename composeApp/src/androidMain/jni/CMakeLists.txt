cmake_minimum_required(VERSION 3.18)
project(playfair_jni C)

add_library(playfair_jni SHARED
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/sap_hash.c
    playfair_jni.c
)

target_include_directories(playfair_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/playfair
)

# Link math library and Android log
find_library(log-lib log)
target_link_libraries(playfair_jni m ${log-lib})
