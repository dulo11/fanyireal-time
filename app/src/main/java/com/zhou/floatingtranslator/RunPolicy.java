package com.zhou.floatingtranslator;
import android.content.*;
/** Every network boundary consults this policy. Downloads require separate explicit UI action. */
public final class RunPolicy {
 public static final String OFFLINE="offline", HYBRID="hybrid", ONLINE="online";
 public static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("floating_translator",0);}
 public static String mode(Context c){return prefs(c).getString("run_mode",HYBRID);}
 public static boolean offline(Context c){return OFFLINE.equals(mode(c));}
 public static boolean networkAllowed(String mode,boolean consent){return ONLINE.equals(mode)||(HYBRID.equals(mode)&&consent);}
 public static boolean cloud(Context c){return networkAllowed(mode(c),prefs(c).getBoolean("cloud_consent",false));}
 public static boolean localFirst(Context c){return !ONLINE.equals(mode(c));}
 public static boolean localAsr(String id){return "vosk".equals(id)||OfflineAsrModelCatalog.find(id)!=null;}
 public static String blocked(){return "当前模式禁止联网识别/翻译。请准备离线模型，或在离线中心明确允许联网兜底。";}
 public static void requireCloud(Context c){if(!cloud(c))throw new IllegalStateException(blocked());}
 public static void requireDownload(Context c){if(offline(c))throw new IllegalStateException("完全离线模式禁止下载；请先切换混合模式，手动准备模型后切回离线。");}
}
