package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import dev.getjump.calltranslate.ml.NllbEngine;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EngineDeviceTest {
  @Test
  public void translationPreservesNumberAndNegation() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (NllbEngine engine = new NllbEngine(new File(context.getFilesDir(), "models/nllb-600m"))) {
      long start = System.nanoTime();
      LanguageCatalog languages = new LanguageCatalog(context);
      String text =
          engine.translate(
              "Не долазите у 15 часова.",
              languages.source("sr").token,
              languages.target("rus_Cyrl").token);
      Benchmark.save(context, "mt-sr-ru", start, text);
      android.util.Log.i(
          "CallTranslateTest", "MT ms=" + (System.nanoTime() - start) / 1000000 + " text=" + text);
      assertTrue("Translation lost the number: " + text, text.contains("15"));
      assertTrue("Translation lost negation: " + text, text.toLowerCase().contains("не"));
    }
  }

  @Test
  public void multilingualEngineIsAvailable() {
    assertTrue(NativeEngine.asrLanguages().length > 90);
    assertTrue(NativeEngine.systemInfo().contains("CPU"));
  }

  @Test
  public void whisperRecognizesSerbianFixture() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File model = new File(context.getFilesDir(), "models/whisper-base/ggml-base.bin");
    assertTrue("Whisper model missing", model.isFile());
    float[] audio = FixtureAudio.read(context);
    try (NativeEngine engine = new NativeEngine(model.getAbsolutePath(), false)) {
      long start = System.nanoTime();
      String[] result = engine.transcribe(audio, "sr");
      Benchmark.save(context, "base-cpu-10s", start, result[0]);
      android.util.Log.i(
          "CallTranslateTest",
          "ASR ms="
              + (System.nanoTime() - start) / 1000000
              + " language="
              + result[1]
              + " text="
              + result[0]);
      assertFalse("Serbian fixture produced empty transcript", result[0].isBlank());
      assertEquals("sr", result[1]);
    }
  }
}
