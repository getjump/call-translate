package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class AgentPcmFanoutTest {
  @Test
  public void partialAndZeroWritesDeliverIdenticalPcmToBothOutputs() throws Exception {
    byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    ByteArrayOutputStream remote = new ByteArrayOutputStream();
    ByteArrayOutputStream local = new ByteArrayOutputStream();
    int[] calls = {0};
    assertTrue(
        AgentPcmFanout.write(
            pcm,
            4,
            () -> true,
            (bytes, offset, length) -> {
              int count = Math.min(2, length);
              remote.write(bytes, offset, count);
              return count;
            },
            (bytes, offset, length) -> {
              if (calls[0]++ == 0) return 0;
              local.write(bytes, offset, length);
              return length;
            }));
    assertArrayEquals(pcm, remote.toByteArray());
    assertArrayEquals(pcm, local.toByteArray());
  }

  @Test
  public void interruptionBetweenOutputsDoesNotReplayOldAudioLocally() throws Exception {
    AtomicBoolean current = new AtomicBoolean(true);
    ByteArrayOutputStream local = new ByteArrayOutputStream();
    assertFalse(
        AgentPcmFanout.write(
            new byte[] {1, 2, 3, 4},
            2,
            current::get,
            (bytes, offset, length) -> {
              current.set(false);
              return length;
            },
            (bytes, offset, length) -> {
              local.write(bytes, offset, length);
              return length;
            }));
    assertEquals(0, local.size());
  }

  @Test
  public void invalidDriverWritesAreRejectedBeforeAdvancingPcm() throws Exception {
    for (int count : new int[] {-1, 1, 6}) {
      try {
        AgentPcmFanout.write(new byte[] {1, 2, 3, 4}, 4, () -> true, (b, o, n) -> count);
        fail("Invalid PCM write was accepted: " + count);
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains(Integer.toString(count)));
      }
    }
  }

  @Test
  public void stalledLocalOutputCannotHangTheVoiceSessionForever() throws Exception {
    ByteArrayOutputStream remote = new ByteArrayOutputStream();
    try {
      AgentPcmFanout.write(
          new byte[] {1, 2, 3, 4},
          4,
          () -> true,
          (bytes, offset, length) -> {
            remote.write(bytes, offset, length);
            return length;
          },
          (bytes, offset, length) -> 0);
      fail("Stalled local output was accepted");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("завис"));
      assertArrayEquals(new byte[] {1, 2, 3, 4}, remote.toByteArray());
    }
  }
}
