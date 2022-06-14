package com.meishe.msplayer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadPoolExecutor;

import static android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;

/**
 * @author : lpf
 * @FileName: AvcEncoder
 * @Date: 2022/6/12 18:33
 * @Description:
 */
public class AvcEncoder {

    private final static String TAG = "MeidaCodec";

    private int TIMEOUT_USEC = 12000;

    private MediaCodec mMediaCodec;

    private int mWidth;
    private int mHeight;
    private int mFrameRate;

    public byte[] mConfigByte;

    private BufferedOutputStream mOutputStream;

    private boolean isRunning = false;
    private int count = 0;
    public  String mH264Path;


    public AvcEncoder(int width, int height, int framerate, int bitrate,String path) {
        mH264Path =path;
        mWidth = width;
        mHeight = height;
        mFrameRate = framerate;
        // 构建 MediaFormat
        MediaFormat mediaFormat = MediaFormat.
                createVideoFormat("video/avc", width, height);
        /*设置颜色空间*/
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        /*设置比特率 width * height * 5 经验值*/
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5);
        /*设置帧率*/
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        /*关键帧频率的 单位是秒*/
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        try {
            /*创建MediaCodec 编码器*/
            mMediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("lpf","mediaCodec init error:"+e.getMessage());
        }

        mMediaCodec.configure(mediaFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();
        //创建保存编码后数据的文件
        createFile();
    }

    private void createFile() {
        File file=new File(mH264Path);
        if (file.exists()){
            file.delete();
        }
        try {
            mOutputStream =new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.e("lpf","e.getMessage="+e.getMessage());
        }
    }

    private void StopEncoder() {
        try {
            mMediaCodec.stop();
            mMediaCodec.release();
        } catch (Exception e){
            e.printStackTrace();
        }
    }


    public void StopThread(){
        isRunning = false;
        try {
            StopEncoder();
            mOutputStream.flush();
            mOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void StartEncoderThread(){
        Thread thread=new Thread(new Runnable() {
            @Override
            public void run() {
                isRunning = true;
                byte[] input = null;
                long pts = 0;
                long generateIndex = 0;

                while (isRunning) {
                    /*访问MainActivity用来缓冲待解码数据的队列*/
                    if (CaptureActivity.mYUVQueue.size() >0){
                        /*从缓冲队列中取出一帧*/
                        input = CaptureActivity.mYUVQueue.poll();
                        /*一帧yuv420p 的大小申明一个buffer空间*/
                        byte[] yuv420sp = new byte[mWidth * mHeight *3/2];
                        /*把待编码的视频帧转换为YUV420格式*/
                        NV21ToNV12(input,yuv420sp, mWidth, mHeight);
                        input = yuv420sp;

                    }

                    if (input != null) {
                        try {
                            long startMs = System.currentTimeMillis();
                            /*编码器输入缓冲区*/
                            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
                            /*编码器输出缓冲区*/
                            ByteBuffer[] outputBuffers = mMediaCodec.getOutputBuffers();
                            /*timeoutUs < 0 则无限期等待输入缓冲区的可用性*/
                            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                pts = computePresentationTime(generateIndex);
                                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                                inputBuffer.clear();
                                /*把转换后的YUV420格式的视频帧放到编码器输入缓冲区中*/
                                inputBuffer.put(input);
                                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
                                generateIndex += 1;
                            }

                            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            while (outputBufferIndex >= 0) {
                                Log.i("AvcEncoder", "Get H264 Buffer Success! flag = "+
                                        bufferInfo.flags+",pts = "+bufferInfo.presentationTimeUs+"");
                                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                                byte[] outData = new byte[bufferInfo.size];
                                outputBuffer.get(outData);

                                if(bufferInfo.flags == BUFFER_FLAG_CODEC_CONFIG){
                                    mConfigByte = new byte[bufferInfo.size];
                                    mConfigByte = outData;
                                }else if(bufferInfo.flags == BUFFER_FLAG_KEY_FRAME){
                                    byte[] keyframe = new byte[bufferInfo.size + mConfigByte.length];
                                    System.arraycopy(mConfigByte, 0, keyframe, 0, mConfigByte.length);
                                    /*把编码后的视频帧从编码器输出缓冲区中拷贝出来*/
                                    System.arraycopy(outData, 0, keyframe, mConfigByte.length, outData.length);

                                    mOutputStream.write(keyframe, 0, keyframe.length);
                                }else{
                                    //写到文件中
                                    mOutputStream.write(outData, 0, outData.length);
                                }

                                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                                outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            }
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }else {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });

        thread.start();
    }

    /**
     * pts 跟帧率相关的一个数据
     * @param frameIndex
     * @return
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / mFrameRate;
    }

    /**
     * nv21 转成 nv12
     * @param nv21
     * @param nv12
     * @param width
     * @param height
     */
    private void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height) {
        if(nv21 == null || nv12 == null){
            return;
        }
        int framesize = width*height;
        int i = 0,j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for(i = 0; i < framesize; i++){
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j-1] = nv21[j+framesize];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j] = nv21[j+framesize-1];
        }
    }

}
