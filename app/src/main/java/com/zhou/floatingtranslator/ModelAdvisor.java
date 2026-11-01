package com.zhou.floatingtranslator;
import android.content.Context;
import android.app.ActivityManager;
import java.util.*;
/** Conservative coverage: unverified language pairs are never reported as ready. */
public final class ModelAdvisor {
 public static final String[] LANGS={"zh","en","ja","vi","tl","ms"};
 public static final String[] NAMES={"中文","英语","日语","越南语","菲律宾语","马来语"};
 public static boolean supports(String id,String language){
  String lang=AsrTranscriptGuard.normalizeTag(language);
  if(id==null)return false;
  if(id.startsWith("whisper_"))return Arrays.asList(LANGS).contains(lang)||Arrays.asList("ko","th","id","fr","de","es","ru").contains(lang);
  if(id.equals("sensevoice"))return Arrays.asList("zh","en","ja","ko","yue").contains(lang);
  if(id.equals("reazon_ja")||id.equals("parakeet_ja"))return lang.equals("ja");
  if(id.equals("qwen3_asr_06b"))return Arrays.asList("zh","en","ja","ko","vi").contains(lang);
  return false;
 }
 public static long memory(Context c){ActivityManager.MemoryInfo i=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(i);return i.availMem;}
 public static List<String> order(String a,String b,boolean mixed,String goal,long memory){
  boolean ample=memory>=4L*1024*1024*1024;
  String[] ids=goal.equals("accuracy")&&ample?new String[]{"whisper_medium","qwen3_asr_06b","parakeet_ja","whisper_small","sensevoice","reazon_ja"}:new String[]{"sensevoice","reazon_ja","whisper_small","qwen3_asr_06b","parakeet_ja","whisper_medium"};
  List<String> out=new ArrayList<>();for(String id:ids)if(supports(id,a)&&(!mixed||supports(id,b))&&(!mixed||(!id.equals("reazon_ja")&&!id.equals("parakeet_ja")))&&(ample||!id.equals("whisper_medium")))out.add(id);return out;
 }
 public static List<String> order(Context c,String a,String b,boolean mixed){return order(a,b,mixed,RunPolicy.prefs(c).getString("model_goal","balanced"),memory(c));}
 public static String installed(Context c,String a,String b,boolean mixed,Set<String> failed){try(OfflineModelStore s=new OfflineModelStore(c)){for(String id:order(c,a,b,mixed))if(!failed.contains(id)&&s.isInstalled(id))return id;}return null;}
}
