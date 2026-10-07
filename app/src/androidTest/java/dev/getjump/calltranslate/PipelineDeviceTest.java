package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.CaptionHistory;
import dev.getjump.calltranslate.ml.InferenceWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PipelineDeviceTest {
  @Test
  public void replacementSessionProducesOfflineCaptionWithAutomaticInput() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var settings = new SettingsStore(context);
    String source = settings.source(),
        provider = settings.provider(),
        target = settings.target(),
        asr = settings.asr(),
        backend = settings.backend();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String[]> caption = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    AtomicInteger stale = new AtomicInteger();
    AtomicLong originalId = new AtomicLong();
    AtomicLong originalTime = new AtomicLong();
    long start = System.nanoTime();
    settings.set("source", "auto");
    settings.set("provider", "local");
    settings.set("target", "rus_Cyrl");
    settings.set("asr", "whisper-base");
    settings.set("backend", "CPU");
    InferenceWorker old = null, current = null;
    try {
      old =
          new InferenceWorker(
              context,
              new InferenceWorker.Listener() {
                public void caption(CaptionHistory.Entry entry) {
                  stale.incrementAndGet();
                }

                public void status(String value) {}

                public void failed(String value) {
                  stale.incrementAndGet();
                }
              });
      old.submit(FixtureAudio.read(context), "Собеседник", old.revision());
      old.close();
      current =
          new InferenceWorker(
              context,
              new InferenceWorker.Listener() {
                public void caption(CaptionHistory.Entry entry) {
                  if (entry.translated() == null) {
                    originalId.set(entry.id());
                    originalTime.set(System.nanoTime());
                    return;
                  }
                  if (originalId.get() != entry.id()) {
                    error.set("Translation did not update the recognized utterance");
                  }
                  caption.set(
                      new String[] {
                        entry.speaker(), entry.original(), entry.translated(), entry.language()
                      });
                  done.countDown();
                }

                public void status(String value) {}

                public void failed(String value) {
                  error.set(value);
                  done.countDown();
                }
              });
      current.submit(FixtureAudio.read(context), "Собеседник", current.revision());
      assertTrue("Offline pipeline timed out", done.await(45, TimeUnit.SECONDS));
      assertNull(error.get(), error.get());
      assertNotNull(caption.get());
      assertTrue("Original was not published before translation", originalTime.get() > 0);
      Benchmark.save(context, "original-before-translation", originalTime.get(), caption.get()[2]);
      assertEquals("Собеседник", caption.get()[0]);
      assertFalse(caption.get()[1].isBlank());
      assertTrue(
          "Expected Russian caption: " + caption.get()[2],
          caption.get()[2].matches("(?s).*[А-Яа-я].*"));
      assertFalse(caption.get()[3].isBlank());
      assertEquals("Old session delivered a late result", 0, stale.get());
      Benchmark.save(
          context, "pipeline-auto-ru-replacement", start, java.util.Arrays.toString(caption.get()));
    } finally {
      if (old != null) old.close();
      if (current != null) current.close();
      settings.set("source", source);
      settings.set("provider", provider);
      settings.set("target", target);
      settings.set("asr", asr);
      settings.set("backend", backend);
    }
  }
}
