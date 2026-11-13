package local.jile.v5;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
final class ScreenCapture {
 private final Context context; private final Handler main=new Handler(Looper.getMainLooper());
 private volatile MediaProjection projection; private final AtomicBoolean capturing=new AtomicBoolean();
 ScreenCapture(Context c){context=c;}
 boolean ready(){return projection!=null;}
 void authorize(int code,Intent data){if(projection!=null)projection.stop();MediaProjection p=((MediaProjectionManager)context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)).getMediaProjection(code,data);projection=p;p.registerCallback(new MediaProjection.Callback(){public void onStop(){if(projection==p)projection=null;}},main);}
 void close(){MediaProjection p=projection;projection=null;if(p!=null)p.stop();}
 byte[] capture() throws Exception {
  if(!capturing.compareAndSet(false,true))throw new IllegalStateException("已有屏幕查看请求，请稍后重试");
  try{return captureOnce();}finally{capturing.set(false);}
 }
 private byte[] captureOnce() throws Exception {
  MediaProjection p=projection;if(p==null)throw new IllegalStateException("需要在设备上授权屏幕查看");
  DisplayMetrics metrics=new DisplayMetrics();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
  int width=metrics.widthPixels,height=metrics.heightPixels;
  HandlerThread thread=new HandlerThread("parent-single-screen");thread.start();Handler handler=new Handler(thread.getLooper());
  ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);VirtualDisplay display=null;
  CountDownLatch done=new CountDownLatch(1);AtomicReference<byte[]> output=new AtomicReference<>();AtomicBoolean taken=new AtomicBoolean();
  try{
   reader.setOnImageAvailableListener(r->{try(Image image=r.acquireLatestImage()){if(image==null || !taken.compareAndSet(false,true))return;
    Image.Plane plane=image.getPlanes()[0];int stride=plane.getPixelStride(),padding=plane.getRowStride()-stride*width;
    Bitmap padded=Bitmap.createBitmap(width+padding/stride,height,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(plane.getBuffer());Bitmap cropped=Bitmap.createBitmap(padded,0,0,width,height);
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();cropped.compress(Bitmap.CompressFormat.PNG,100,bytes);output.set(bytes.toByteArray());if(cropped!=padded)cropped.recycle();padded.recycle();
   }catch(RuntimeException ignored){}finally{if(taken.get())done.countDown();}},handler);
   display=p.createVirtualDisplay("家长按需截图",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
   if(!done.await(5,TimeUnit.SECONDS) || output.get()==null)throw new IllegalStateException("屏幕截图未完成，请重新授权后重试");return output.get();
  }finally{if(display!=null)display.release();reader.setOnImageAvailableListener(null,null);reader.close();thread.quitSafely();}
 }
}
