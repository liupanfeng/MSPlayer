#include <jni.h>
#include <string>
#include "ms_video_player.h"
#include "jni_callback.h"
#include <android/native_window_jni.h>
#include "android_log_util.h"

extern "C" {
#include <libavutil/avutil.h>
}

/*得到JavaVM*/
JavaVM *vm = nullptr;
MSPlayer *msPlayer = nullptr;

/*静态初始化异步锁*/
pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
ANativeWindow *window = nullptr;


jint JNI_OnLoad(JavaVM *vm, void *args) {
    //初始化JavaVM
    ::vm = vm;
    return JNI_VERSION_1_6;
}

/**
 * 函数指针实现  渲染工作
 * @param src_data
 * @param width
 * @param height
 * @param src_lineSize
 */
void renderFrame(uint8_t *src_data, int width, int height, int src_lineSize) {
    /*为了线程安全加锁*/
    pthread_mutex_lock(&mutex);
    if (!window) {
        /*出现了问题后，释放锁，避免出现死锁问题*/
        pthread_mutex_unlock(&mutex);
    }

    /*设置窗口的大小，各个属性*/
    ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888);

    /*声明ANativeWindow缓冲区*/
    ANativeWindow_Buffer window_buffer;

    /*如果在渲染的时候被锁住的，就无法渲染需要释放 ，防止出现死锁*/
    if (ANativeWindow_lock(window, &window_buffer, 0)) {
        ANativeWindow_release(window);
        window = 0;
        pthread_mutex_unlock(&mutex);
        return;
    }

    /*
     * 把rgba数据进行字节对齐 将数据丢给window_buffer画面就出来了
     * */
    uint8_t *dst_data = static_cast<uint8_t *>(window_buffer.bits);
    /*目标行大小*/
    int dst_linesize = window_buffer.stride * 4;
    /*
     *  图：一行一行显示 循环遍历高度
     *  视频分辨率：426 * 240
     *  视频分辨率：宽 426
     *  一行数据的大小 426 * 4(rgba8888) = 1704
     *  memcpy(dst_data + i * 1704, src_data + i * 1704, 1704); 直接这样处理会花屏幕
     *  花屏原因：ANativeWindow_Buffer 64字节对齐的算法，  1704无法以64位字节对齐
     *  FFmpeg是默认采用8字节对齐的，他就认为没有问题， 但是ANativeWindow_Buffer他是64字节对齐的，就有问题
     * */
    for (int i = 0; i < window_buffer.height; ++i) {
        /*
         *C库函数 void *memcpy(void *str1, const void *str2, size_t n) 从存储区 str2 复制 n 个字节到存储区 str1。
         * 将src_data + i * src_lineSize存储区的数据 复制dst_linesize个字节 到 dst_data + i * dst_linesize存储区
         * */
        memcpy(dst_data + i * dst_linesize, src_data + i * src_lineSize, dst_linesize);
    }

    /*
     * 解锁后并且刷新window_buffer的数据显示画面
     * */
    ANativeWindow_unlockAndPost(window);

    pthread_mutex_unlock(&mutex);
}

/**
 * 准备播放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_prepareNative(JNIEnv *env, jobject thiz, jstring data_source) {
    const char *data_source_ = env->GetStringUTFChars(data_source, 0);
    auto *helper = new JniUtil(vm, env, thiz); // C++子线程回调 ， C++主线程回调
    msPlayer = new MSPlayer(data_source_, helper);
    msPlayer->setRenderCallback(renderFrame);
    msPlayer->prepare();
    msPlayer->setJNICallback(helper);
    env->ReleaseStringUTFChars(data_source, data_source_);
}


/**
 * 开始播放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_startNative(JNIEnv *env, jobject thiz) {
    if (msPlayer) {
        msPlayer->start();
    }
}

/**
 * 停止播放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_stopNative(JNIEnv *env, jobject thiz) {
    if (msPlayer) {
        msPlayer->stop();
    }
}

/**
 * 释放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_releaseNative(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&mutex);
    // 先释放之前的显示窗口
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }

    pthread_mutex_unlock(&mutex);

    // 释放工作
    DELETE(msPlayer);
    DELETE(vm);
    DELETE(window);

}




/**
 * 将Surface 设置给c++层，用于数据的渲染
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_setSurfaceNative(JNIEnv *env, jobject thiz, jobject surface) {
    pthread_mutex_lock(&mutex);
    /*先释放之前的显示窗口*/
    if (window) {
        ANativeWindow_release(window);
        window = 0;
    }

    /*创建新的窗口用于视频显示*/
    window = ANativeWindow_fromSurface(env, surface);

    pthread_mutex_unlock(&mutex);
}

/**
 * 从底层获取音视频的长度
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_meishe_msplayer_MSPlayer_getDurationNative(JNIEnv *env, jobject thiz) {
    // implement getDurationNative()
    if (msPlayer) {
        return msPlayer->getDuration();
    }
    return 0;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_meishe_msplayer_MSPlayer_seekNative(JNIEnv *env, jobject thiz, jint play_progress) {
    if (msPlayer) {
        msPlayer->seek(play_progress);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_meishe_msplayer_MSPlayer_getPlayState(JNIEnv *env, jobject thiz) {
    if (msPlayer) {
        return msPlayer->getPlayState();
    }
    return false;
}