package com.zhou.floatingtranslator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=android.app.Application.class)
public class ModelIntegrityTest{
 @Test public void changedBytesOfSameSizeFailVerification()throws Exception{
  File dir=Files.createTempDirectory("inventory").toFile();try{Files.write(new File(dir,"model.onnx").toPath(),new byte[]{1,2,3});Files.write(new File(dir,"tokens.txt").toPath(),new byte[]{1});ModelIntegrity.inventory(dir,"sensevoice");ModelIntegrity.verify(dir,"sensevoice");Files.write(new File(dir,"model.onnx").toPath(),new byte[]{1,2,4});try{ModelIntegrity.verify(dir,"sensevoice");fail("changed bytes accepted");}catch(IOException expected){}}finally{OfflineModelStore.deleteRecursively(dir);}
 }
 @Test public void categoryFilterRetainsEveryRepeatedEvent(){List<HistoryStore.Entry> entries=Arrays.asList(new HistoryStore.Entry(1,"hello","你好","call"),new HistoryStore.Entry(2,"hello","你好","call"),new HistoryStore.Entry(3,"hello","你好","conversation"));assertEquals(2,HistoryStore.filter(entries,"你好","call").size());assertEquals(3,HistoryStore.filter(entries,"HELLO","").size());}
 @Test public void invalidImportPreservesInstalledModelAndUnlocks()throws Exception{
  android.content.Context c=RuntimeEnvironment.getApplication();File dir=new File(c.getFilesDir(),"sherpa-models/sensevoice");dir.mkdirs();File old=new File(dir,"keep.txt");Files.write(old.toPath(),"previous".getBytes(java.nio.charset.StandardCharsets.UTF_8));File zip=File.createTempFile("invalid-model",".zip",c.getCacheDir());
  try{try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){out.putNextEntry(new ZipEntry("sensevoice/../../escape"));out.write(1);out.closeEntry();}
   try{ModelBackup.importModel(c,Uri.fromFile(zip));fail("unsafe archive accepted");}catch(IOException expected){}
   assertEquals("previous",new String(Files.readAllBytes(old.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertTrue(OfflineModelStore.MUTATION.tryLock());OfflineModelStore.MUTATION.unlock();
  }finally{zip.delete();OfflineModelStore.deleteRecursively(dir);}
 }
}
