cmake_minimum_required(VERSION 3.10.0)
project(termux-api-native-package C)

set(TERMUX_PREFIX "/data/data/com.termux/files/apps/com.termux")
set(TERMUX_PACKAGE_NAME "com.termux")

# Build the termux-api-broadcast executable (no-fork, direct socket client).
# Installed as PREFIX/bin/termux-api (primary) and PREFIX/libexec/termux-api-broadcast (backward compat).
add_executable(termux-api-broadcast
    termux-api-broadcast.c
    termux-api.c
)

target_include_directories(termux-api-broadcast PRIVATE .)
target_compile_definitions(termux-api-broadcast PRIVATE
    PREFIX="${TERMUX_PREFIX}"
)

# Install as bin/termux-api (direct entry point, no shell wrapper needed)
set_target_properties(termux-api-broadcast PROPERTIES OUTPUT_NAME "termux-api")

# Build libtermux-api.so (shared library with JNI bridge)
add_library(termux-api SHARED
    termux-api.c
    termux-api-jni.c
)
target_include_directories(termux-api PUBLIC .)
target_compile_definitions(termux-api PUBLIC
    PREFIX="${TERMUX_PREFIX}"
)
target_link_libraries(termux-api PRIVATE
    # JNI headers provided by the NDK — no explicit -ljni needed
    # Android log library for __android_log_write (if used)
    log
)

# Process .in scripts — substitute @TERMUX_PREFIX@
# termux-api.in is NOT processed here — the binary at PREFIX/bin/termux-api handles everything directly.
set(script_files
    scripts/termux-api-start.in
    scripts/termux-api-stop.in
    scripts/termux-audio-info.in
    scripts/termux-battery-status.in
    scripts/termux-brightness.in
    scripts/termux-call-log.in
    scripts/termux-camera-info.in
    scripts/termux-camera-photo.in
    scripts/termux-clipboard-get.in
    scripts/termux-clipboard-set.in
    scripts/termux-contact-list.in
    scripts/termux-dialog.in
    scripts/termux-download.in
    scripts/termux-fingerprint.in
    scripts/termux-infrared-frequencies.in
    scripts/termux-infrared-transmit.in
    scripts/termux-job-scheduler.in
    scripts/termux-keystore.in
    scripts/termux-location.in
    scripts/termux-media-player.in
    scripts/termux-media-scan.in
    scripts/termux-microphone-record.in
    scripts/termux-nfc.in
    scripts/termux-notification.in
    scripts/termux-notification-channel.in
    scripts/termux-notification-list.in
    scripts/termux-notification-remove.in
    scripts/termux-saf-create.in
    scripts/termux-saf-dirs.in
    scripts/termux-saf-ls.in
    scripts/termux-saf-managedir.in
    scripts/termux-saf-mkdir.in
    scripts/termux-saf-read.in
    scripts/termux-saf-rm.in
    scripts/termux-saf-stat.in
    scripts/termux-saf-write.in
    scripts/termux-sensor.in
    scripts/termux-bluetooth.in
    scripts/termux-share.in
    scripts/termux-sms-inbox.in
    scripts/termux-sms-list.in
    scripts/termux-sms-send.in
    scripts/termux-speech-to-text.in
    scripts/termux-storage-get.in
    scripts/termux-telephony-call.in
    scripts/termux-telephony-cellinfo.in
    scripts/termux-telephony-deviceinfo.in
    scripts/termux-toast.in
    scripts/termux-torch.in
    scripts/termux-tts-engines.in
    scripts/termux-tts-speak.in
    scripts/termux-usb.in
    scripts/termux-vibrate.in
    scripts/termux-volume.in
    scripts/termux-wallpaper.in
    scripts/termux-wifi.in
    scripts/termux-wifi-connectioninfo.in
    scripts/termux-wifi-enable.in
    scripts/termux-wifi-scaninfo.in
    scripts/termux-open.in
)

foreach(file ${script_files})
    get_filename_component(name ${file} NAME)
    string(REGEX REPLACE "\\.in$" "" out_name ${name})
    configure_file(
        ${CMAKE_CURRENT_SOURCE_DIR}/${file}
        ${CMAKE_CURRENT_BINARY_DIR}/scripts/${out_name}
        @ONLY
    )
endforeach()

# Process termux-callback.in
configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/termux-callback.in
    ${CMAKE_CURRENT_BINARY_DIR}/termux-callback
    @ONLY
)

# Fix shebang: inside container, shell is at standard paths, not @TERMUX_PREFIX@/bin/{bash,sh}
file(GLOB_RECURSE GENERATED_SCRIPTS "${CMAKE_CURRENT_BINARY_DIR}/scripts/*")
foreach(generated ${GENERATED_SCRIPTS})
    file(READ ${generated} content)
    string(REPLACE "#!${TERMUX_PREFIX}/bin/bash" "#!/bin/bash" content "${content}")
    string(REPLACE "#!${TERMUX_PREFIX}/bin/sh" "#!/bin/sh" content "${content}")
    file(WRITE ${generated} "${content}")
endforeach()
# Fix termux-callback too
file(READ ${CMAKE_CURRENT_BINARY_DIR}/termux-callback content)
string(REPLACE "#!${TERMUX_PREFIX}/bin/bash" "#!/bin/bash" content "${content}")
string(REPLACE "#!${TERMUX_PREFIX}/bin/sh" "#!/bin/sh" content "${content}")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/termux-callback "${content}")

# Installation rules
# termux-api-broadcast → PREFIX/bin/termux-api (direct entry point, no fork)
install(TARGETS termux-api-broadcast
    RUNTIME DESTINATION bin
    PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
        WORLD_READ WORLD_EXECUTE
)

# Also install to libexec/ with original name for backward compatibility
install(FILES $<TARGET_FILE:termux-api-broadcast>
    DESTINATION libexec
    RENAME termux-api-broadcast
    PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
        WORLD_READ WORLD_EXECUTE
)

install(TARGETS termux-api
    LIBRARY DESTINATION lib
)

install(DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/scripts/
    DESTINATION bin
    FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
        WORLD_READ WORLD_EXECUTE
)

install(FILES ${CMAKE_CURRENT_BINARY_DIR}/termux-callback
    DESTINATION libexec
    PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
        WORLD_READ WORLD_EXECUTE
)

install(FILES termux-api.h
    DESTINATION include
)
