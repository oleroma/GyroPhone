cmake_minimum_required(VERSION 3.22.1)
project(gyrobuffer C)

# Reads the gyro's direct channel when the sensor HAL offers it only as a HardwareBuffer
# (gralloc): Java cannot map a HardwareBuffer for reading, so this locks it for the CPU.
add_library(gyrobuffer SHARED gyrobuffer.c)
target_link_libraries(gyrobuffer android nativewindow)
# Android 15+ phones with 16 KB memory pages (ColorOS 17 on the OnePlus 15) need every native
# library's segments aligned to 16 KB; older NDKs align to 4 KB unless told.
target_link_options(gyrobuffer PRIVATE "-Wl,-z,max-page-size=16384")
