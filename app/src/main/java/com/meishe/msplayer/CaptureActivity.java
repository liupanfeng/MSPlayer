package com.meishe.msplayer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.tracks.h264.H264TrackImpl;
import com.meishe.msplayer.databinding.ActivityCaptureBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;

public class CaptureActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private ActivityCaptureBinding mBinding;

    private SurfaceView surfaceview;
    private SurfaceHolder surfaceHolder;

    private Camera camera;
    /*拍摄参数*/
    private Camera.Parameters parameters;

    private int width = 1280;
    private int height = 720;
    /*帧率*/
    private int frameRate = 30;
    /*比特率*/
    int biterate = 8500 * 1000;

    private static int yuvQueueSize = 10;

    /*待解码视频缓冲队列，静态成员*/
    public static ArrayBlockingQueue<byte[]> YUVQueue =
            new ArrayBlockingQueue<byte[]>(yuvQueueSize);

    private AvcEncoder avcCodec;
    /*本地生成视频路径*/
    private String mp4Path ;

    private String h264Path;

    private Context mContext;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityCaptureBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        mContext = this;
        h264Path = mContext.getCacheDir().getAbsolutePath() + "/test1.h264";
        mp4Path=mContext.getCacheDir().getAbsolutePath()+File.separator + "test.mp4";;
        surfaceHolder = mBinding.surfaceview.getHolder();
        surfaceHolder.addCallback(this);


    }


    /**
     * 添加yuv 数据
     *
     * @param data
     * @param length
     */
    private void putYUVData(byte[] data, int length) {
        if (YUVQueue.size() >= 10) {
            YUVQueue.poll();
        }
        YUVQueue.add(data);
    }

    private Camera getBackCamera() {
        Camera c = null;
        try {
            //获取Camera的实例
            c = Camera.open(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //获取Camera的实例失败时返回null
        return c;
    }


    private void startCamera(Camera camera) {
        if (camera != null) {
            try {
                camera.setPreviewCallback(this);
                camera.setDisplayOrientation(90);
                if (parameters == null) {
                    parameters = camera.getParameters();
                }

                parameters.setPreviewFormat(ImageFormat.NV21);
                parameters.setPreviewSize(width, height);
                camera.setParameters(parameters);
                camera.setPreviewDisplay(surfaceHolder);

                camera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        putYUVData(data, data.length);
    }


    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        camera = getBackCamera();
        startCamera(camera);

        //创建AvEncoder对象
        avcCodec = new AvcEncoder(width, height, frameRate, biterate,h264Path);
        //启动编码线程
        avcCodec.StartEncoderThread();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (null != camera) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
            avcCodec.StopThread();
            h264ToMp4();
        }
    }

    private void h264ToMp4() {
        H264TrackImpl h264Track = null;
        try {
            h264Track = new H264TrackImpl(new FileDataSourceImpl(h264Path));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Movie movie = new Movie();
        movie.addTrack(h264Track);

        Container container = new DefaultMp4Builder().build(movie);

        FileChannel fc = null;
        try {
            fc = new FileOutputStream(new File(mp4Path)).getChannel();
            container.writeContainer(fc);
            fc.close();
        } catch (Exception e) {
            e.printStackTrace();
        }


    }


}