cmake_minimum_required(VERSION 3.18.1)

project("msplayer")

# ffmpeg的路径
set(FFMPEG ${CMAKE_SOURCE_DIR}/ffmpeg)
# rtmp的路径
set(RTMP ${CMAKE_SOURCE_DIR}/rtmp)
# 导入ffmpeg的头文件
include_directories(${FFMPEG}/include)


#ffmpeg库指定
set(CMAKE_CXX_FLAGS  "${CMAKE_CXX_FLAGS} -L${FFMPEG}/libs/${CMAKE_ANDROID_ARCH_ABI}")
#rtmp库指定
set(CMAKE_CXX_FLAGS  "${CMAKE_CXX_FLAGS} -L${RTMP}/libs/${CMAKE_ANDROID_ARCH_ABI}")

# 批量导入 源文件
file(GLOB src_files *.cpp)


add_library(
        msplayer
        SHARED
        ${src_files})


find_library(
        log-lib
        log)


target_link_libraries(
        msplayer
        # 忽略顺序的方式，导入
        -Wl,--start-group
        avcodec avfilter avformat avutil swresample swscale
        -Wl,--end-group
        z   #libz.so 是FFMpeg 额外需要的库文件
        rtmp
        android  #ANativeWindow 用来渲染画面
        OpenSLES  #OpenSLES 用来播放PCM格式的音频

        ${log-lib})