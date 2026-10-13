cmake_minimum_required(VERSION 3.21)
project(termux-am-native-package C)

# Build the termux-am-socket binary from pure C — no C++ runtime needed.
add_executable(termux-am-socket am-socket.c)

install(TARGETS termux-am-socket
    RUNTIME DESTINATION bin
    PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
        GROUP_READ GROUP_EXECUTE
)
