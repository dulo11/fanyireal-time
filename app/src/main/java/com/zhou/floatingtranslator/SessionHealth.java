package com.zhou.floatingtranslator;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.function.IntSupplier;

/** Session-scoped telemetry. Holding capture never clears the translation queue. */
public final class SessionHealth implements AutoCloseable {
    public interface Listener { void changed(boolean held,String reason); }
    private static final Map<String,SessionHealth> sessions=new LinkedHashMap<>();
    private static volatile long lastInferenceMs=-1,lastSegmentMs=-1,lastAsrWaitMs=-1;
    public static void inference(long millis,long segmentMillis,long queueMillis) { lastInferenceMs=millis;lastSegmentMs=segmentMillis;lastAsrWaitMs=queueMillis; }
    private final Context context; private final String name; private final IntSupplier queue; private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final long started=SystemClock.elapsedRealtime();
    private volatile long audioAt,translationMs=-1,translationStarted;
    private volatile int peak,thermal,pending;
    private volatile String asr="准备中",engine="等待译文",reason="",direction="";
    private volatile boolean held,manual,closed;
    public SessionHealth(Context c,String name,IntSupplier queue,Listener listener) {
        this.context=c.getApplicationContext();this.name=name;this.queue=queue;this.listener=listener;
        synchronized(sessions) { SessionHealth old=sessions.put(name,this); if(old!=null)old.close(); }
        main.post(tick);
    }
    public void audio(int value) { peak=value;audioAt=SystemClock.elapsedRealtime(); }
    public void asr(String value) {asr=value;}
    public void direction(String value) {direction=value;}
    public void beginTranslation() {translationStarted=SystemClock.elapsedRealtime();}
    public void translated(String value) {engine=value;translationMs=Math.max(0,SystemClock.elapsedRealtime()-translationStarted);}
    public boolean held() {return held;}
    static boolean shouldHold(int thermal,boolean lowMemory,int pending,boolean alreadyHeld) {
        return thermal>=PowerManager.THERMAL_STATUS_SEVERE || lowMemory || pending>=(alreadyHeld?4:24);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        thermal=context.getSystemService(PowerManager.class).getCurrentThermalStatus();pending=queue.getAsInt();
        boolean protect=RunPolicy.prefs(context).getBoolean("session_protection",true);
        boolean next=manual || (protect && shouldHold(thermal,info.lowMemory,pending,held));
        String why=manual?"手动暂停采音；已识别内容继续处理":thermal>=PowerManager.THERMAL_STATUS_SEVERE?"设备过热，暂停采音":info.lowMemory?"系统低内存，暂停采音":pending>=4&&next?"翻译积压，暂停采音并继续处理":thermal>=PowerManager.THERMAL_STATUS_MODERATE?"温度升高，建议下次使用省电模式":"";
        reason=why;
        if(held!=next){held=next;listener.changed(next,why);}
        main.postDelayed(this,2000);
    }};
    public static void holdAll(boolean value){synchronized(sessions){for(SessionHealth s:sessions.values()){s.manual=value;s.main.removeCallbacks(s.tick);s.main.post(s.tick);}}}
    public static boolean active(){synchronized(sessions){return !sessions.isEmpty();}}
    public static String summary(Context c) {
        StringBuilder out=new StringBuilder("运行模式：").append(RunPolicy.mode(c)).append("；云服务允许：").append(RunPolicy.cloud(c)).append('\n');
        synchronized(sessions){if(sessions.isEmpty())out.append("当前没有语音会话\n");
            for(SessionHealth s:sessions.values()){
                long age=s.audioAt==0?-1:(SystemClock.elapsedRealtime()-s.audioAt)/1000;
                out.append(s.name).append(" · ").append(s.direction).append(" · 已运行 ").append((SystemClock.elapsedRealtime()-s.started)/1000).append(" 秒\n")
                    .append("输入：").append(age<0?"尚无 PCM 信号 / 系统服务不提供":age+" 秒前，峰值 "+s.peak).append("\n识别：").append(s.asr)
                    .append("\n翻译：").append(s.engine).append("；最近耗时 ").append(s.translationMs<0?"未测":s.translationMs+" ms")
                    .append("\n待处理：").append(s.queue.getAsInt()).append(" 句；温控级别：").append(s.thermal).append("\n采音：").append(s.held?"暂停":"开放")
                    .append(s.reason.isEmpty()?"":" · "+s.reason).append('\n');
            }}
        out.append("最近本地片段时长：").append(lastSegmentMs<0?"未测":lastSegmentMs+" ms").append("；识别排队：").append(lastAsrWaitMs<0?"未测":lastAsrWaitMs+" ms").append("\n最近本地分段推理：").append(lastInferenceMs<0?"未测":lastInferenceMs+" ms（不含采集和排队）");
        return out.toString();
    }
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);synchronized(sessions){if(sessions.get(name)==this)sessions.remove(name);}}
}
