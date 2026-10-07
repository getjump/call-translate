package dev.getjump.calltranslate.agent;

import static org.junit.Assert.*;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ICaptureService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AgentAudioIoDeviceTest {
  @Test
  public void simAudioAlsoStartsLocalPlaybackAndDrainsBeforeClosing() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var manager = context.getSystemService(AudioManager.class);
    int previous = voicePlayers(manager);
    FakeCapture capture = new FakeCapture();
    AtomicReference<String> failure = new AtomicReference<>();
    try (var audio =
        new AgentAudioIo(
            context,
            16000,
            16000,
            capture,
            new AgentAudioIo.Listener() {
              @Override
              public boolean input(byte[] pcm) {
                return true;
              }

              @Override
              public void failed(String reason) {
                failure.set(reason);
              }
            })) {
      audio.start();
      assertTrue(audio.play(new byte[3200], 16000));
      capture.awaitWrites(1);
      long deadline = android.os.SystemClock.elapsedRealtime() + 1500;
      while (voicePlayers(manager) <= previous
          && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(10);
      assertTrue("SIM audio has no local voice playback", voicePlayers(manager) > previous);
      CountDownLatch finished = new CountDownLatch(1);
      audio.finishPlayback(finished::countDown);
      boolean completed = finished.await(3, TimeUnit.SECONDS);
      assertTrue(
          "SIM and local outputs did not drain: "
              + playbackState(audio)
              + ", failure="
              + failure.get(),
          completed);
      assertNull(failure.get());
    } finally {
      capture.closePipe();
    }
  }

  private static int voicePlayers(AudioManager manager) {
    return (int)
        manager.getActivePlaybackConfigurations().stream()
            .filter(
                player ->
                    player.getAudioAttributes().getUsage()
                        == AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .count();
  }

  private static String playbackState(AgentAudioIo audio) throws Exception {
    var field = AgentAudioIo.class.getDeclaredField("speaker");
    field.setAccessible(true);
    AudioTrack track = (AudioTrack) field.get(audio);
    return "head="
        + track.getPlaybackHeadPosition()
        + ", threshold="
        + track.getStartThresholdInFrames()
        + ", buffer="
        + track.getBufferSizeInFrames()
        + ", state="
        + track.getPlayState();
  }

  @Test
  public void pcmPipeAndPlaybackSurviveInterruptionThenClose() throws Exception {
    FakeCapture capture = new FakeCapture();
    CountDownLatch input = new CountDownLatch(1);
    AtomicReference<String> failure = new AtomicReference<>();
    ByteArrayOutputStream received = new ByteArrayOutputStream();
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (var audio =
        new AgentAudioIo(
            context,
            16000,
            11025,
            capture,
            new AgentAudioIo.Listener() {
              @Override
              public boolean input(byte[] pcm) {
                assertEquals(0, pcm.length % 2);
                synchronized (received) {
                  received.write(pcm, 0, pcm.length);
                  if (received.size() >= 4) input.countDown();
                }
                return true;
              }

              @Override
              public void failed(String reason) {
                failure.set(reason);
              }
            })) {
      audio.start();
      capture.sink.write(new byte[] {1});
      capture.sink.flush();
      Thread.sleep(30);
      capture.sink.write(new byte[] {2, 3, 4});
      capture.sink.flush();
      assertTrue("PCM input did not arrive", input.await(2, TimeUnit.SECONDS));
      assertTrue(audio.play(new byte[4410], 11025));
      capture.awaitWrites(2);
      audio.interruptPlayback();
      assertTrue(audio.play(new byte[4410], 11025));
      capture.awaitWrites(4);
      CountDownLatch finished = new CountDownLatch(1);
      audio.finishPlayback(finished::countDown);
      assertTrue("Final voice audio did not drain", finished.await(2, TimeUnit.SECONDS));
      assertEquals(4410, capture.newTurnBytes);
      assertFalse(audio.play(new byte[2], 11025));
      assertNull(failure.get());
      assertEquals(11025, capture.sampleRate);
      assertTrue(capture.events.contains("flush:1"));
      assertTrue(capture.events.indexOf("flush:1") < capture.events.indexOf("write:1"));
      audio.close();
      assertFalse(audio.play(new byte[2], 11025));
      assertEquals(1, capture.stops);
      synchronized (received) {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, received.toByteArray());
      }
    } finally {
      capture.closePipe();
    }
    assertNull(failure.get());
  }

  @Test
  public void simRejectsUnsupportedInputBeforeOpeningRoute() throws Exception {
    FakeCapture capture = new FakeCapture();
    try {
      try {
        new AgentAudioIo(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            8000,
            16000,
            capture,
            new AgentAudioIo.Listener() {
              @Override
              public boolean input(byte[] pcm) {
                return true;
              }

              @Override
              public void failed(String reason) {
                fail(reason);
              }
            });
        fail("Expected explicit SIM format error");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains("PCM 16000"));
      }
      assertEquals(0, capture.sampleRate);
      assertEquals(1, capture.stops);
    } finally {
      capture.closePipe();
    }
  }

  private static final class FakeCapture extends ICaptureService.Stub {
    final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
    final ParcelFileDescriptor.AutoCloseOutputStream sink =
        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
    final List<String> events = new ArrayList<>();
    int sampleRate;
    int writes;
    int stops;
    int newTurnBytes;
    long generation;

    FakeCapture() throws IOException {}

    @Override
    public ParcelFileDescriptor start(int source) {
      return pipe[0];
    }

    @Override
    public synchronized void stop() {
      stops++;
      try {
        sink.close();
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    @Override
    public void destroy() {
      stop();
    }

    @Override
    public String diagnostics() {
      return "fake";
    }

    @Override
    public void openInjection(int rate) {
      sampleRate = rate;
    }

    @Override
    public synchronized int writeInjection(byte[] pcm, long turn) throws RemoteException {
      if (turn != generation || pcm.length % 2 != 0 || pcm.length > 9600)
        throw new RemoteException("Invalid generation or PCM");
      if (turn == 1) newTurnBytes += pcm.length;
      events.add("write:" + turn);
      writes++;
      notifyAll();
      return pcm.length;
    }

    @Override
    public synchronized void flushInjection(long turn) {
      generation = turn;
      events.add("flush:" + turn);
    }

    @Override
    public void stopInjection() {}

    @Override
    public synchronized void stopInput() {
      try {
        sink.close();
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    @Override
    public boolean injectionDrained(long turn) {
      return turn == generation;
    }

    synchronized void awaitWrites(int count) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (writes < count && System.nanoTime() < deadline) wait(50);
      assertTrue("Expected " + count + " writes, got " + writes, writes >= count);
    }

    void closePipe() throws IOException {
      sink.close();
      pipe[0].close();
    }
  }
}
