package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AgentPlaybackQueueTest {
  @Test
  public void interruptedAudioCannotPrecedeTheFlushOrStealNewBudget() throws Exception {
    var queue = new AgentPlaybackQueue(16000);
    assertTrue(queue.offer(new byte[32000]));
    var old = queue.take();
    assertTrue(queue.offer(new byte[32000]));
    queue.interrupt();
    for (int i = 0; i < 30; i++) assertTrue(queue.offer(new byte[64000]));
    assertFalse(queue.offer(new byte[2]));
    var flush = queue.take();
    assertNull(flush.pcm());
    assertNotEquals(old.generation(), flush.generation());
    var next = queue.take();
    assertEquals(flush.generation(), next.generation());
    assertEquals(64000, next.pcm().length);
    assertTrue(queue.offer(new byte[64000]));
  }

  @Test
  public void simultaneousProducerAlwaysPlacesFlushBeforeNewGeneration() throws Exception {
    for (int iteration = 0; iteration < 100; iteration++) {
      var queue = new AgentPlaybackQueue(16000);
      var go = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      Thread producer =
          new Thread(
              () -> {
                try {
                  go.await();
                  queue.offer(new byte[3200]);
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      producer.start();
      go.countDown();
      queue.interrupt();
      producer.join(1000);
      assertFalse("Producer did not finish", producer.isAlive());
      assertNull(failure.get());
      var first = queue.take();
      assertNull("New turn must start with flush", first.pcm());
      assertEquals(queue.generation(), first.generation());
      queue.close();
    }
  }

  @Test
  public void fastTtsBurstKeepsTheWholeUtterance() throws Exception {
    var queue = new AgentPlaybackQueue(16000);
    for (int second = 0; second < 12; second++) {
      byte[] pcm = new byte[32000];
      pcm[0] = (byte) second;
      assertTrue("Dropped second " + second + " of TTS burst", queue.offer(pcm));
    }
    for (int second = 0; second < 12; second++) {
      var frame = queue.take();
      assertEquals(second, frame.pcm()[0]);
      assertEquals(0, frame.generation());
    }
    queue.close();
  }

  @Test
  public void normalCompletionPlaysPendingAudioBeforeFinishing() throws Exception {
    var queue = new AgentPlaybackQueue(16000);
    assertTrue(queue.offer(new byte[32000]));
    queue.finish();
    assertFalse(queue.offer(new byte[2]));
    assertFalse(queue.take().finish());
    assertTrue(queue.take().finish());
    queue.interrupt();
    assertFalse(queue.take().finish());
    assertTrue(queue.take().finish());
    queue.close();
  }

  @Test
  public void closeWakesBlockedConsumerAndRejectsNewAudio() throws Exception {
    var queue = new AgentPlaybackQueue(16000);
    var done = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    Thread consumer =
        new Thread(
            () -> {
              try {
                assertNull(queue.take());
              } catch (Throwable error) {
                failure.set(error);
              } finally {
                done.countDown();
              }
            });
    consumer.start();
    queue.close();
    assertTrue(done.await(1, TimeUnit.SECONDS));
    assertNull(failure.get());
    assertFalse(queue.offer(new byte[3200]));
  }

  @Test
  public void inputIsCopiedAndOddPcmOrExcessFramesAreRejected() throws Exception {
    var queue = new AgentPlaybackQueue(16000);
    byte[] pcm = {1, 2};
    assertTrue(queue.offer(pcm));
    pcm[0] = 9;
    assertArrayEquals(new byte[] {1, 2}, queue.take().pcm());
    assertFalse(queue.offer(new byte[3]));
    for (int i = 0; i < 2048; i++) assertTrue(queue.offer(new byte[2]));
    assertFalse(queue.offer(new byte[2]));
    queue.close();
  }
}
