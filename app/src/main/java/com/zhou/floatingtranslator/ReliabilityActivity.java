package com.zhou.floatingtranslator;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.net.Uri;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Runtime state, recovery actions and an honest, device-specific acceptance log. */
public final class ReliabilityActivity extends Activity {
    private LinearLayout root;private TextView live,downloads,report;private boolean destroyed,retrying;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private OfflineFirstTranslationRouter retryRouter;private final ArrayDeque<JSONObject> retryQueue=new ArrayDeque<>();
    private int recovered;private static final int EXPORT=791,IMPORT=792;
    private final Runnable tick=new Runnable(){public void run(){if(destroyed)return;live.setText(SessionHealth.summary(ReliabilityActivity.this));downloads.setText(DownloadTasks.summary(ReliabilityActivity.this));main.postDelayed(this,2000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("运行与恢复中心");
        ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(28,28,28,150);scroll.setBackgroundColor(Color.rgb(17,13,30));scroll.addView(root);setContentView(scroll);
        label("运行状态与长时间保护",23);live=label("",14);
        button("只暂停采音，继续处理已有内容",()->SessionHealth.holdAll(true));button("请求恢复采音",()->SessionHealth.holdAll(false));
        label("保护默认开启：设备温控达到严重级别、系统低内存或积压 24 句时暂停采音；恢复条件满足后继续。暂停期间的新讲话不会被收录。不会按文字相同删除内容。",13);
        button("长时间保护设置",()->new AlertDialog.Builder(this).setTitle("采音保护")
            .setItems(new String[]{"开启自动保护（推荐）","关闭自动保护，仅保留手动暂停"},(d,i)->RunPolicy.prefs(this).edit().putBoolean("session_protection",i==0).apply()).show());
        button("切换省电偏好（下次启动生效）",()->{RunPolicy.prefs(this).edit().putString("model_goal","battery").putString("asr_conversation_profile","low_latency").apply();show("已保存省电偏好；不在当前句中途切换模型。");});
        label("未完成 / 失败句",23);
        label("仅保存已经得到的识别文字。录音尚未识别、被系统强制结束的内存队列无法恢复。重试遵守当前联网授权，不会自动播放译文。",13);
        button("查看失败句 / 单句重试",this::chooseFailure);button("按顺序重试全部失败句",()->loadRetries(null));button("停止重试，保留未完成项",this::stopRetry);
        button("清空失败句",()->new AlertDialog.Builder(this).setTitle("删除所有待重试原文？").setMessage("删除后无法从此列表恢复。").setNegativeButton("取消",null).setPositiveButton("删除",(d,i)->work(()->{if(retrying)throw new IllegalStateException("请先停止重试");FailureStore.clear(this);return "已清空";})).show());
        label("下载任务",23);downloads=label("",14);
        button("继续未完成任务",()->{try{if(ModelDownloadService.active){show("已有下载任务正在运行");return;}DownloadTasks.resume(this);ModelDownloadService.start(this);}catch(Exception e){show(e.getMessage());}});
        button("暂停模型下载",()->{stopService(new Intent(this,ModelDownloadService.class));show("已请求停止，缓存与任务保留。ML Kit 文字模型下载由系统组件管理，已提交的下载可能继续完成。");});
        button("前往离线模型准备",()->startActivity(new Intent(this,OfflineCenterActivity.class)));
        label("场景配置",23);label("保存当前语言、声音来源、字幕、播报与模型偏好。应用场景前停止翻译；应用后默认完全离线，联网需重新允许，通话配置需要重新测试。",13);
        button("保存当前配置为场景",this::saveScene);button("应用 / 删除已保存场景",this::chooseScene);
        label("数据备份与升级恢复",23);label("JSON 备份含历史原文和译文、场景与白名单设置，请自行保管。不含 API 密钥、授权、音频或模型。导入会替换历史，先自动保留恢复点。",13);
        button("导出历史与设置备份",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"FloatingTranslator-data.json"),EXPORT));
        button("导入数据备份",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT));
        button("查看升级检查与恢复点",()->show(RunPolicy.prefs(this).getString("upgrade_checkpoint","暂无升级快照")+"\n版本："+BuildConfig.VERSION_NAME+" / "+BuildConfig.VERSION_CODE+"\n升级后请验证历史数量、模型就绪和权限。权限与模型不会从设置备份中伪造恢复。"));
        button("恢复升级前的数据快照",()->restore("before-upgrade.json"));button("撤回最近一次导入",()->restore("before-restore.json"));
        label("真机验证记录（手动实测）",23);label("默认全部未测试。请实际操作后记录通过或失败；计时结束不代表准确率、离线能力或通话兼容性通过。记录包含本机型号与版本，不含识别原文。",13);
        button("开始 15 分钟连续使用计时",()->{RunPolicy.prefs(this).edit().putLong("soak_started",SystemClock.elapsedRealtime()).putInt("soak_boot",android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1)).apply();show("计时已开始，请回到翻译页面实际使用。完成后回来记录结果。");});
        button("记录长时间 / 系统兼容测试",()->chooseTest(new String[]{"断网连续使用 15 分钟","锁屏与解锁","切换应用","来电打断与恢复","蓝牙连接与断开","Android 状态栏 / 导航栏遮挡","返回手势","后台录音 / 悬浮窗","升级后历史 / 模型 / 权限"}));
        button("记录六语种准确率与延迟",()->new AlertDialog.Builder(this).setTitle("选择实际测试语言").setItems(ModelAdvisor.NAMES,(d,i)->{
            String language=ModelAdvisor.NAMES[i];chooseTest(new String[]{language+" · 短句",language+" · 快语速",language+" · 长句",language+" · 重复句逐句保留",language+" · 双向切换"});}).show());
        button("查看 / 复制验证报告",this::validationReport);
        report=label("操作结果",14);report.setTextIsSelectable(true);
    }
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setPadding(0,14,0,10);root.addView(v);return v;}
    private void button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);root.addView(b);b.setOnClickListener(v->action.run());}
    private void show(String text){if(!destroyed&&report!=null)report.setText(text==null?"未知错误":text);}
    private interface Job{String run()throws Exception;}
    private void work(Job action){worker.execute(()->{String result;try{result=action.run();}catch(Exception e){result="失败："+e.getMessage();}String message=result;main.post(()->show(message));});}
    private void chooseFailure(){try{JSONArray rows=FailureStore.read(this);if(rows.length()==0){show("没有待重试句子");return;}String[] labels=new String[Math.min(rows.length(),100)];
        for(int i=0;i<labels.length;i++){JSONObject j=rows.getJSONObject(i);String t=j.getString("text");labels[i]=(i+1)+". "+t.substring(0,Math.min(80,t.length()))+"\n"+j.optString("reason");}
        new AlertDialog.Builder(this).setTitle("最近列表前 100 项 · 点击重试").setItems(labels,(d,i)->{try{loadRetries(rows.getJSONObject(i).getString("id"));}catch(Exception e){show(e.getMessage());}}).show();
    }catch(Exception e){show(e.getMessage());}}
    private void loadRetries(String id){if(retrying){show("正在重试，请等待或停止");return;}try{JSONArray a=FailureStore.read(this);retryQueue.clear();for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);if(id==null||id.equals(j.getString("id")))retryQueue.add(j);}recovered=0;retrying=true;nextRetry();}catch(Exception e){show(e.getMessage());}}
    private void nextRetry(){if(!retrying||destroyed)return;JSONObject item=retryQueue.poll();if(item==null){retrying=false;if(retryRouter!=null){retryRouter.close();retryRouter=null;}show("重试完成：成功 "+recovered+" 句；失败项仍保留。");return;}
        try{if(retryRouter!=null)retryRouter.close();retryRouter=new OfflineFirstTranslationRouter(this,item.getString("source"),item.getString("target"),item.getString("engine"));
            OfflineFirstTranslationRouter router=retryRouter;router.translate(item.getString("text"),item.getString("source"),new OfflineFirstTranslationRouter.Callback(){
                public void onSuccess(String value,String engine){if(!retrying||retryRouter!=router||destroyed)return;
                    try{HistoryStore.appendRecovered(ReliabilityActivity.this,item,value);FailureStore.remove(ReliabilityActivity.this,item.getString("id"));recovered++;}catch(Exception e){show("译文保存失败，原文保留："+e.getMessage());}
                    main.post(ReliabilityActivity.this::nextRetry);
                }
                public void onError(String message){if(!retrying||retryRouter!=router||destroyed)return;show("仍未成功："+message);main.post(ReliabilityActivity.this::nextRetry);}
            });
        }catch(Exception e){show(e.getMessage());main.post(this::nextRetry);}}
    private void stopRetry(){retrying=false;retryQueue.clear();if(retryRouter!=null){retryRouter.close();retryRouter=null;}show("重试已停止，未完成项保留");}
    private void saveScene(){EditText input=new EditText(this);input.setHint("例如：面对面聊天 / 视频字幕 / 通话");new AlertDialog.Builder(this).setTitle("保存场景").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,i)->{String name=input.getText().toString().trim();if(name.isEmpty()||name.length()>60){show("场景名称需 1–60 字");return;}work(()->{UserArchive.saveScene(this,name);return "场景已保存："+name;});}).show();}
    private void chooseScene(){try{JSONObject scenes=UserArchive.scenes(this);List<String> names=new ArrayList<>();scenes.keys().forEachRemaining(names::add);if(names.isEmpty()){show("尚无场景，请先保存当前配置");return;}
        new AlertDialog.Builder(this).setTitle("选择场景").setItems(names.toArray(new String[0]),(d,i)->{String name=names.get(i);new AlertDialog.Builder(this).setTitle(name).setMessage("应用后切换到完全离线；通话配置须重新测试，不恢复云端授权。").setNegativeButton("取消",null).setNeutralButton("删除",(x,y)->work(()->{UserArchive.deleteScene(this,name);return "已删除场景";})).setPositiveButton("应用",(x,y)->work(()->{UserArchive.applySettings(this,scenes.getJSONObject(name));return "场景已应用，请进入对应翻译页面启动";})).show();}).show();
    }catch(Exception e){show(e.getMessage());}}
    private void restore(String name){new AlertDialog.Builder(this).setTitle("恢复本机快照？").setMessage("会替换当前历史与设置。请先导出当前数据，停止翻译和重试。").setNegativeButton("取消",null).setPositiveButton("恢复",(d,i)->work(()->{if(retrying)throw new IllegalStateException("请停止重试");UserArchive.restoreCheckpoint(this,name);return "快照已恢复，当前为完全离线模式";})).show();}
    private void chooseTest(String[] names){new AlertDialog.Builder(this).setTitle("选择实际操作的项目").setItems(names,(d,i)->recordTest(names[i])).show();}
    private void recordTest(String test){EditText notes=new EditText(this);notes.setHint("样本数、错词数、延迟、温度、设备或失败现象（勿填密钥）");new AlertDialog.Builder(this).setTitle(test).setView(notes).setNeutralButton("未测试",(d,i)->saveTest(test,"未测试",notes.getText().toString())).setNegativeButton("失败",(d,i)->saveTest(test,"手动实测失败",notes.getText().toString())).setPositiveButton("通过",(d,i)->{
        if(test.contains("15 分钟")){int boot=android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);long start=RunPolicy.prefs(this).getLong("soak_started",-1);
            if(start<0||RunPolicy.prefs(this).getInt("soak_boot",-2)!=boot||SystemClock.elapsedRealtime()-start<900000){show("尚未完成本次开机内的 15 分钟计时；计时不能代替实测。");return;}}
        saveTest(test,"用户记录通过（非自动认证）",notes.getText().toString());}).show();}
    private void saveTest(String test,String state,String note){getSharedPreferences("device_acceptance",0).edit().putString(test,new Date()+" · "+state+"\n"+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" / APP "+BuildConfig.VERSION_CODE+"\n"+note).apply();show("已记录："+test+" · "+state);}
    private void validationReport(){StringBuilder b=new StringBuilder("浮译真机验证记录\n未列出的项目均未测试；记录不能代表其他手机的兼容性。\n");for(Map.Entry<String,?> e:getSharedPreferences("device_acceptance",0).getAll().entrySet())b.append('\n').append(e.getKey()).append('\n').append(e.getValue()).append('\n');String text=b.toString();show(text);getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("验证记录",text));}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(code==EXPORT)work(()->{UserArchive.exportTo(this,uri);return "备份导出成功";});
        if(code==IMPORT)new AlertDialog.Builder(this).setTitle("导入并替换历史与设置？").setMessage("将先保存当前恢复点。不导入密钥、权限或云端授权；请先停止翻译和重试。").setNegativeButton("取消",null).setPositiveButton("导入",(d,i)->work(()->{if(retrying)throw new IllegalStateException("请先停止重试");UserArchive.importFrom(this,uri);return "数据已恢复；当前为完全离线模式";})).show();
    }
    @Override protected void onResume(){super.onResume();main.post(tick);}
    @Override protected void onPause(){main.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;stopRetry();main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
}
