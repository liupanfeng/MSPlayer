package com.meishe.msplayer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

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

    private SurfaceView mSurfaceview;
    private SurfaceHolder mSurfaceHolder;

    private Camera mCamera;
    /*拍摄参数*/
    private Camera.Parameters mParameters;

    private int mWidth = 1280;
    private int mHeight = 720;
    /*帧率*/
    private int mFrameRate = 30;
    /*比特率*/
    int mBiteRate = 8500 * 1000;

    private static int mYuvQueueSize = 10;

    /*待解码视频缓冲队列，静态成员*/
    public static ArrayBlockingQueue<byte[]> mYUVQueue =
            new ArrayBlockingQueue<byte[]>(mYuvQueueSize);

    private AvcEncoder mAvcCodec;
    /*本地生成视频路径*/
    private String mMp4Path;

    private String mH264Path;

    private Context mContext;

    private int mCaptureState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityCaptureBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        mContext = this;
        mH264Path = PathUtils.getEncodeDir() + File.separator+"test.h264";
        mMp4Path = PathUtils.getEncodeDir() + File.separator + "test.mp4";
        Log.e("lpf", "h264Path-=" + mH264Path + " mp4Path="+ mMp4Path);
        mSurfaceHolder = mBinding.surfaceview.getHolder();
        mSurfaceHolder.addCallback(this);

        initListener();

    }

    private void initListener() {
        mBinding.flTakePhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCaptureState==0){
                    mCaptureState=1;
                    mBinding.ivTakePhoto.setBackgroundResource(R.mipmap.capture_stop_video);

                    //启动编码线程
                    mAvcCodec.StartEncoderThread();

                }else{
                    mCaptureState=0;
                    mBinding.ivTakePhoto.setBackgroundResource(R.mipmap.capture_take_photo);
                    mBinding.flMiddleParent.setVisibility(View.VISIBLE);

                    mAvcCodec.StopThread();
                    h264ToMp4();

                }
            }
        });

        mBinding.ivBackDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!TextUtils.isEmpty(mMp4Path)){
                    File file=new File(mMp4Path);
                    PathUtils.deleteFile(file.getAbsolutePath());
                    mBinding.flMiddleParent.setVisibility(View.GONE);
                }
            }
        });

        mBinding.ivConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent=new Intent(CaptureActivity.this,MSPlayerActivity.class);
                intent.putExtra(Constants.INTENT_KEY_VIDEO_PATH, mMp4Path);
                startActivity(intent);
                finish();
            }
        });
    }


    /**
     * 添加yuv 数据
     *
     * @param data
     * @param length
     */
    private void putYUVData(byte[] data, int length) {
        if (mYUVQueue.size() >= 10) {
            mYUVQueue.poll();
        }
        mYUVQueue.add(data);
        Log.e("lpf", "putYUVData---UVQueue.size()" + mYUVQueue.size());
    }

    private Camera getBackCamera() {
        Camera c = null;
        try {
            //获取Camera的实例
            c = Camera.open(0);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("lpf", "Camera.open---error:" + e.getMessage());
        }
        //获取Camera的实例失败时返回null
        return c;
    }


    private void startCamera(Camera camera) {
        if (camera != null) {
            try {
                camera.setPreviewCallback(this);
                camera.setDisplayOrientation(90);
                if (mParameters == null) {
                    mParameters = camera.getParameters();
                }

                mParameters.setPreviewFormat(ImageFormat.NV21);
                mParameters.setPreviewSize(mWidth, mHeight);
                camera.setParameters(mParameters);
                camera.setPreviewDisplay(mSurfaceHolder);

                camera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Log.e("lpf", "onPreviewFrame==" + data);
        putYUVData(data, data.length);
    }


    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.e("lpf", "---------surfaceCreated------------");
        mCamera = getBackCamera();
        startCamera(mCamera);

        //创建AvEncoder对象
        mAvcCodec = new AvcEncoder(mWidth, mHeight, mFrameRate, mBiteRate, mH264Path);

    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.e("lpf", "---------surfaceChanged------------");
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.e("lpf", "---------surfaceDestroyed------------");
        if (null != mCamera) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;

        }
    }

    private void h264ToMp4() {
        Log.e("lpf", "h264ToMp4 start----------------");
        File file=new File(mH264Path);
        if (!file.exists()){
            Log.e("lpf", "h264 file is not exists");
            return;
        }
        H264TrackImpl h264Track = null;
        try {
            h264Track = new H264TrackImpl(new FileDataSourceImpl(mH264Path));
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("lpf", "H264TrackImpl error:" + e.getMessage());
        }

        Movie movie = new Movie();
        movie.addTrack(h264Track);

        Container container = new DefaultMp4Builder().build(movie);

        FileChannel fc = null;
        try {
            fc = new FileOutputStream(new File(mMp4Path)).getChannel();
            container.writeContainer(fc);
            fc.close();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("lpf", "container.writeContainer error:" + e.getMessage());
        }


    }


}