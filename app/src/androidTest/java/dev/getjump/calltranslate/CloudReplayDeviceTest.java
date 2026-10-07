package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.CaptionHistory;
import dev.getjump.calltranslate.ml.InferenceWorker;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CloudReplayDeviceTest {
  private record Seen(long milliseconds, CaptionHistory.Entry entry) {}

  @Test
  public void resumeSimMonitoringWhenExplicitlyRequested() throws Exception {
    Assume.assumeTrue(
        InstrumentationRegistry.getArguments().getString("resume_sim", "false").equals("true"));
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    assertEquals("elevenlabs", new SettingsStore(context).provider());
    assertTrue(
        "Shizuku permission is unavailable",
        dev.getjump.calltranslate.capture.ShizukuConnection.permitted());
    instrumentation.runOnMainSync(
        () ->
            context.startForegroundService(
                new android.content.Intent(context, CallService.class).setAction(CallService.SIM)));
    long deadline = SystemClock.elapsedRealtime() + 40000;
    while (SystemClock.elapsedRealtime() < deadline
        && !CallService.latestStatus.equals("Модели готовы. Ожидаю SIM-звонок"))
      SystemClock.sleep(100);
    assertTrue("SIM monitoring did not start: " + CallService.latestStatus, CallService.running);
    assertEquals("Модели готовы. Ожидаю SIM-звонок", CallService.latestStatus);
  }

  @Test
  public void recordedCallShowsDraftTranslationBeforeFinalText() throws Exception {
    String recording = InstrumentationRegistry.getArguments().getString("replay_recording", "");
    Assume.assumeFalse("Replay requires an explicit recorded-call argument", recording.isBlank());
    assertTrue("Invalid recording name", recording.matches("test-[0-9]+-[a-f0-9-]{36}"));
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var settings = new SettingsStore(context);
    assertEquals("elevenlabs", settings.provider());
    assertEquals("sr", settings.source());
    assertEquals("rus_Cyrl", settings.target());
    byte[] pcm =
        Files.readAllBytes(
            new File(context.getNoBackupFilesDir(), "call-tests/" + recording + "/downlink.pcm")
                .toPath());
    assertTrue("Recorded call is too short", pcm.length >= 35 * 32000);
    File output =
        new File(context.getNoBackupFilesDir(), "call-tests/replay-" + System.currentTimeMillis());
    assertTrue("Cannot create replay diagnostics", output.mkdirs());
    File events = new File(output, "events.jsonl");
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<String> failure = new AtomicReference<>();
    AtomicLong started = new AtomicLong();
    List<Seen> captions = new ArrayList<>();
    try (InferenceWorker worker =
        new InferenceWorker(
            context,
            new InferenceWorker.Listener() {
              @Override
              public void caption(CaptionHistory.Entry entry) {
                long elapsed = (System.nanoTime() - started.get()) / 1000000;
                synchronized (captions) {
                  captions.add(new Seen(elapsed, entry));
                }
                try {
                  JSONObject event =
                      new JSONObject()
                          .put("elapsedMs", elapsed)
                          .put("id", entry.id())
                          .put("original", entry.original())
                          .put(
                              "translated",
                              entry.translated() == null ? JSONObject.NULL : entry.translated())
                          .put("language", entry.language())
                          .put("draft", entry.draft());
                  Files.write(
                      events.toPath(),
                      (event + "\n").getBytes(StandardCharsets.UTF_8),
                      StandardOpenOption.CREATE,
                      StandardOpenOption.APPEND);
                } catch (Exception error) {
                  failure.set("Cannot save replay captions: " + error.getMessage());
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
              }
            })) {
      assertTrue("Models did not become ready", ready.await(40, TimeUnit.SECONDS));
      assertNull(failure.get(), failure.get());
      assertTrue(worker.ready());
      assertTrue(worker.streaming());
      worker.beginSession(true);
      started.set(System.nanoTime());
      long playback = SystemClock.elapsedRealtime();
      for (int chunk = 0; chunk < 370; chunk++) {
        assertNull(failure.get(), failure.get());
        float[] samples = new float[1600];
        if (chunk < 350) {
          int offset = chunk * 3200;
          for (int i = 0; i < samples.length; i++)
            samples[i] =
                (short) (((pcm[offset + 2 * i + 1] & 255) << 8) | (pcm[offset + 2 * i] & 255))
                    / 32768f;
        }
        assertTrue(
            "Audio chunk rejected: " + chunk,
            worker.submit(samples, "Собеседник", worker.revision()));
        long remaining = playback + (chunk + 1) * 100L - SystemClock.elapsedRealtime();
        if (remaining > 0) SystemClock.sleep(remaining);
      }
      boolean early = false;
      long lead = 0;
      int drafts = 0;
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (SystemClock.elapsedRealtime() < deadline) {
        assertNull(failure.get(), failure.get());
        List<Seen> snapshot;
        synchronized (captions) {
          snapshot = List.copyOf(captions);
        }
        drafts = 0;
        for (Seen caption : snapshot) {
          String translated = caption.entry().translated();
          if (translated == null) continue;
          assertFalse(
              "Translation failed: " + translated,
              translated.startsWith("Ошибка перевода:")
                  || translated.startsWith("Перевод недоступен")
                  || translated.startsWith("Перевод пропущен"));
          assertTrue(
              "Missing Russian translation: " + translated, translated.matches("(?s).*[А-Яа-я].*"));
        }
        for (Seen draft : snapshot) {
          if (!draft.entry().draft() || draft.entry().translated() == null) continue;
          assertTrue(
              "Draft has no Russian translation: " + draft.entry().translated(),
              draft.entry().translated().matches("(?s).*[А-Яа-я].*"));
          drafts++;
          for (Seen committed : snapshot) {
            if (committed.entry().id() != draft.entry().id()
                || committed.entry().draft()
                || committed.entry().translated() == null
                || draft.entry().original().length() >= committed.entry().original().length())
              continue;
            lead = Math.max(lead, committed.milliseconds() - draft.milliseconds());
            early |= lead >= 1000;
          }
        }
        if (early) break;
        SystemClock.sleep(100);
      }
      JSONObject summary =
          new JSONObject()
              .put("recording", recording)
              .put("audioSeconds", 35)
              .put("draftUpdates", drafts)
              .put("maximumLeadVsFinalTranslationMs", lead)
              .put("earlyTranslation", early);
      Files.write(
          new File(output, "summary.json").toPath(),
          (summary + "\n").getBytes(StandardCharsets.UTF_8));
      assertTrue("No translated draft preceded final text by at least 1s: " + summary, early);
      android.os.Bundle status = new android.os.Bundle();
      status.putString(
          "stream", "Cloud replay diagnostics: " + output.getName() + "\n" + summary + "\n");
      InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
  }
}
