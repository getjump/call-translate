package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
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
public class ConversationDeviceTest {
  @Test
  public void conversationSurvivesRecreationAndDoesNotJumpWhileReadingOlderMessages()
      throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var previous = CallService.history.snapshot();
    CallService.history.clear();
    for (int i = 1; i <= 30; i++)
      CallService.history.update(
          new CaptionHistory.Entry(i, "Собеседник", "Dobar dan " + i, null, "sr", 100, 100));
    try (var scenario = ActivityScenario.launch(MainActivity.class)) {
      instrumentation.waitForIdleSync();
      scenario.onActivity(
          activity -> {
            ScrollView scroll = activity.findViewById(R.id.caption_scroll);
            assertNotNull(scroll);
            assertTrue("Recognized original is hidden", contains(scroll, "Dobar dan 1"));
            assertTrue("Translation has no pending state", contains(scroll, "Переводится..."));
            scroll.scrollTo(0, 0);
          });
      instrumentation.waitForIdleSync();
      CallService.history.update(
          new CaptionHistory.Entry(1, "Собеседник", "Dobar dan 1", "Добрый день", "sr", 100, 300));
      CallService.history.update(
          new CaptionHistory.Entry(31, "Собеседник", "Nova poruka", null, "sr", 100, 100));
      context.sendBroadcast(new Intent(CallService.UPDATE).setPackage(context.getPackageName()));
      java.util.concurrent.atomic.AtomicBoolean updated =
          new java.util.concurrent.atomic.AtomicBoolean();
      long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
      while (!updated.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
        instrumentation.waitForIdleSync();
        scenario.onActivity(
            activity ->
                updated.set(contains(activity.findViewById(R.id.caption_scroll), "Добрый день")));
        if (!updated.get()) android.os.SystemClock.sleep(16);
      }
      assertTrue("Caption update broadcast was not rendered", updated.get());
      scenario.onActivity(
          activity -> {
            ScrollView scroll = activity.findViewById(R.id.caption_scroll);
            assertEquals("Appending a caption moved the reader", 0, scroll.getScrollY());
            assertTrue("Original disappeared after translation", contains(scroll, "Dobar dan 1"));
            assertTrue("Translation is missing", contains(scroll, "Добрый день"));
            assertEquals(View.VISIBLE, activity.findViewById(R.id.caption_latest).getVisibility());
            assertEquals(
                "A translation added a duplicate bubble",
                31,
                ((ViewGroup) scroll.getChildAt(0)).getChildCount());
          });
      android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
      assertNotNull("Could not inspect conversation UI", screenshot);
      try (var output =
          new java.io.FileOutputStream(
              new java.io.File(context.getFilesDir(), "conversation-ui.png"))) {
        assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
      } finally {
        screenshot.recycle();
      }
      scenario.recreate();
      instrumentation.waitForIdleSync();
      scenario.onActivity(
          activity ->
              assertTrue(
                  "Activity recreation lost the conversation",
                  contains(activity.findViewById(R.id.caption_scroll), "Dobar dan 1")));
    } finally {
      CallService.history.clear();
      for (var entry : previous) CallService.history.update(entry);
    }
  }

  @Test
  public void draftTranslationUpdatesTheSameBubbleAndClearsItsLabelAtFinalization() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.runOnMainSync(
        () -> {
          var feed = new CaptionFeed(instrumentation.getTargetContext(), 300);
          feed.render(
              java.util.List.of(
                  new CaptionHistory.Entry(
                      100,
                      "Собеседник",
                      "Za informacije o mobilnoj",
                      "Для информации о",
                      "sr",
                      100,
                      200,
                      true)));
          ScrollView scroll = feed.findViewById(R.id.caption_scroll);
          assertTrue(contains(scroll, "Собеседник [sr] | Черновик"));
          feed.render(
              java.util.List.of(
                  new CaptionHistory.Entry(
                      100,
                      "Собеседник",
                      "Za informacije o mobilnoj telefoniji",
                      "Для информации о мобильной телефонии",
                      "sr",
                      300,
                      400,
                      false)));
          assertTrue(contains(scroll, "Za informacije o mobilnoj telefoniji"));
          assertTrue(contains(scroll, "Для информации о мобильной телефонии"));
          assertFalse(contains(scroll, "Собеседник [sr] | Черновик"));
          assertTrue(contains(scroll, "Собеседник [sr]"));
          assertEquals(1, ((ViewGroup) scroll.getChildAt(0)).getChildCount());
        });
  }

  @Test
  public void warmModelsAreReusedAcrossSessionsAndOldTranslationIsRejected() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var settings = new SettingsStore(context);
    String source = settings.source(),
        target = settings.target(),
        asr = settings.asr(),
        backend = settings.backend();
    CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
    AtomicInteger starts = new AtomicInteger(),
        originals = new AtomicInteger(),
        translations = new AtomicInteger();
    AtomicLong firstId = new AtomicLong();
    AtomicReference<String> error = new AtomicReference<>();
    AtomicReference<InferenceWorker> reference = new AtomicReference<>();
    settings.set("source", "sr");
    settings.set("target", "rus_Cyrl");
    settings.set("asr", "whisper-small-q5");
    settings.set("backend", "CPU");
    long startup = System.nanoTime();
    try (var worker =
        new InferenceWorker(
            context,
            new InferenceWorker.Listener() {
              public void ready(long ms) {
                starts.incrementAndGet();
                ready.countDown();
              }

              public void status(String value) {}

              public void failed(String value) {
                error.set(value);
                ready.countDown();
                done.countDown();
              }

              public void caption(CaptionHistory.Entry entry) {
                if (entry.translated() == null) {
                  if (originals.incrementAndGet() == 1) {
                    firstId.set(entry.id());
                    var current = reference.get();
                    long oldCaptureSession = current.session();
                    current.endSession();
                    assertTrue("Models were unloaded at pause", current.ready());
                    current.beginSession(false);
                    try {
                      assertFalse(
                          "Old capture token was accepted",
                          current.submit(
                              FixtureAudio.read(context),
                              "Старый поток",
                              oldCaptureSession,
                              current.revision()));
                      current.submit(FixtureAudio.read(context), "Собеседник", current.revision());
                    } catch (Exception failure) {
                      error.set(failure.toString());
                      done.countDown();
                    }
                  }
                } else {
                  translations.incrementAndGet();
                  if (entry.id() == firstId.get())
                    error.set("Old session translated after replacement");
                  if (!entry.translated().matches("(?s).*[А-Яа-я].*"))
                    error.set("Missing Russian translation");
                  done.countDown();
                }
              }
            })) {
      reference.set(worker);
      assertTrue("Model preparation timed out", ready.await(45, TimeUnit.SECONDS));
      assertNull(error.get(), error.get());
      Benchmark.save(context, "small-warmup", startup, "ready");
      worker.beginSession(true);
      long start = System.nanoTime();
      worker.submit(FixtureAudio.read(context), "Собеседник", worker.revision());
      assertTrue("Warm replacement timed out", done.await(45, TimeUnit.SECONDS));
      assertNull(error.get(), error.get());
      assertEquals("Models were initialized again", 1, starts.get());
      assertEquals("Original events were lost", 2, originals.get());
      assertEquals("Stale translation reached UI", 1, translations.get());
      Benchmark.save(
          context, "small-warm-session-replacement", start, "two originals, one translation");
    } finally {
      settings.set("source", source);
      settings.set("target", target);
      settings.set("asr", asr);
      settings.set("backend", backend);
    }
  }

  private static boolean contains(View view, String text) {
    if (view instanceof TextView value && value.getText().toString().equals(text)) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        if (contains(group.getChildAt(i), text)) return true;
      }
    return false;
  }
}
