//
//
#include "ms_video_channel.h"

#define STATE_WORK 1
#define STATE_STOP 0


/**
 * 丢包 AVFrame * 原始包 很简单，因为不需要考虑 关键帧
 * @param q
 */
void dropAVFrame(queue<AVFrame *> &q) {
    if (!q.empty()) {
        AVFrame *frame = q.front();
        BaseChannel::releaseAVFrame(&frame);
        q.pop();
    }
}

/**
 * 丢包 AVPacket * 压缩包 考虑关键帧
 * @param q
 */
void dropAVPacket(queue<AVPacket *> &q) {
    while (!q.empty()) {
        AVPacket *pkt = q.front();
        if (pkt->flags != AV_PKT_FLAG_KEY) { // 非关键帧，可以丢弃
            BaseChannel::releaseAVPacket(&pkt);
            q.pop();
        } else {
            break; // 如果是关键帧，不能丢，那就结束
        }
    }
}


VideoChannel::VideoChannel(int stream_index, AVCodecContext *codecContext, AVRational time_base,
                           int fps)
        : BaseChannel(stream_index, codecContext, time_base), fps(fps) {
    frames.setSyncCallback(dropAVFrame);
    packets.setSyncCallback(dropAVPacket);
}

VideoChannel::~VideoChannel() {
    //释放工作
    DELETE(audio_channel);
}

void VideoChannel::stop() {
    pthread_join(pid_video_decode, nullptr);
    pthread_join(pid_video_play, nullptr);

    isPlaying = false;
    packets.setWork(0);
    frames.setWork(0);

    packets.clear();
    frames.clear();
}

void *task_video_decode(void *args) {
    auto *video_channel = static_cast<VideoChannel *>(args);
    video_channel->video_decode();
    return nullptr;
}

/*线程回调函数*/
void *task_video_play(void *args) {
    auto *video_channel = static_cast<VideoChannel *>(args);
    video_channel->video_play();
    return nullptr;
}

/**
 *  把队列里面的压缩包(AVPacket *)取出来，然后解码成（AVFrame * ）原始包 ----> 保存队列
 *  把队列里面的原始包(AVFrame *)取出来， 播放
 */
void VideoChannel::start() {
    isPlaying = true;

    // 队列开始工作了
    packets.setWork(STATE_WORK);
    frames.setWork(STATE_WORK);

    //开线程->视频,取出队列的压缩包 进行解码 解码后的原始包 再push队列中去
    pthread_create(&pid_video_decode, nullptr, task_video_decode, this);

    //开线程：视频，从队列取出原始包，播放
    pthread_create(&pid_video_play, nullptr, task_video_play, this);
}

/**
 * 把队列里面的压缩包(AVPacket *)取出来，然后解码成（AVFrame * ）原始包 ----> 保存队列
 */
void VideoChannel::video_decode() {
    AVPacket *pkt = 0;
    while (isPlaying) {
        if (isPlaying && frames.size() > AV_MAX_SIZE) {
            av_usleep(10 * 000);
            continue;
        }
        /*阻塞式函数*/
        int ret = packets.getQueueAndDel(pkt);
        if (!isPlaying) {
            /*如果关闭了播放跳出循环，releaseAVPacket(&pkt);*/
            break;
        }

        if (!ret) {
            /* 可能是生产数据慢没拿到数据*/
            continue;
        }

        /*
         * 将获取到的 AVPackage* 数据通过avcodec_send_packet 函数丢给ffmpeg缓冲区 解码操作
         * */
        ret = avcodec_send_packet(codecContext, pkt);

        if (ret) {
            break;
        }


        AVFrame *frame = av_frame_alloc();
        /*从ffmpeg缓冲区 获取原始包AVFrame数据，原始数据包yuv420数据*/
        ret = avcodec_receive_frame(codecContext, frame);
        if (ret == AVERROR(EAGAIN)) {
            /*获取视频数据失败，可能是B帧数据需要参考前面视频帧以及后边的视频帧，需要继续获取*/
            continue;
        } else if (ret != 0) {
            if (frame) {
                releaseAVFrame(&frame);
            }
            break;
        }
        /*将原始包放到 帧队列 */
        frames.insertToQueue(frame);
        /*使用完记得释放 否则会造成内容泄漏*/
        av_packet_unref(pkt);
        releaseAVPacket(&pkt);
    }
    /*发生异常释放 指针*/
    av_packet_unref(pkt);
    releaseAVPacket(&pkt);
}

/**
 * 把队列里面的原始包(AVFrame *)取出来播放
 */
