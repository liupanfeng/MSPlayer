#ifndef DERRYPLAYER_JNICALLBAKCHELPER_H
#define DERRYPLAYER_JNICALLBAKCHELPER_H

#include <jni.h>
#include "video_statel.h"

class JNICallbakcHelper {

private:
    JavaVM *vm = 0;
    JNIEnv *env = 0;
    jobject job;
    jmethodID jmd_prepared;
    jmethodID jmd_onError;
    jmethodID jmd_onProgress; // 播放音频的时间搓回调
public:
    JNICallbakcHelper(JavaVM *vm, JNIEnv *env, jobject job);
    ~JNICallbakcHelper();

    void onPrepared(int thread_mode);
    void onError(int thread_mode, int error_code);

    void onProgress(int thread_mode, int audio_time);

};



#endif //DERRYPLAYER_JNICALLBAKCHELPER_H
