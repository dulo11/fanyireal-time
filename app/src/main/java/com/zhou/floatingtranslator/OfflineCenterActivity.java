package com.zhou.floatingtranslator;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import com.google.mlkit.common.model.*;
import com.google.mlkit.nl.translate.*;
import com.google.android.gms.tasks.Tasks;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline setup, on-device help, model backups and evidence-based diagnostics. */
public final class OfflineCenterActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private OfflineModelStore store;private LinearLayout root;private Spinner source,target,models;private TextView readiness,report;
 private final List<String> ids=new ArrayList<>();private String reportText="",exportId;
 private volatile boolean destroyed,busy;private FaceOfflineSession session;private OfflineFirstTranslationRouter translator;private SherpaSpeechEngine probe;
 private int generation,recognized,translated;private boolean networkStayedOff;private int refreshGeneration;
 private static final int IMPORT=744,EXPORT=745;
 private interface Job{String run()throws Exception;}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);store=new OfflineModelStore(this);if(state!=null)exportId=state.getString("export_id");
  setTitle("浮译 "+BuildConfig.VERSION_NAME+" · 离线与帮助中心");
  ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,24,32,170);scroll.setBackgroundColor(Color.rgb(17,13,30));scroll.addView(root);setContentView(scroll);
  label("离线与帮助中心",27);label("准备模型 → 选择语言 → 关闭网络 → 开始讲话 → 实时译文",15);
  button("运行模式："+RunPolicy.mode(this),()->new AlertDialog.Builder(this).setTitle("选择运行模式")
   .setItems(new String[]{"完全离线：禁止云服务及模型下载","智能混合：本地优先，联网需另行允许","在线优先：允许上传语音和文字"},(d,i)->{
    if(active())return;RunPolicy.prefs(this).edit().putString("run_mode",new String[]{RunPolicy.OFFLINE,RunPolicy.HYBRID,RunPolicy.ONLINE}[i]).putBoolean("cloud_consent",false).apply();recreate();}).show());
  button("混合模式联网兜底："+(RunPolicy.prefs(this).getBoolean("cloud_consent",false)?"已允许":"未允许"),()->{
   if(active())return;if(!RunPolicy.HYBRID.equals(RunPolicy.mode(this))){show("只有混合模式需要单独设置兜底授权");return;}
   new AlertDialog.Builder(this).setTitle("允许联网兜底？").setMessage("离线失败后，语音或文字将发送到你配置的服务。可随时关闭；完全离线模式始终禁止。")
    .setNegativeButton("禁止",(d,i)->{RunPolicy.prefs(this).edit().putBoolean("cloud_consent",false).apply();recreate();})
    .setPositiveButton("允许",(d,i)->{RunPolicy.prefs(this).edit().putBoolean("cloud_consent",true).apply();recreate();}).show();});
  button("停止正在运行的翻译",()->{
   startService(new Intent(this,TranslationService.class).setAction(TranslationService.ACTION_STOP));
   startService(new Intent(this,RootCallTranslationService.class).setAction(TranslationService.ACTION_STOP));show("已发送停止请求；稍后可切换模式");});
  label("双方语言与模型推荐",21);
  source=spinner(ModelAdvisor.NAMES,RunPolicy.prefs(this).getInt("source_index",2));target=spinner(ModelAdvisor.NAMES,RunPolicy.prefs(this).getInt("target_index",0));
  button("应用双方语言与自动引擎",()->{
   if(active())return;if(from().equals(to())){show("双方语言不能相同");return;}
   RunPolicy.prefs(this).edit().putInt("source_index",source.getSelectedItemPosition()).putInt("target_index",target.getSelectedItemPosition())
    .putInt("face_source_index",source.getSelectedItemPosition()).putInt("face_target_index",target.getSelectedItemPosition())
    .putString("asr_mode","auto").putString("engine_id","auto").putString("language_mode",SherpaSpeechEngine.LANG_AUTO).putBoolean("auto_language_enabled",true).apply();
   show("实时翻译语言已应用；面对面页面可选双方语言或自动双向。固定来源不会被自动更换。");refresh();});
  button("模型偏好："+RunPolicy.prefs(this).getString("model_goal","balanced"),()->new AlertDialog.Builder(this).setTitle("自动推荐偏好")
   .setItems(new String[]{"均衡","准确率优先（大模型更耗内存）","速度 / 省电"},(d,i)->{
    if(active())return;RunPolicy.prefs(this).edit().putString("model_goal",new String[]{"balanced","accuracy","battery"}[i])
     .putString("asr_conversation_profile",new String[]{"balanced","accuracy","low_latency"}[i]).apply();recreate();}).show());
  readiness=label("正在读取模型……",14);button("刷新离线就绪状态",this::refresh);button("一键准备双方离线翻译",this::preparePair);
  button("详细模型中心：下载 / 删除 / 缓存",()->open(ModelManagerActivity.class));
  label("模型校验、修复与备份",21);List<String> names=new ArrayList<>();for(OfflineAsrModelCatalog.Model m:OfflineAsrModelCatalog.all()){ids.add(m.id);names.add(m.name);}models=spinner(names.toArray(new String[0]),0);
  button("校验所选模型全部文件 SHA-256",()->{String id=model();work(()->{ModelIntegrity.verify(store.modelDir(id),id);return "文件校验通过；这是本地基线，不是官方签名。";});});
  button("旧模型：建立本地校验基线",()->{String id=model();new AlertDialog.Builder(this).setTitle("记录当前模型文件？").setMessage("只记录当前文件的 SHA-256，不能证明旧文件原本没有损坏。建立后请执行加载与推理测试。")
   .setNegativeButton("取消",null).setPositiveButton("建立",(d,i)->work(()->{
    if(!OfflineModelStore.MUTATION.tryLock())throw new IOException("模型正在操作");try{ModelIntegrity.inventory(store.modelDir(id),id);return "已建立当前文件校验基线，请执行加载测试。";}finally{OfflineModelStore.MUTATION.unlock();}})).show();});
  button("测试所选模型加载与推理",this::testModel);
  button("修复所选模型：重新下载安装",this::repair);
  button("导出所选语音模型 ZIP",()->{if(active())return;exportId=model();startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,exportId+"-backup.zip"),EXPORT);});
  button("导入浮译语音模型 ZIP",()->{if(active())return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);});
  label("备份仅包含所选语音模型，不含密钥与聊天记录。ML Kit 不提供模型文件导出，换机后单独准备文字模型。",13);
  label("诊断与断网实测",21);button("一键诊断（报告不上传）",this::diagnose);button("测试麦克风 4 秒",this::testMic);button("断网连续识别 + 翻译实测",this::testOffline);button("停止测试",()->stopTest(true));
  button("复制诊断报告",()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("浮译诊断",reportText));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();});
  button("前往系统权限修复",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
  button("ROOT / Shizuku 扫描与通话声音测试",()->open(RootCallActivity.class));
  report=label("检测结果显示在这里。权限正常不等于实际采到了声音。",14);report.setTextIsSelectable(true);
  label("字幕与本地播报",21);
  button("输出方式："+RunPolicy.prefs(this).getString("output_mode","text"),()->new AlertDialog.Builder(this).setTitle("输出方式")
   .setItems(new String[]{"只显示文字","只播报译文","文字 + 本地语音"},(d,i)->{if(active())return;RunPolicy.prefs(this).edit().putString("output_mode",new String[]{"text","voice","both"}[i]).apply();recreate();}).show());
  label("播报只使用已安装的本地 TTS。播报时暂停采音，结束后恢复；请在播报结束后再说话。面对面页面自己的自动播报开关仍可单独开启。",13);
  button("系统本地 TTS 设置",()->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){show("请到系统设置搜索“文字转语音”");}});
  label("字幕背景透明度（下次启动生效）",14);SeekBar alpha=new SeekBar(this);alpha.setMax(255);alpha.setProgress(RunPolicy.prefs(this).getInt("subtitle_alpha",220));root.addView(alpha);
  alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int v,boolean u){}public void onStopTrackingTouch(SeekBar s){RunPolicy.prefs(OfflineCenterActivity.this).edit().putInt("subtitle_alpha",Math.max(30,s.getProgress())).apply();}});
  button("重置字幕位置",()->{RunPolicy.prefs(this).edit().remove("subtitle_y").apply();show("位置已重置，下次启动生效。字幕拖动后自动保存位置。");});
  button("字幕字体与原文 / 译文显示",()->open(MainActivity.class));button("历史搜索、分类和 TXT / SRT 导出",()->open(HistoryActivity.class));
  help();refresh();
 }
 private boolean active(){if(busy||session!=null||probe!=null||RunPolicy.prefs(this).getBoolean("service_running",false)){show("请先停止翻译/测试，并等待当前模型操作结束。");return true;}return false;}
 private String from(){return ModelAdvisor.LANGS[source.getSelectedItemPosition()];}private String to(){return ModelAdvisor.LANGS[target.getSelectedItemPosition()];}private String model(){return ids.get(models.getSelectedItemPosition());}
 private void open(Class<?> c){startActivity(new Intent(this,c));}
 private TextView label(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(size);v.setPadding(0,12,0,10);root.addView(v);return v;}
 private void button(String s,Runnable task){Button b=new Button(this);b.setText(s);b.setAllCaps(false);root.addView(b);b.setOnClickListener(v->task.run());}
 private Spinner spinner(String[] values,int selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));s.setSelection(Math.max(0,Math.min(selected,values.length-1)));root.addView(s);return s;}
 private void show(String s){reportText=s;if(!destroyed&&report!=null)report.setText(s);}
 private void work(Job job){if(active())return;busy=true;show("正在处理，请保持页面打开……");worker.execute(()->{String result;try{result=job.run();}catch(Exception e){result="失败："+e.getMessage();}String text=result;main.post(()->{busy=false;if(!destroyed){show(text);refresh();}});});}
 private void refresh(){
  if(destroyed)return;String dest=to();int version=++refreshGeneration;
  RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(ms->{
   if(destroyed)return;Set<String> langs=new HashSet<>();langs.add("en");for(TranslateRemoteModel m:ms)langs.add(m.getLanguage());
   worker.execute(()->{StringBuilder out=new StringBuilder("文字模型："+langs+"\n");
    for(int i=0;i<ModelAdvisor.LANGS.length;i++){String lang=ModelAdvisor.LANGS[i];if(lang.equals(dest))continue;String id=ModelAdvisor.installed(this,lang,dest,true,Collections.emptySet());boolean text=langs.contains(lang)&&langs.contains(dest);
     out.append(ModelAdvisor.NAMES[i]).append(" ↔ ").append(dest).append(": ").append(id!=null&&text?"文件就绪，待实测":"未就绪").append("；语音=").append(id==null?"缺少双方语言模型":id).append("；文字=").append(text?"已安装":"缺少 "+lang+"/"+dest).append('\n');}
    for(OfflineAsrModelCatalog.Model m:OfflineAsrModelCatalog.all()){long bytes=store.installedBytes(m.id);if(bytes>0)out.append(m.name).append(": ").append(OfflineModelStore.human(bytes)).append(store.isInstalled(m.id)?" · 结构完整":" · 文件缺失").append('\n');}
    out.append("可用空间：").append(OfflineModelStore.human(store.availableBytes())).append("\nML Kit 不公开单模型实际体积，不能将估算显示为实测值。");
    main.post(()->{if(!destroyed&&version==refreshGeneration)readiness.setText(out.toString());});
   });
  }).addOnFailureListener(e->{if(!destroyed)readiness.setText("读取文字模型失败："+e.getMessage());});
 }
 private void preparePair(){
  if(active())return;if(RunPolicy.offline(this)){show("先切换混合模式手动准备模型，完成后切回完全离线。");return;}if(from().equals(to())){show("双方语言不能相同");return;}
  String a=from(),b=to();List<String> list=ModelAdvisor.order(this,a,b,true);if(list.isEmpty()){show("暂无经过语言覆盖确认的推荐模型，请在详细模型中心选择后测试。");return;}
  String installed=ModelAdvisor.installed(this,a,b,true,Collections.emptySet());String id=installed==null?list.get(0):installed;OfflineAsrModelCatalog.Model m=OfflineAsrModelCatalog.find(id);
  new AlertDialog.Builder(this).setTitle("准备 "+a+" ↔ "+b).setMessage("语音："+m.name+"\n"+m.approximateSize+"\n文字：ML Kit "+a+"、"+b+"\n下载需额外解压空间；已安装模型不重复下载。")
   .setNegativeButton("取消",null).setPositiveButton("开始",(d,i)->{busy=true;if(store.isInstalled(id))prepareText(a,b);else store.download(m,downloadCallback(()->prepareText(a,b)));}).show();
 }
 private OfflineModelStore.Callback downloadCallback(Runnable done){return new OfflineModelStore.Callback(){public void onStatus(String s){show(s);}public void onProgress(int p,long d,long t){show("下载 "+(p<0?"":p+"% ")+OfflineModelStore.human(d));}public void onSuccess(File f){done.run();}public void onError(String e){busy=false;show(e);}};}
 private void prepareText(String a,String b){
  show("正在准备双方 ML Kit 文字模型……");worker.execute(()->{String result;
   try{RunPolicy.requireDownload(this);for(String lang:new LinkedHashSet<>(Arrays.asList(a,b)))if(!lang.equals("en"))Tasks.await(RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder().setLanguage(lang).build(),new DownloadConditions.Builder().build()),15,TimeUnit.MINUTES);
    VadModelStore.ensure(this,s->main.post(()->{if(!destroyed)show(s);}));result="模型准备完成。切回完全离线，关闭网络，然后执行断网实测。";
   }catch(Exception e){result="准备未完成："+e.getMessage();}String text=result;main.post(()->{busy=false;if(!destroyed){show(text);refresh();}});
  });
 }
 private void repair(){
  if(active())return;if(RunPolicy.offline(this)){show("重新下载需要先退出完全离线模式");return;}String id=model();
  new AlertDialog.Builder(this).setTitle("重新准备 "+id).setMessage("保留当前模型直到新包验证成功；将清理该模型的下载缓存。")
   .setNegativeButton("取消",null).setPositiveButton("修复",(d,i)->{
    if(active())return;if(!OfflineModelStore.MUTATION.tryLock()){show("模型正在操作");return;}
    try{for(String suffix:new String[]{".tar.bz2.ready",".tar.bz2.part",".tar.bz2.part.validator"}){File f=new File(store.baseDir(),id+suffix);if(f.exists()&&!f.delete()){show("无法清理缓存，请检查存储");return;}}}finally{OfflineModelStore.MUTATION.unlock();}
    busy=true;store.download(OfflineAsrModelCatalog.find(id),downloadCallback(()->{busy=false;show("修复安装完成，请运行加载/断网实测");refresh();}));}).show();
 }
 private void testModel(){
  if(active())return;String id=model();if(!store.isInstalled(id)){show("模型未完整安装");return;}
  int token=++generation;show("正在加载所选模型并执行一次静音推理；这不是准确率测试……");
  probe=new SherpaSpeechEngine(this,id,SherpaSpeechEngine.LANG_AUTO,from(),new SherpaSpeechEngine.Callback(){
   public void onStatus(String s){if(token==generation)show(s);}public void onText(String s,String lang){}
   public void onReady(String name){if(token!=generation||probe==null)return;probe.testInference(()->finishProbe(token,"加载和推理成功："+name+"；实际翻译仍需断网讲话测试"),e->finishProbe(token,e));}
   public void onError(String e){finishProbe(token,e);}
  });probe.prepare();main.postDelayed(()->finishProbe(token,"模型测试超时；可尝试更小模型/INT8 并检查内存"),60000);
 }
 private void finishProbe(int token,String text){if(token!=generation||probe==null)return;generation++;probe.close();probe=null;RunPolicy.prefs(this).edit().putString("last_model_test",new Date()+"："+text).apply();show(text);}
 private void diagnose(){String a=from(),b=to();work(()->{
  StringBuilder out=new StringBuilder("浮译 "+BuildConfig.VERSION_NAME+"\n"+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n");
  out.append("模式：").append(RunPolicy.mode(this)).append("；允许云服务：").append(RunPolicy.cloud(this)).append('\n');
  out.append("麦克风权限：").append(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"已授权，采音待实测":"未授权").append("\n悬浮窗：").append(Settings.canDrawOverlays(this));
  out.append("\n系统声音：每次需系统授权；仅授权不能证明有音频\nShizuku：").append(ShizukuShell.describe()).append("；权限=").append(ShizukuShell.hasPermission());
  out.append("\n通话最近测试：").append(RunPolicy.prefs(this).getString("last_call_test","未验证，请进入通话采集页扫描并测试"));
  out.append("\n可用内存：").append(OfflineModelStore.human(ModelAdvisor.memory(this))).append("；存储：").append(OfflineModelStore.human(store.availableBytes()));
  out.append("\n推荐 ASR：").append(ModelAdvisor.installed(this,a,b,true,Collections.emptySet()));
  for(OfflineAsrModelCatalog.Model m:OfflineAsrModelCatalog.all())if(store.installedBytes(m.id)>0){out.append('\n').append(m.name).append(": ");try{ModelIntegrity.verify(store.modelDir(m.id),m.id);out.append("SHA-256 校验通过");}catch(Exception e){out.append(e.getMessage());}}
  out.append("\nML Kit 已安装：en ");for(TranslateRemoteModel m:Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),20,TimeUnit.SECONDS))out.append(m.getLanguage()).append(' ');
  out.append("\n实际翻译引擎：").append(RunPolicy.prefs(this).getString("last_translation_engine","未验证"));
  for(String key:new String[]{"last_model_test","tts_status","last_offline_test"})out.append('\n').append(key).append(": ").append(RunPolicy.prefs(this).getString(key,"未测试"));
  out.append("\n无活动网络：").append(noNetwork()).append("\n未实际执行的项目不标为成功。报告不含密钥和识别原文。");return out.toString();
 });}
 private boolean micPermission(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)return true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},746);show("授权后重新点击测试");return false;}
 private boolean noNetwork(){return getSystemService(ConnectivityManager.class).getActiveNetwork()==null;}
 private void testMic(){if(!micPermission())return;work(()->{
  int size=Math.max(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));AudioRecord r=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);int peak=0;long bytes=0;
  try{if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("AudioRecord 初始化失败");r.startRecording();long end=SystemClock.elapsedRealtime()+4000;short[] buffer=new short[size/2];
   while(!destroyed&&SystemClock.elapsedRealtime()<end){int n=r.read(buffer,0,buffer.length,AudioRecord.READ_NON_BLOCKING);if(n<0)throw new IOException("录音读取错误 "+n);bytes+=n*2L;for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((int)buffer[i]));Thread.sleep(20);}
  }finally{try{r.stop();}catch(Exception ignored){}r.release();}return "麦克风 PCM="+bytes+" 字节，峰值="+peak+(peak>=90?"；检测到音频信号":"；接近静音，请检查输入设备");
 });}
 private void testOffline(){
  if(active()||!micPermission())return;if(!RunPolicy.offline(this)||!noNetwork()){show("请选完全离线，并关闭 Wi-Fi 与移动网络后测试");return;}String id=ModelAdvisor.installed(this,from(),to(),true,Collections.emptySet());if(id==null){show("缺少覆盖双方语言的本地语音模型");return;}
  int token=++generation;recognized=0;translated=0;networkStayedOff=true;translator=new OfflineFirstTranslationRouter(this,from(),to(),TranslationRouter.MLKIT);
  session=new FaceOfflineSession(this,id,from(),true,new FaceOfflineSession.Callback(){
   public void status(String s){if(token==generation)show(s);}
   public void ready(String name){if(token!=generation)return;show("已加载 "+name+"；请连续说至少两句外语，可重复同一句。采集 15 秒后等待译文。");main.postDelayed(()->{if(token==generation){if(session!=null)session.close();main.postDelayed(()->{if(token==generation)stopTest(false);},8000);}},15000);}
   public void result(String text,String language){if(token!=generation||translator==null)return;recognized++;translator.translate(text,language,new OfflineFirstTranslationRouter.Callback(){
    public void onSuccess(String value,String engine){if(token==generation&&engine.contains("ML Kit")&&!value.trim().isEmpty()){translated++;show("识别 "+recognized+" 句，实际本地翻译 "+translated+" 句；继续测试中……");}}
    public void onError(String e){if(token==generation)show(e);}
   });}
   public void error(String e){if(token==generation){stopTest(true);show(e);}}
  });session.start();watchNetwork(token);main.postDelayed(()->{if(token==generation){stopTest(true);show("准备/测试超过 90 秒，请先做模型加载诊断");}},90000);
 }
 private void watchNetwork(int token){if(token!=generation||session==null)return;networkStayedOff&=noNetwork()&&RunPolicy.offline(this);main.postDelayed(()->watchNetwork(token),300);}
 private void stopTest(boolean cancelled){
  if(probe!=null){generation++;probe.close();probe=null;show("模型测试已停止");}
  if(session==null)return;generation++;session.close();session=null;if(translator!=null){translator.close();translator=null;}
  String text=new Date()+"："+(cancelled?"已取消":translated>=2&&networkStayedOff?"通过：无活动网络，连续识别与本地翻译成功":"未通过：需无网络并产生至少两句本地译文")+"；识别="+recognized+"，翻译="+translated;
  RunPolicy.prefs(this).edit().putString("last_offline_test",text).apply();show(text);
 }
 private void help(){
  label("离线帮助中心",24);
  guide("① 新手：安装后如何断网翻译", "1. 设置双方语言并应用。\n2. 点击一键准备，确认推荐模型、体积与空间。\n3. 等待语音模型和双方 ML Kit 文字模型完成。\n4. 授予麦克风与悬浮窗权限。系统内部声音需系统单独授权。\n5. 切到完全离线，打开实时或面对面翻译。\n6. 关闭网络，运行断网实测并说至少两句。",this::preparePair);
  guide("② 自动语言 / 面对面双向", "面对面页面选双方语言或自动双向。对方说外语译成我的语言；我说话译成对方语言。模型必须覆盖双方语言。短句可能误判，设定备用语言或固定输入。重复说话每句翻译；临时字幕更新不等于最终句子合并。",()->open(FaceToFaceActivity.class));
  guide("③ 三种运行模式", "完全离线禁用云 ASR、系统语音服务、在线翻译和自动下载，缺模型就提示。智能混合本地优先，用户明确允许才联网兜底。在线优先适合稳定网络。下载前切换混合模式，完成后切回离线。固定引擎与声音来源不偷偷切换。",this::refresh);
  guide("④ ROOT：微信 / Telegram / WhatsApp / QQ", "先接通目标 App 的通话，让对方持续说话。选择 ROOT，授权并扫描设备，逐个做 5 秒 PCM 测试，确认声音确实来自对方后保存配置。有 PCM 数据但接近静音不算成功。SELinux、音频 HAL 和厂商限制可能阻止采集，不自动关闭 SELinux。",()->open(RootCallActivity.class));
  guide("⑤ Shizuku / 麦克风兼容", "在 Shizuku App 启动服务，再回浮译授权、扫描、测试。shell 身份并不等于 ROOT，无法保证读取微信或 TG 通话。失败可手动选麦克风外放；耳机通话的普通麦克风通常录不到对方。不会自动改变固定声音来源。",()->open(RootCallActivity.class));
  guide("⑥ 视频 / 直播内部声音", "从实时翻译选择系统内部声音，并同意系统捕获授权。目标 App 必须允许捕获；受保护媒体、部分直播和通话可能静音。每次启动按系统提示授权，先检查音频峰值，再查识别模型。",()->open(MainActivity.class));
  guide("⑦ 下载 / 校验 / 修复 / 备份", "下载支持断点续传与重试，完整包在安装成功前保留。空间不足先清理再重试解压；校验失败可重新下载修复。新模型验证成功才替换旧模型。ZIP 导入会校验路径、体积和 SHA-256；备份不含 API 密钥。ML Kit 文字模型在另一部手机单独下载。",()->open(ModelManagerActivity.class));
  guide("⑧ 延迟、准确率与省电", "优先使用覆盖语言的 INT8 模型。均衡/省电减少推理线程与语音段长度；高精度长段延迟更大。Vosk 等流式引擎可更新临时字幕，批量模型不会伪装流式输出。开头缓冲减少截字；不按重复文本删除内容。模型加载/静音推理通过不代表准确率已验证。",()->open(AsrPrecisionActivity.class));
  guide("⑨ 本地播报与回声", "只选择已经安装且不需要网络的 TTS Voice。播报时暂停输入、结束后恢复，不按内容相同过滤讲话。请等播报结束再说；连续大段播报会排队，可选择只显示文字。",()->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){show("在系统设置搜索文字转语音");}});
  guide("⑩ 故障处理 / 诊断报告", "诊断区分文件存在、校验、实际加载、推理、音频与翻译链路。缺权限进入系统修复；缺模型一键准备；模型损坏重新下载；通话静音进入 PCM 测试。不把未测试项目标为成功。报告可复制，不包含密钥或识别原文。",this::diagnose);
 }
 private void guide(String title,String text,Runnable action){button(title,()->new AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton("关闭",null).setPositiveButton("前往操作",(d,i)->action.run()).show());}
 @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(code==IMPORT)work(()->"导入并校验成功："+ModelBackup.importModel(this,uri));if(code==EXPORT&&exportId!=null){String id=exportId;work(()->{ModelBackup.exportModel(this,id,uri);return "模型备份完成";});}}
 @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("export_id",exportId);}
 @Override protected void onStop(){super.onStop();stopTest(true);}
 @Override protected void onDestroy(){destroyed=true;stopTest(true);main.removeCallbacksAndMessages(null);worker.shutdownNow();store.close();super.onDestroy();}
}