void VideoChannel::video_play() {

    AVFrame *frame = nullptr;
    /*接收RGBA数据*/
    uint8_t *dst_data[4];
    /*接收RGBA 数据长度*/
    int dst_linesize[4];

    /*
     * 原始包（YUV数据） 通过libswscale方法进行数据转换 Android屏幕（RGBA数据）
     * dst_data 申请内存   width * height * 4
     * */
    av_image_alloc(dst_data, dst_linesize,
                   codecContext->width, codecContext->height,
                   AV_PIX_FMT_RGBA, 1);
    /*
     * 初始化转换上下文：SwsContext
     * 第七个参数：yuv 转rgba flag ：SWS_FAST_BILINEAR，快速双线性，速度快可能会模糊，
     * 所以选择SWS_BILINEAR普通双线性就好
     * */
    SwsContext *sws_ctx = sws_getContext(
            /*输入环节*/
            codecContext->width,
            codecContext->height,
            /*自动获取 xxx.mp4 的像素格式 AV_PIX_FMT_YUV420P*/
            codecContext->pix_fmt,
            /*输出环节*/
            codecContext->width,
            codecContext->height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR, NULL, NULL, NULL);

    while (isPlaying) {
        int ret = frames.getQueueAndDel(frame);
        if (!isPlaying) {
            /*如果关闭了播放跳出循环，releaseAVPacket(&pkt);*/
            break;
        }
        if (!ret) {
            /* 压缩包加入队列慢，继续*/
            continue;
        }
        /*
         * 将yuv格式数据转换成rgba数据
         * */
        sws_scale(sws_ctx,
                /*输入环节 YUV的数据*/
                  frame->data, frame->linesize,
                  0, codecContext->height,

                /*输出环节:RGBA数据*/
                  dst_data,
                  dst_linesize
        );

        // 音视频同步（根据fps来休眠） FPS间隔时间加入 == 要有延时感觉
        // 0.04是这一帧的真实时间加上延迟时间吧
        // 公式：extra_delay = repeat_pict / (2*fps)
        // 经验值 extra_delay:0.0400000
        double extra_delay = frame->repeat_pict / (2 * fps); // 在之前的编码时，加入的额外延时时间取出来（可能获取不到）
        double fps_delay = 1.0 / fps; // 根据fps得到延时时间（fps25 == 每秒25帧，计算每一帧的延时时间，0.040000）
        double real_delay = fps_delay + extra_delay; // 当前帧的延时时间  0.040000


        // fps间隔时间后的效果，任何播放器都会有

        /*------------------------音视频同步----------------------------------------*/
        double video_time = frame->best_effort_timestamp * av_q2d(time_base);
        double audio_time = audio_channel->audio_time;

        /*判断两个时间差值，一个快一个慢（快的等慢的，慢的快点追）*/
        double time_diff = video_time - audio_time;

        if (time_diff > 0) {
            // 视频时间 > 音频时间： 要等音频，所以控制视频播放慢一点（等音频）
            if (time_diff > 1) {
                /*音频预视频插件很大， 拖动条 特色场景  音频 和 视频 差值很大，我不能睡眠那么久，否则是大Bug*/
                /*如果音频和视频差值很大，稍微睡一下*/
                av_usleep((real_delay * 2) * 1000000);
            } else {   // 说明：0~1之间：音频与视频差距不大，所以可以那（当前帧实际延时时间 + 音视频差值）
                av_usleep((real_delay + time_diff) * 1000000); // 单位是微妙：所以 * 1000000
            }
        }
        if (time_diff < 0) {
            /*丢包：在frames 和 packets 中的队列*/
            /* 视频时间 < 音频时间： 要追音频，所以控制视频播放快一点（追音频） 【丢包】I帧是绝对不能丢 */
            /*经验值 0.05 -0.234454   fabs == 0.234454*/
            if (fabs(time_diff) <= 0.05) { // fabs对负数的操作（对浮点数取绝对值）
                // 多线程（安全 同步丢包）
                frames.sync();
                continue; // 丢完取下一个包
            }
        } else {
            // 百分百同步，这个基本上很难做的
            LOGI("完全同步了");
        }



        /*
         * 将得到的rgba数据进行回调，回调给ANativeWindow进行渲染播放
         * */
        renderCallback(dst_data[0], codecContext->width, codecContext->height, dst_linesize[0]);
        /*释放原始包，已经被渲染完了*/
        releaseAVFrame(&frame);
    }
    /*出现错误，所退出的循环，都要释放frame*/
    releaseAVFrame(&frame);
    isPlaying = false;
    av_free(&dst_data[0]);
    sws_freeContext(sws_ctx);
}

void VideoChannel::setRenderCallback(RenderCallback renderCallback) {
    this->renderCallback = renderCallback;
}

void VideoChannel::setAudioChannel(AudioChannel *audio_channel) {
    this->audio_channel = audio_channel;
}


