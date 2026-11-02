package com.zhou.floatingtranslator;
import android.content.Context;
import android.os.*;
import android.speech.tts.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
/** Local voices only. Mutes capture during speaker playback; never compares recognized words. */
public final class LocalNarrator {
 public interface Listener{void pauseCapture();void resumeCapture();}
 private static final Set<Listener> listeners=new CopyOnWriteArraySet<>();
 private static LocalNarrator instance;
 private static volatile boolean speaking;
 private static volatile long mutedUntil;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Context context;
 private TextToSpeech tts;
 private boolean ready,failed;
 private long generation;
 private final ArrayDeque<String[]> queue=new ArrayDeque<>();
 public static void register(Listener l){listeners.add(l);}public static void unregister(Listener l){listeners.remove(l);}
 public static boolean muted(){return speaking||SystemClock.elapsedRealtime()<mutedUntil;}
 public static synchronized LocalNarrator get(Context c){if(instance==null)instance=new LocalNarrator(c);return instance;}
 public static void stopIfCreated(){if(instance!=null)instance.stop();}
 private LocalNarrator(Context c){context=c.getApplicationContext();tts=new TextToSpeech(context,code->main.post(()->{
  ready=code==TextToSpeech.SUCCESS;failed=!ready;if(failed){queue.clear();report("本地 TTS 初始化失败");return;}
  tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){main.post(()->finish(id));}public void onError(String id){main.post(()->{report("播报失败");finish(id);});}});next();
 }));}
 public void speak(String text,String language){main.post(()->{
  if(failed||text==null||text.trim().isEmpty())return;
  int max=TextToSpeech.getMaxSpeechInputLength()-1;
  int pieces=(text.length()+max-1)/max;
  if(queue.size()+pieces>30){report("播报队列已满；译文仍在历史中，请降低速度或关闭自动播报");return;}
  for(int i=0;i<text.length();i+=max)queue.add(new String[]{text.substring(i,Math.min(text.length(),i+max)),language});next();
 });}
 private void next(){
  if(!ready||speaking||queue.isEmpty())return;
  if(muted()){main.postDelayed(this::next,850);return;}
  String[] item=queue.poll();String lang=AsrTranscriptGuard.normalizeTag(item[1]);Voice voice=null;
  if(tts.getVoices()!=null)for(Voice v:tts.getVoices())if(!v.isNetworkConnectionRequired()&&AsrTranscriptGuard.normalizeTag(v.getLocale().getLanguage()).equals(lang)&&(v.getFeatures()==null||!v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))){voice=v;break;}
  if(voice==null||tts.setVoice(voice)!=TextToSpeech.SUCCESS){report("缺少 "+lang+" 本地语音包，请在系统文字转语音设置下载");next();return;}
  speaking=true;String id=Long.toString(++generation);for(Listener l:listeners)l.pauseCapture();report("本地播报："+lang+"，播报期间暂停采音，请结束后再说话");
  if(tts.speak(item[0],TextToSpeech.QUEUE_FLUSH,null,id)!=TextToSpeech.SUCCESS){finish(id);return;}
  main.postDelayed(()->{if(speaking&&id.equals(Long.toString(generation))){tts.stop();report("播报超时，已恢复采音");finish(id);}},60000);
 }
 private void finish(String id){if(!id.equals(Long.toString(generation)))return;speaking=false;mutedUntil=SystemClock.elapsedRealtime()+800;main.postDelayed(()->{if(!speaking){for(Listener l:listeners)l.resumeCapture();next();}},850);}
 private void stop(){main.post(()->{queue.clear();tts.stop();finish(Long.toString(generation));generation++;});}
 private void report(String s){RunPolicy.prefs(context).edit().putString("tts_status",s).apply();}
 public static void translated(Context c,String text,String lang){if(!RunPolicy.prefs(c).getString("output_mode","text").equals("text"))get(c).speak(text,lang);}
}
