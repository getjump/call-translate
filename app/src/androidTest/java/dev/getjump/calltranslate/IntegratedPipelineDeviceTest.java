package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.CaptionHistory;
import dev.getjump.calltranslate.ml.InferenceWorker;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class IntegratedPipelineDeviceTest {
  @Test
  public void npuPipelineUpdatesOriginalsWithLocalTranslations() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var settings = new SettingsStore(context);
    Map<String, String> saved =
        Map.of(
            "provider",
            settings.provider(),
            "asr",
            settings.asr(),
            "backend",
            settings.backend(),
            "source",
            settings.source(),
            "target",
            settings.target());
    settings.set("provider", "local");
    settings.set("asr", "whisper-turbo-qnn");
    settings.set("backend", "NPU");
    settings.set("source", "sr");
    settings.set("target", "rus_Cyrl");
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch complete = new CountDownLatch(2);
    Map<Long, CaptionHistory.Entry> originals = new ConcurrentHashMap<>();
    Map<Long, CaptionHistory.Entry> translated = new ConcurrentHashMap<>();
    AtomicReference<String> failure = new AtomicReference<>();
    try (InferenceWorker worker =
        new InferenceWorker(
            context,
            new InferenceWorker.Listener() {
              @Override
              public void caption(CaptionHistory.Entry entry) {
                if (entry.translated() == null) originals.put(entry.id(), entry);
                else {
                  if (!originals.containsKey(entry.id()))
                    failure.set("Translation preceded original");
                  translated.put(entry.id(), entry);
                  complete.countDown();
                }
              }

              @Override
              public void ready(long milliseconds) {
                ready.countDown();
              }

              @Override
              public void status(String status) {}

              @Override
              public void failed(String error) {
                failure.set(error);
                ready.countDown();
                while (complete.getCount() > 0) complete.countDown();
              }
            })) {
      assertTrue("Models did not become ready", ready.await(40, TimeUnit.SECONDS));
      assertNull(failure.get(), failure.get());
      assertTrue(worker.ready());
      assertFalse(worker.streaming());
      float[] audio = FixtureAudio.read(context);
      long started = System.nanoTime();
      assertTrue(worker.submit(audio, "Собеседник", worker.revision()));
      assertTrue(worker.submit(audio, "Собеседник", worker.revision()));
      assertTrue("Two-stage pipeline timed out", complete.await(20, TimeUnit.SECONDS));
      assertNull(failure.get(), failure.get());
      assertEquals(2, originals.size());
      assertEquals(originals.keySet(), translated.keySet());
      for (CaptionHistory.Entry entry : translated.values()) {
        assertEquals(originals.get(entry.id()).original(), entry.original());
        assertEquals("sr", entry.language());
        assertTrue(
            "Missing Russian translation: " + entry.translated(),
            entry.translated().matches("(?s).*[А-Яа-я].*"));
        assertTrue("Lost keypad number: " + entry.translated(), entry.translated().contains("1"));
      }
      Benchmark.save(context, "npu-pipeline-two-phrases", started, translated.values().toString());
    } finally {
      saved.forEach(settings::set);
    }
  }
}
