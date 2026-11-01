package com.zhou.floatingtranslator;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.zip.*;
/** Portable ASR backups using SAF. No API keys, history or ML Kit private files. */
public final class ModelBackup {
 public static void exportModel(Context c,String id,Uri uri)throws Exception{
  if(!OfflineModelStore.MUTATION.tryLock())throw new IOException("模型操作正在进行");
  try(OfflineModelStore store=new OfflineModelStore(c)){
   File root=store.modelDir(id);if(!store.isInstalled(id))throw new IOException("模型未完整安装");
   if(!new File(root,ModelIntegrity.MANIFEST).exists())throw new IOException("请先为旧模型建立校验清单并加载测试");ModelIntegrity.verify(root,id);
   try(OutputStream stream=c.getContentResolver().openOutputStream(uri,"wt");ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(Objects.requireNonNull(stream)))){
    zip.setLevel(0);List<File> files=ModelIntegrity.files(root);files.add(new File(root,ModelIntegrity.MANIFEST));
    for(File f:files){zip.putNextEntry(new ZipEntry(id+"/"+root.toPath().relativize(f.toPath())));try(InputStream in=new FileInputStream(f)){copy(in,zip,Long.MAX_VALUE);}zip.closeEntry();}
   }
  }finally{OfflineModelStore.MUTATION.unlock();}
 }
 public static String importModel(Context c,Uri uri)throws Exception{
  if(!OfflineModelStore.MUTATION.tryLock())throw new IOException("模型操作正在进行");
  File stage=new File(c.getFilesDir(),"import-"+UUID.randomUUID());File target=null,previous=null;boolean moved=false,backedUp=false,done=false;
  try(OfflineModelStore store=new OfflineModelStore(c)){
   if(!stage.mkdirs())throw new IOException("无法创建导入目录");String id=null;Set<String> names=new HashSet<>();long bytes=0;
   try(InputStream raw=c.getContentResolver().openInputStream(uri);ZipInputStream zip=new ZipInputStream(new BufferedInputStream(Objects.requireNonNull(raw)))){
    ZipEntry e;while((e=zip.getNextEntry())!=null){String name=e.getName();if(names.size()>=40000||!names.add(name))throw new IOException("压缩包条目异常");int slash=name.indexOf('/');if(slash<1)throw new IOException("请选择浮译导出的模型 ZIP");String key=name.substring(0,slash);
     if(OfflineAsrModelCatalog.find(key)==null||(id!=null&&!id.equals(key)))throw new IOException("备份模型标识错误");id=key;File out=ModelIntegrity.safeChild(stage,name);
     if(e.isDirectory()){if(!out.exists()&&!out.mkdirs())throw new IOException("无法创建目录");continue;}
     if(!out.getParentFile().exists()&&!out.getParentFile().mkdirs())throw new IOException("无法创建目录");
     long limit=Math.min(12L*1024*1024*1024-bytes,stage.getUsableSpace()-128L*1024*1024);
     try(OutputStream os=new FileOutputStream(out)){bytes+=copy(zip,os,limit);}zip.closeEntry();
    }
   }
   if(id==null)throw new IOException("空备份包");File incoming=new File(stage,id);ModelIntegrity.verify(incoming,id);
   if(!store.baseDir().exists()&&!store.baseDir().mkdirs())throw new IOException("无法创建模型目录");target=store.modelDir(id);previous=new File(store.baseDir(),id+".import-previous");
   if(previous.exists())throw new IOException("存在待恢复的旧模型备份，请先检查");
   if(target.exists()){if(!target.renameTo(previous))throw new IOException("无法备份原模型");backedUp=true;}
   if(!incoming.renameTo(target))throw new IOException("无法发布导入模型");moved=true;
   java.nio.file.Files.writeString(new File(target,".ready").toPath(),id);done=true;OfflineModelStore.deleteRecursively(previous);return id;
  }finally{
   try {
    if(!done){if(moved&&target!=null)OfflineModelStore.deleteRecursively(target);if(backedUp&&previous.exists()&&!previous.renameTo(target))throw new IOException("恢复失败，旧模型仍保留在 .import-previous 目录");}
   }finally{OfflineModelStore.deleteRecursively(stage);OfflineModelStore.MUTATION.unlock();}
  }
 }
 static long copy(InputStream in,OutputStream out,long limit)throws IOException{
  byte[] b=new byte[131072];int n;long count=0;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");count+=n;if(count>limit)throw new IOException("存储不足或模型包过大");out.write(b,0,n);}return count;
 }
}
