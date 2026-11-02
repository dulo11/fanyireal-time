package com.zhou.floatingtranslator;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.google.mlkit.common.model.*;
import com.google.mlkit.nl.translate.*;
import java.io.File;
import java.util.concurrent.*;

/** User-started foreground downloads survive leaving their Activity. */
public final class ModelDownloadService extends Service {
    private static final String CHANNEL="offline_downloads";private static final int ID=749;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private OfflineModelStore store;private OfflineSpeechEngine vosk;private volatile boolean closed;
    private String current;private long lastProgress;
    public static volatile boolean active;
    public static void start(Context c){RunPolicy.requireDownload(c);c.startForegroundService(new Intent(c,ModelDownloadService.class));}
    @Override public void onCreate(){super.onCreate();active=true;store=new OfflineModelStore(this);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"离线模型下载",NotificationManager.IMPORTANCE_LOW));}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(ID,notification("正在准备下载任务"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if(current==null)next();return START_NOT_STICKY;
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,ReliabilityActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("浮译 · 离线模型准备")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).build();
    }
    private void progress(String text){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->progress(text));return;}if(closed)return;long now=SystemClock.elapsedRealtime();if(now-lastProgress<1500)return;lastProgress=now;
        getSystemService(NotificationManager.class).notify(ID,notification(text));
        try{DownloadTasks.update(this,current,"running",text);}catch(Exception e){finish(false,e.getMessage());}
    }
    private void next(){if(closed)return;try{
        RunPolicy.requireDownload(this);current=DownloadTasks.next(this);if(current==null){stopSelf();return;}
        DownloadTasks.update(this,current,"running","正在准备");lastProgress=0;progress(current);
        if(current.startsWith("text:")){
            String lang=current.substring(5);
            RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder(lang).build(),new DownloadConditions.Builder().build())
                .addOnSuccessListener(v->finish(true,"文字模型已就绪")).addOnFailureListener(e->finish(false,e.getMessage()));
        }else if(current.equals("vad")){
            worker.execute(()->{File f=VadModelStore.ensure(this,this::progress);main.post(()->finish(f!=null,f!=null?"VAD 已准备":"VAD 下载失败，可继续使用能量切句"));});
        }else if(current.startsWith("vosk:")){
            vosk=new OfflineSpeechEngine(this,current.substring(5),new OfflineSpeechEngine.Callback(){
                public void onStatus(String s){progress(s);}public void onReady(String s){finish(true,s+" 已加载验证");}public void onError(String s){finish(false,s);}public void onPartial(String s){}public void onFinal(String s){}
            });vosk.prepare();
        }else if(store.isInstalled(current)){finish(true,"已安装，无需重复下载");}
        else store.download(OfflineAsrModelCatalog.find(current),new OfflineModelStore.Callback(){
            public void onStatus(String s){progress(s);}public void onProgress(int p,long d,long t){progress((p<0?"":p+"% · ")+OfflineModelStore.human(d));}
            public void onSuccess(File f){finish(true,"安装及文件校验完成");}public void onError(String s){finish(false,s);}
        });
    }catch(Exception e){finish(false,e.getMessage());}}
    private void finish(boolean success,String message){if(closed)return;
        try{if(current!=null)DownloadTasks.update(this,current,success?"done":"failed",message==null?"未知错误":message);}catch(Exception ignored){}
        if(vosk!=null){vosk.close();vosk=null;}current=null;
        if(success)main.post(this::next);else stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){finish(false,"后台下载达到系统时限，请回到运行与恢复中心继续");stopSelf();}
    @Override public void onDestroy(){closed=true;active=false;main.removeCallbacksAndMessages(null);store.close();if(vosk!=null)vosk.close();worker.shutdownNow();
        try{DownloadTasks.recover(this);}catch(Exception ignored){}super.onDestroy();}
}
