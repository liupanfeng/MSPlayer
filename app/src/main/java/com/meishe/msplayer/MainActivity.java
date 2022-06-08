package com.meishe.msplayer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.meishe.msplayer.databinding.ActivityMainBinding;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MSPlayer.OnPreparedListener, SeekBar.OnSeekBarChangeListener {

    // Used to load the 'msplayer' library on application startup.


    private ActivityMainBinding mBinding;

    private MSPlayer mMSPlayer;
    private int duration; // 获取native层的总时长
    private boolean isTouch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        requestPermission();

        initListener();


        mBinding.seekBar.setOnSeekBarChangeListener(this);
    }

    private void initListener() {
        mBinding.btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMSPlayer != null) {
                    mMSPlayer.start();
                }
            }
        });

        mBinding.surfaceView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSeekView();
            }
        });

    }

    private void showSeekView() {
        mBinding.llTopContainer.setVisibility(View.VISIBLE);
        mBinding.llBottomContainer.setVisibility(View.VISIBLE);
        mBinding.llBottomContainer.postDelayed(new Runnable() {
            @Override
            public void run() {
                mBinding.llTopContainer.setVisibility(View.GONE);
                mBinding.llBottomContainer.setVisibility(View.GONE);
            }
        },3000);
    }

    private void initPlayer() {
        mMSPlayer = new MSPlayer();
        mMSPlayer.setSurfaceView(mBinding.surfaceView);
        mMSPlayer.setOnPreparedListener(MainActivity.this);

        mMSPlayer.setOnErrorListener(new MSPlayer.OnErrorListener() {
            @Override
            public void onError(String errorCode) {
                Toast.makeText(MainActivity.this, "error:"+errorCode, Toast.LENGTH_SHORT).show();
            }
        });

        String filePath= new File(Environment.
                getExternalStorageDirectory() +
                File.separator + "demo.mp4")
                .getAbsolutePath();

        mMSPlayer.setDataSource(filePath);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;


        Bitmap videoThumb = getVideoThumbnail(filePath,screenWidth,
                screenHeight, MediaStore.Video.Thumbnails.MINI_KIND);

        mBinding.surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (holder==null){
                    return;
                }
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.STROKE);
                /*先锁住画布*/
                Canvas canvas = holder.lockCanvas();
                canvas.drawBitmap(videoThumb,0,0,paint);
                holder.unlockCanvasAndPost(canvas);

            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });

        mMSPlayer.setOnOnProgressListener(new MSPlayer.OnProgressListener() {
            @Override
            public void onProgress(int progress) {
                //  C++层吧audio_time时间搓传递上来
                if (!isTouch) {
                    // C++层是异步线程调用上来的，小心，UI
                    runOnUiThread(new Runnable() {
                        @SuppressLint("SetTextI18n")
                        @Override
                        public void run() {
                            if (duration != 0) {
                                //播放信息 动起来
                                // progress:C++层 ffmpeg获取的当前播放【时间（单位是秒 80秒都有，肯定不符合界面的显示） -> 1分20秒】
                                mBinding.currentPlaytime.setText(getMinutes(progress) + ":" + getSeconds(progress));

                                // 拖动条 动起来 seekBar相对于总时长的百分比
                                // progress == C++层的 音频时间搓  ----> seekBar的百分比
                                // seekBar.setProgress(progress * 100 / duration 以秒计算seekBar相对总时长的百分比);
                                mBinding.seekBar.setProgress(progress * 100 / duration);
                            }
                        }
                    });
                }
            }
        });

        mMSPlayer.prepare();
    }

    @Override
    public void onPrepared() {

        duration = mMSPlayer.getDuration();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (duration != 0) {
                    mBinding.totalDuration.setText(getMinutes(duration) + ":" + getSeconds(duration));
                }

                Log.d("lpf", "init success");
            }
        });
    }


    /**
     * 获取授权
     */
    private void requestPermission() {
        XXPermissions.with(this).permission(Permission.READ_EXTERNAL_STORAGE)
                .permission(Permission.WRITE_EXTERNAL_STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            initPlayer();
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {

                    }
                });
    }


    @Override
    protected void onStop() {
        super.onStop();
        if (mMSPlayer != null) {
            mMSPlayer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mMSPlayer != null) {
            mMSPlayer.release();
        }
    }

    ///////////////////////////进度条//////////////////////////////////
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            // progress 是进度条的进度 （0 - 100） ------>   秒 分 的效果
            mBinding.currentPlaytime.setText(getMinutes(progress * duration / 100)
                    + ":" +
                    getSeconds(progress * duration / 100));
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        isTouch = true;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        isTouch = false;
        int seekBarProgress = seekBar.getProgress(); // 获取当前seekbar当前进度
        // SeekBar1~100  -- 转换 -->  C++播放的时间（61.546565）
        int playProgress = seekBarProgress * duration / 100;
        mMSPlayer.seek(playProgress);
    }

    // 119 ---> 1.多一点点
    private String getMinutes(int duration) { // 给我一个duration，转换成xxx分钟
        int minutes = duration / 60;
        if (minutes <= 9) {
            return "0" + minutes;
        }
        return "" + minutes;
    }

    // 119 ---> 60 59
    private String getSeconds(int duration) { // 给我一个duration，转换成xxx秒
        int seconds = duration % 60;
        if (seconds <= 9) {
            return "0" + seconds;
        }
        return "" + seconds;
    }



    /**
     * 获取视频的第一帧缩略图
     * 先通过ThumbnailUtils来创建一个视频的缩略图，然后再利用ThumbnailUtils来生成指定大小的缩略图。
     * 如果想要的缩略图的宽和高都小于MICRO_KIND，则类型要使用MICRO_KIND作为kind的值，这样会节省内存。
     * @param videoPath 视频的路径
     * @param width 指定输出视频缩略图的宽度
     * @param height 指定输出视频缩略图的高度度
     * @param kind 参照MediaStore.Images(Video).Thumbnails类中的常量MINI_KIND和MICRO_KIND。
     *      其中，MINI_KIND: 512 x 384，MICRO_KIND: 96 x 96
     * @return 指定大小的视频缩略图
     */
    public static Bitmap getVideoThumbnail(String videoPath, int width, int height, int kind) {
        Bitmap bitmap = null;
        // 获取视频的缩略图
        bitmap = ThumbnailUtils.createVideoThumbnail(videoPath, kind); //调用ThumbnailUtils类的静态方法createVideoThumbnail获取视频的截图；
        if(bitmap!= null){
            bitmap = ThumbnailUtils.extractThumbnail(bitmap, width, height,
                    ThumbnailUtils.OPTIONS_RECYCLE_INPUT);//调用ThumbnailUtils类的静态方法extractThumbnail将原图片（即上方截取的图片）转化为指定大小；
        }
        return bitmap;
    }


    /**
     * 获取视频文件第一帧图
     *
     * @param path 视频文件的路径
     * @return Bitmap 返回获取的Bitmap
     */
    public static Bitmap getVideoThumb(String path) {
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        media.setDataSource(path);
        return media.getFrameAtTime();
    }

}