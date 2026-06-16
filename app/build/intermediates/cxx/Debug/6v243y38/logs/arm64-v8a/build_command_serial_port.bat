@echo off
"C:\\Android\\Sdk\\ndk\\23.1.7779620\\ndk-build.cmd" ^
  "NDK_PROJECT_PATH=null" ^
  "APP_BUILD_SCRIPT=D:\\Gaurav\\MM_MDU_RS485_2025\\MM_MDU_RS485_2025\\ComAssistantUSB\\app\\src\\main\\jni\\Android.mk" ^
  "NDK_APPLICATION_MK=D:\\Gaurav\\MM_MDU_RS485_2025\\MM_MDU_RS485_2025\\ComAssistantUSB\\app\\src\\main\\jni\\Application.mk" ^
  "APP_ABI=arm64-v8a" ^
  "NDK_ALL_ABIS=arm64-v8a" ^
  "NDK_DEBUG=1" ^
  "APP_PLATFORM=android-28" ^
  "NDK_OUT=D:\\Gaurav\\MM_MDU_RS485_2025\\MM_MDU_RS485_2025\\ComAssistantUSB\\app\\build\\intermediates\\cxx\\Debug\\6v243y38/obj" ^
  "NDK_LIBS_OUT=D:\\Gaurav\\MM_MDU_RS485_2025\\MM_MDU_RS485_2025\\ComAssistantUSB\\app\\build\\intermediates\\cxx\\Debug\\6v243y38/lib" ^
  serial_port
