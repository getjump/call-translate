package dev.getjump.calltranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.SerbianText;
import dev.getjump.calltranslate.ml.NllbEngine;
import dev.getjump.calltranslate.ml.npu.NpuEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NpuDeviceTest {
  @Test
  public void turboNpuRecognizesSerbianInDedicatedProcess() throws Exception {
    Context context = context();
    try (NpuEngine engine = new NpuEngine(context, modelDirectory(context))) {
      long started = System.nanoTime();
      String[] result = engine.transcribe(FixtureAudio.read(context), "sr");
      Benchmark.save(context, "turbo-qnn-integrated-sr", started, result[0]);
      assertEquals("sr", result[1]);
      assertSerbianFixture(result[0]);
      ActivityManager manager = context.getSystemService(ActivityManager.class);
      var process =
          manager.getRunningAppProcesses().stream()
              .filter(value -> value.processName.equals(context.getPackageName() + ":asr_npu"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Dedicated NPU process is missing"));
      assertNotEquals("NPU shares the main process", Process.myPid(), process.pid);
      assertFalse(
          "QNN ORT loaded into main process", mainMappings().contains("libonnxruntime_qnn.so"));
    }
  }

  @Test
  public void autoUsesDetectedLanguageTokenAndReturnsTranscript() throws Exception {
    Context context = context();
    try (NpuEngine engine = new NpuEngine(context, modelDirectory(context))) {
      long started = System.nanoTime();
      String[] result = engine.transcribe(FixtureAudio.read(context), "auto");
      Benchmark.save(context, "turbo-qnn-integrated-auto-" + result[1], started, result[0]);
      assertTrue(
          "Unexpected fixture language: " + result[1],
          Set.of("sr", "hr", "bs").contains(result[1]));
      assertSerbianFixture(result[0]);
      assertThrows(
          IllegalArgumentException.class,
          () -> engine.transcribe(FixtureAudio.read(context), "zz"));
      assertThrows(IllegalArgumentException.class, () -> engine.transcribe(new float[0], "sr"));
      assertThrows(
          IllegalArgumentException.class, () -> engine.transcribe(new float[480001], "sr"));
    }
  }

  @Test
  public void thirtySecondSharedMemoryInputAvoidsBinderSizeLimit() throws Exception {
    Context context = context();
    float[] samples = Arrays.copyOf(FixtureAudio.read(context), 480000);
    try (NpuEngine engine = new NpuEngine(context, modelDirectory(context))) {
      String[] result = engine.transcribe(samples, "sr");
      assertSerbianFixture(result[0]);
      assertEquals("sr", result[1]);
    }
  }

  @Test
  public void cancellationIsBoundedAndDoesNotPoisonTheNextRequest() throws Exception {
    Context context = context();
    float[] samples = FixtureAudio.read(context);
    var worker = Executors.newSingleThreadExecutor();
    try (NpuEngine engine = new NpuEngine(context, modelDirectory(context))) {
      CountDownLatch started = new CountDownLatch(1);
      var inference =
          worker.submit(
              () -> {
                started.countDown();
                return engine.transcribe(samples, "sr");
              });
      assertTrue("Inference did not start", started.await(2, TimeUnit.SECONDS));
      long deadline = SystemClock.elapsedRealtime() + 2000;
      while (!inference.isDone() && SystemClock.elapsedRealtime() < deadline) {
        engine.cancel();
        SystemClock.sleep(10);
      }
      ExecutionException canceled =
          assertThrows(ExecutionException.class, () -> inference.get(3, TimeUnit.SECONDS));
      assertTrue(
          "Unexpected cancellation: " + canceled.getCause(),
          canceled.getCause() instanceof CancellationException);
      engine.cancel();
      String[] resumed = engine.transcribe(samples, "sr");
      assertEquals("sr", resumed[1]);
      assertSerbianFixture(resumed[0]);
    } finally {
      worker.shutdownNow();
      assertTrue("Cancellation worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
    }
    NpuEngine closed = new NpuEngine(context, modelDirectory(context));
    closed.close();
    closed.close();
    closed.cancel();
    assertThrows(IllegalStateException.class, () -> closed.transcribe(samples, "sr"));
  }

  @Test
  public void closeDuringInferenceDoesNotFreeLiveNativeBuffers() throws Exception {
    Context context = context();
    float[] samples = FixtureAudio.read(context);
    NpuEngine engine = new NpuEngine(context, modelDirectory(context));
    var worker = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch started = new CountDownLatch(1);
      var inference =
          worker.submit(
              () -> {
                started.countDown();
                return engine.transcribe(samples, "sr");
              });
      assertTrue("Inference did not start", started.await(2, TimeUnit.SECONDS));
      SystemClock.sleep(30);
      engine.close();
      ExecutionException closed =
          assertThrows(ExecutionException.class, () -> inference.get(3, TimeUnit.SECONDS));
      assertTrue(
          "Unexpected close result: " + closed.getCause(),
          closed.getCause() instanceof CancellationException
              || closed.getCause() instanceof IllegalStateException);
      assertThrows(IllegalStateException.class, () -> engine.transcribe(samples, "sr"));
      try (NpuEngine replacement = new NpuEngine(context, modelDirectory(context))) {
        assertSerbianFixture(replacement.transcribe(samples, "sr")[0]);
      }
    } finally {
      engine.close();
      worker.shutdownNow();
      assertTrue("Close worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void nllbCpuAndQnnAsrCoexistInSeparateProcesses() throws Exception {
    Context context = context();
    LanguageCatalog languages = new LanguageCatalog(context);
    float[] samples = FixtureAudio.read(context);
    var worker = Executors.newSingleThreadExecutor();
    try (NllbEngine nllb = new NllbEngine(new File(context.getFilesDir(), "models/nllb-600m"));
        NpuEngine npu = new NpuEngine(context, modelDirectory(context))) {
      String original = npu.transcribe(samples, "sr")[0];
      var nextAsr = worker.submit(() -> npu.transcribe(samples, "sr"));
      String translated =
          nllb.translate(
              SerbianText.toCyrillic(original),
              languages.source("sr").token,
              languages.target("rus_Cyrl").token);
      assertFalse("Concurrent NLLB returned empty text", translated.isBlank());
      assertTrue("Concurrent NLLB lost keypad number: " + translated, translated.contains("1"));
      assertSerbianFixture(nextAsr.get(10, TimeUnit.SECONDS)[0]);
      String mappings = mainMappings();
      assertTrue(
          "Standard NLLB ORT missing in main process", mappings.contains("libonnxruntime.so"));
      assertFalse("QNN ORT shares NLLB process", mappings.contains("libonnxruntime_qnn.so"));
      assertFalse("HTP backend shares NLLB process", mappings.contains("libQnnHtp.so"));
    } finally {
      worker.shutdownNow();
      assertTrue(
          "Concurrent ASR worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static void assertSerbianFixture(String original) {
    assertFalse("NPU returned empty Serbian text", original.isBlank());
    assertTrue("NPU lost keypad number: " + original, original.contains("1"));
    String normalized = SerbianText.toCyrillic(original).toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        "NPU lost SIM context: " + original,
        normalized.contains("сим") || normalized.contains("sim"));
    assertTrue("NPU lost internet context: " + original, normalized.contains("интернет"));
  }

  private static Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  private static File modelDirectory(Context context) {
    return new File(context.getFilesDir(), "models/whisper-turbo-qnn");
  }

  private static String mainMappings() throws Exception {
    return new String(
        Files.readAllBytes(new File("/proc/self/maps").toPath()), StandardCharsets.UTF_8);
  }
}
