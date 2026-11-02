package com.zhou.floatingtranslator;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class OfflineUpgradeTest{
 @Test public void strictOfflineAlwaysRejectsCloud(){
  assertFalse(RunPolicy.networkAllowed("offline",true));assertFalse(RunPolicy.networkAllowed("offline",false));assertFalse(RunPolicy.networkAllowed("hybrid",false));assertTrue(RunPolicy.networkAllowed("hybrid",true));assertTrue(RunPolicy.networkAllowed("online",false));assertFalse(RunPolicy.networkAllowed("unknown",true));
 }
 @Test public void recommendationsCoverBothLanguagesAndMemory(){
  assertFalse(ModelAdvisor.supports("sensevoice","vi"));assertFalse(ModelAdvisor.supports("parakeet_ja","zh"));
  for(String lang:ModelAdvisor.LANGS)assertTrue(ModelAdvisor.supports("whisper_small",lang));
  assertEquals(Collections.singletonList("whisper_small"),ModelAdvisor.order("tl","zh",true,"accuracy",1024L*1024*1024));
  assertFalse(ModelAdvisor.order("ja","zh",true,"balanced",Long.MAX_VALUE).contains("parakeet_ja"));
 }
 @Test public void archiveCannotEscapeRoot()throws Exception{
  File dir=Files.createTempDirectory("paths").toFile();try{for(String path:new String[]{"../escape","/tmp/escape","a/../../escape","..\\escape"}){try{ModelIntegrity.safeChild(dir,path);fail(path);}catch(IOException expected){}}assertEquals(new File(dir,"model/encoder.onnx"),ModelIntegrity.safeChild(dir,"model/encoder.onnx"));}finally{dir.delete();}
 }
 @Test public void prerollConsumesOnlyLatestSamplesOnce(){PcmPreroll p=new PcmPreroll(4);p.offer(new byte[]{1,2,3},3);p.offer(new byte[]{4,5},2);assertArrayEquals(new byte[]{2,3,4,5},p.take());assertEquals(0,p.take().length);p.offer(new byte[]{1,1,1},3);assertArrayEquals(new byte[]{1,1,1},p.take());}
 @Test public void modelRequiresBothNetworksAndTokens()throws Exception{
  File dir=Files.createTempDirectory("model").toFile();try{Files.write(new File(dir,"encoder.onnx").toPath(),new byte[]{1});assertFalse(ModelIntegrity.structure(dir,"whisper_small"));Files.write(new File(dir,"decoder.onnx").toPath(),new byte[]{1});Files.write(new File(dir,"tokens.txt").toPath(),new byte[]{1});assertTrue(ModelIntegrity.structure(dir,"whisper_small"));}finally{OfflineModelStore.deleteRecursively(dir);}
 }
}
