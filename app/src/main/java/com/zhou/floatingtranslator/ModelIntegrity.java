package com.zhou.floatingtranslator;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
/** Local baseline detects later damage, not publisher authenticity. */
public final class ModelIntegrity {
 public static final String MANIFEST=".inventory.json";
 public static boolean structure(File dir,String id){
  if(dir==null||!dir.isDirectory())return false;
  String[] parts=id.startsWith("whisper_")?new String[]{"encoder","decoder","tokens"}:id.equals("qwen3_asr_06b")?new String[]{"conv_frontend","encoder","decoder","tokenizer"}:id.equals("reazon_ja")?new String[]{"encoder","decoder","joiner","tokens"}:new String[]{"model","tokens"};
  for(String part:parts)if(OfflineModelStore.findFirst(dir,f->f.isFile()&&f.length()>0&&(part.equals("tokenizer")?f.getParent().contains("tokenizer"):f.getName().contains(part)&&(part.equals("tokens")||f.getName().endsWith(".onnx"))))==null)return false;
  return true;
 }
 public static File safeChild(File dir,String path)throws IOException{
  File f=new File(dir,path);if(path.isEmpty()||path.startsWith("/")||path.contains("\\")||!f.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new IOException("非法压缩包路径");return f;
 }
 public static List<File> files(File dir)throws IOException{List<File> out=new ArrayList<>();collect(dir,dir.getCanonicalPath()+File.separator,out,0);out.sort(Comparator.comparing(File::getPath));return out;}
 private static void collect(File dir,String root,List<File> out,int depth)throws IOException{
  if(depth>24)throw new IOException("模型目录过深");File[] children=dir.listFiles();if(children==null)throw new IOException("无法读取模型目录");
  for(File f:children){if(Files.isSymbolicLink(f.toPath())||!f.getCanonicalPath().startsWith(root))throw new IOException("不支持模型链接");
   if(f.isDirectory())collect(f,root,out,depth+1);else if(!f.getName().equals(MANIFEST)&&!f.getName().equals(MANIFEST+".tmp")&&!f.getName().equals(".ready"))out.add(f);
   if(out.size()>40000)throw new IOException("模型文件过多");}
 }
 static String digest(File f)throws Exception{
  MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("校验已取消");hash.update(b,0,n);}}
  StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
 }
 public static void inventory(File root,String id)throws Exception{
  if(!structure(root,id))throw new IOException("缺少模型网络或词表文件");JSONArray entries=new JSONArray();
  for(File f:files(root))entries.put(new JSONObject().put("path",root.toPath().relativize(f.toPath()).toString()).put("size",f.length()).put("sha256",digest(f)));
  File temp=new File(root,MANIFEST+".tmp");Files.write(temp.toPath(),new JSONObject().put("model",id).put("files",entries).toString().getBytes(StandardCharsets.UTF_8));Files.move(temp.toPath(),new File(root,MANIFEST).toPath(),StandardCopyOption.REPLACE_EXISTING);
 }
 public static void verify(File root,String id)throws Exception{
  if(!structure(root,id))throw new IOException("必需文件缺失或为空");File file=new File(root,MANIFEST);
  if(!file.isFile())throw new IOException("旧模型无校验基线，请建立清单或重新下载安装");if(file.length()>12*1024*1024)throw new IOException("清单异常");
  JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(!id.equals(json.getString("model")))throw new IOException("模型标识不匹配");JSONArray entries=json.getJSONArray("files");Set<String> names=new HashSet<>();
  for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);String name=e.getString("path");File f=safeChild(root,name);if(!names.add(name)||!f.isFile()||f.length()!=e.getLong("size")||!digest(f).equals(e.getString("sha256")))throw new IOException("校验失败："+name);}
  if(entries.length()!=files(root).size())throw new IOException("存在未登记模型文件");
 }
}
