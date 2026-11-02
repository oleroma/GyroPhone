cmake_minimum_required(VERSION 3.22.1)
project(gyrobuffer C)

# Reads the gyro's direct channel when the sensor HAL offers it only as a HardwareBuffer
# (gralloc): Java cannot map a HardwareBuffer for reading, so this locks it for the CPU.
add_library(gyrobuffer SHARED gyrobuffer.c)
target_link_libraries(gyrobuffer android nativewindow)
