package com.zhou.floatingtranslator;

import org.junit.Test;
import static org.junit.Assert.*;

public class UtterancePreservationTest {
    @Test public void repeatedUnpunctuatedFinalsRemainSeparate() {
        SmartSentenceAssembler assembler = new SmartSentenceAssembler();
        SpeechQueue queue = new SpeechQueue();
        for (int i = 0; i < 3; i++) assembler.offer("你好", true, "zh", queue::offer);
        assertEquals(3, queue.size());
        for (int i = 0; i < 3; i++) {
            SpeechQueue.Item item = queue.poll();
            assertTrue(item.complete);
            assertEquals("你好", item.text);
        }
    }

    @Test public void prefixSuffixAndConjunctionAreNotUtteranceIdentities() {
        SmartSentenceAssembler assembler = new SmartSentenceAssembler();
        SpeechQueue queue = new SpeechQueue();
        String[] inputs = {"今天晚上", "今天晚上我们出去吃饭", "出去吃饭", "because", "because"};
        for (String value : inputs) assembler.offer(value, true, "", queue::offer);
        for (String value : inputs) {
            SpeechQueue.Item item = queue.poll();
            assertTrue(item.complete);
            assertEquals(value, item.text);
        }
        assertNull(queue.poll());
    }

    @Test public void partialsCanRefreshButNeverEraseRepeatedFinals() {
        SmartSentenceAssembler assembler = new SmartSentenceAssembler();
        SpeechQueue queue = new SpeechQueue();
        for (int i = 0; i < 2; i++) {
            assembler.offer("hel", false, "en", queue::offer);
            assembler.offer("hello", false, "en", queue::offer);
            assembler.offer("hello", true, "en", queue::offer);
        }
        assertEquals(2, queue.size());
        assertTrue(queue.poll().complete);
        assertTrue(queue.poll().complete);
    }

    @Test public void repetitionsWithinOneUtteranceRemainIntact() {
        SmartSentenceAssembler assembler = new SmartSentenceAssembler();
        SpeechQueue queue = new SpeechQueue();
        assembler.offer("不不不，我说不不不", true, "zh", queue::offer);
        assertEquals("不不不，我说不不不", queue.poll().text);
    }
}
