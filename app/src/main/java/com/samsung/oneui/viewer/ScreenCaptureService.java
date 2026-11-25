package com.samsung.oneui.viewer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** One capture per system consent. The projection and foreground service stop after that frame. */
public final class ScreenCaptureService extends Service {
    static final String DATA="projection_data", RECEIVER="receiver", RESULT_PATH="capture_path";
    private HandlerThread thread;
    private Handler worker;
    private ResultReceiver receiver;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private boolean finished;
    private int density;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf(startId);return START_NOT_STICKY;}if(worker!=null)return START_NOT_STICKY;
        receiver=intent.getParcelableExtra(RECEIVER); Intent consent=intent.getParcelableExtra(DATA);
        if(consent==null){stopSelf();return START_NOT_STICKY;}
        try {
            NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            notifications.createNotificationChannel(new NotificationChannel("capture","화면 캡처",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notice=new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("뷰어").setContentText("화면을 캡처하고 있어요.").setOngoing(true).setContentIntent(open).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(46,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(46,notice);
            thread=new HandlerThread("viewer-capture");thread.start();worker=new Handler(thread.getLooper());
            worker.post(() -> begin(consent));
        }catch(Exception e){send(false,null,"화면 캡처를 시작하지 못했습니다.");stopSelf();}
        return START_NOT_STICKY;
    }
    private void begin(Intent consent) {
        try {
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=manager.getMediaProjection(Activity.RESULT_OK,consent);
            if(projection==null)throw new IllegalStateException("화면 공유 권한이 없습니다.");
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){if(!finished)complete(null,"화면 캡처가 취소됐어요.");}
                @Override public void onCapturedContentResize(int width,int height){if(!finished && display!=null)resize(width,height);}
            },worker);
            WindowManager windows=(WindowManager)getSystemService(WINDOW_SERVICE);DisplayMetrics metrics=getResources().getDisplayMetrics();density=metrics.densityDpi;
            android.graphics.Rect bounds;
            if(Build.VERSION.SDK_INT>=30)bounds=windows.getMaximumWindowMetrics().getBounds();else{windows.getDefaultDisplay().getRealMetrics(metrics);bounds=new android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels);}
            final int width=bounds.width(),height=bounds.height();
            worker.postDelayed(() -> {
                if(finished)return;
                try{createReader(width,height);display=projection.createVirtualDisplay("ViewerCapture",reader.getWidth(),reader.getHeight(),density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);}
                catch(Exception e){complete(null,"화면을 캡처하지 못했습니다.");}
            },350);
            worker.postDelayed(() -> complete(null,"캡처할 화면을 받지 못했습니다."),15000);
        }catch(Exception e){complete(null,"화면 캡처를 시작하지 못했습니다.");}
    }
    private void createReader(int width,int height) {
        reader=ImageReader.newInstance(Math.max(1,width),Math.max(1,height),PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(this::frame,worker);
    }
    private void resize(int width,int height) {
        try {
            ImageReader old=reader; createReader(width,height);
            display.resize(reader.getWidth(),reader.getHeight(),density);display.setSurface(reader.getSurface());
            if(old!=null)old.close();
        }catch(Exception e){complete(null,"캡처 화면의 크기를 조절하지 못했습니다.");}
    }
    private void frame(ImageReader source) {
        if(finished || source!=reader)return;
        Image image=null;Bitmap padded=null,bitmap=null;
        try {
            image=source.acquireLatestImage();if(image==null || finished || source!=reader)return;
            Image.Plane plane=image.getPlanes()[0];if(plane.getPixelStride()!=4)throw new IllegalStateException("화면 형식 오류");
            int width=image.getWidth(),height=image.getHeight(),stride=plane.getRowStride();
            padded=Bitmap.createBitmap(stride/4,height,Bitmap.Config.ARGB_8888);
            ByteBuffer buffer=plane.getBuffer();int required=stride*height;
            if(buffer.remaining()<required){ByteBuffer copy=ByteBuffer.allocate(required);copy.put(buffer);copy.rewind();buffer=copy;}
            padded.copyPixelsFromBuffer(buffer);bitmap=Bitmap.createBitmap(padded,0,0,width,height);
            image.close();image=null;
            File directory=new File(getFilesDir(),"captures");directory.mkdirs();File file=new File(directory,"Capture_"+System.currentTimeMillis()+".png");
            try(FileOutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("캡처 저장 실패");}
            getSharedPreferences("samsung_viewer_native_prefs",MODE_PRIVATE).edit().putString(RESULT_PATH,file.getAbsolutePath()).commit();
            complete(file.getAbsolutePath(),null);
        }catch(Exception e){complete(null,"화면 캡처를 저장하지 못했습니다.");}
        finally{if(image!=null)image.close();if(bitmap!=null && bitmap!=padded)bitmap.recycle();if(padded!=null)padded.recycle();}
    }
    private void send(boolean ok,String path,String error){if(receiver!=null){Bundle result=new Bundle();result.putString("path",path);result.putString("error",error);receiver.send(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}}
    private void complete(String path,String error){if(finished)return;finished=true;cleanup();send(path!=null,path,error);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private void cleanup(){if(worker!=null)worker.removeCallbacksAndMessages(null);if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}if(projection!=null){projection.stop();projection=null;}}
    @Override public void onDestroy(){if(worker!=null)worker.post(() -> {finished=true;cleanup();thread.quitSafely();});super.onDestroy();}
}
