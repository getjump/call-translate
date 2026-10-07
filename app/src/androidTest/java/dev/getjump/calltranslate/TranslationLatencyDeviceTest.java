package dev.getjump.calltranslate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.SerbianText;
import dev.getjump.calltranslate.ml.NllbEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class TranslationLatencyDeviceTest {
  @Test
  public void translateFullNpuTranscript() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    LanguageCatalog languages = new LanguageCatalog(context);
    String original =
        "Za informacije o mobilnoj telefoni i bežičnom kućnom internetu koji koristi SIM karticu,"
            + " pritisni taster 1.";
    String input = SerbianText.toCyrillic(original);
    long load = System.nanoTime();
    try (NllbEngine engine = new NllbEngine(new File(context.getFilesDir(), "models/nllb-600m"))) {
      save(context, new JSONObject().put("kind", "load").put("ms", elapsed(load)));
      for (int iteration = 0; iteration < 3; iteration++) {
        long start = System.nanoTime();
        String output =
            engine.translate(
                input, languages.source("sr").token, languages.target("rus_Cyrl").token);
        double milliseconds = elapsed(start);
        save(
            context,
            new JSONObject()
                .put("kind", "translation")
                .put("iteration", iteration)
                .put("ms", milliseconds)
                .put("original", original)
                .put("input", input)
                .put("text", output));
        assertFalse("Empty translation", output.isBlank());
        assertTrue("Translation lost keypad number: " + output, output.contains("1"));
      }
    }
  }

  private static double elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000.0;
  }

  private static void save(Context context, JSONObject report) throws Exception {
    report.put("time", System.currentTimeMillis());
    Files.write(
        new File(context.getFilesDir(), "translation-latency.jsonl").toPath(),
        (report.toString() + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    Bundle status = new Bundle();
    status.putString("stream", report.toString() + "\n");
    InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
  }
}
