package com.zhou.floatingtranslator;
/** Silence before the next utterance, never completed speech. */
final class PcmPreroll{
 private final byte[] ring;private int write,count;
 PcmPreroll(int size){ring=new byte[size];}
 void offer(byte[] pcm,int length){for(int i=0;i<Math.min(length,pcm.length);i++){ring[write]=pcm[i];write=(write+1)%ring.length;count=Math.min(count+1,ring.length);}}
 byte[] take(){byte[] out=new byte[count];for(int i=0;i<count;i++)out[i]=ring[(write-count+i+ring.length)%ring.length];clear();return out;}
 void clear(){write=0;count=0;}
}
