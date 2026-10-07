package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class SpeechSegmenterTest {
  private static byte[] pcm(int samples, short value) {
    ByteBuffer bytes = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) bytes.putShort(value);
    return bytes.array();
  }

  @Test
  public void silenceDoesNotTriggerRecognition() {
    byte[] quiet = pcm(320000, (short) 0);
    assertTrue(new SpeechSegmenter().append(quiet, quiet.length).isEmpty());
  }

  @Test
  public void utteranceEndsOnSilenceAndRetainsPreRoll() {
    SpeechSegmenter segmenter = new SpeechSegmenter();
    byte[] pre = pcm(4800, (short) 0);
    byte[] voice = pcm(16000, (short) 8192);
    byte[] tail = pcm(9600, (short) 0);
    assertTrue(segmenter.append(pre, pre.length).isEmpty());
    assertTrue(segmenter.append(voice, voice.length).isEmpty());
    var output = segmenter.append(tail, tail.length);
    assertEquals(1, output.size());
    assertEquals(30400, output.get(0).length);
    assertEquals(0f, output.get(0)[4799], 0f);
    assertEquals(0.25f, output.get(0)[4800], 0f);
    assertEquals(0.25f, output.get(0)[20799], 0f);
    assertEquals(0f, output.get(0)[20800], 0f);
  }

  @Test
  public void continuousSpeechCapsAtTenSecondsWithoutDuplicatingSamples() {
    SpeechSegmenter segmenter = new SpeechSegmenter();
    byte[] voice = pcm(320000, (short) 8192);
    var output = segmenter.append(voice, voice.length);
    assertEquals(2, output.size());
    for (float[] samples : output) {
      assertEquals(160000, samples.length);
      for (float sample : samples) assertEquals(0.25f, sample, 0f);
    }
  }

  @Test
  public void oddByteBoundaryPreservesPcmSamples() {
    SpeechSegmenter segmenter = new SpeechSegmenter();
    byte[] voice = pcm(160000, (short) 8192);
    assertTrue(segmenter.append(new byte[] {voice[0]}, 1).isEmpty());
    byte[] rest = java.util.Arrays.copyOfRange(voice, 1, voice.length);
    var output = segmenter.append(rest, rest.length);
    assertEquals(1, output.size());
    assertEquals(160000, output.get(0).length);
    for (float sample : output.get(0)) assertEquals(0.25f, sample, 0f);
  }
}
