package dev.getjump.calltranslate.core;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class AgentPcmFanout {
  public interface Output {
    int write(byte[] pcm, int offset, int length) throws Exception;
  }

  public static boolean write(
      byte[] pcm, int chunkBytes, BooleanSupplier current, Output... outputs) throws Exception {
    if (pcm == null
        || pcm.length == 0
        || pcm.length % 2 != 0
        || chunkBytes <= 0
        || chunkBytes % 2 != 0
        || outputs.length == 0) throw new IllegalArgumentException("Invalid PCM fanout");
    int[] offsets = new int[outputs.length];
    long[] progress = new long[outputs.length];
    java.util.Arrays.fill(progress, System.nanoTime());
    while (current.getAsBoolean()) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      boolean completed = true;
      boolean advanced = false;
      for (int i = 0; i < outputs.length; i++) {
        if (!current.getAsBoolean()) return false;
        if (offsets[i] == pcm.length) continue;
        int length = Math.min(chunkBytes, pcm.length - offsets[i]);
        int count = outputs[i].write(pcm, offsets[i], length);
        if (count < 0 || count > length || count % 2 != 0)
          throw new IllegalStateException("Ошибка вывода голоса: " + count);
        if (count == 0) {
          if (System.nanoTime() - progress[i] > 2000000000L)
            throw new IllegalStateException("Вывод голоса завис");
        } else {
          offsets[i] += count;
          progress[i] = System.nanoTime();
          advanced = true;
        }
        completed &= offsets[i] == pcm.length;
      }
      if (completed) return current.getAsBoolean();
      if (!advanced) LockSupport.parkNanos(5000000L);
    }
    return false;
  }

  private AgentPcmFanout() {}
}
