package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ShortContextDeviceTest {
  @Test
  public void compareShortAudioContextAgainstFullEncoder() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var model = new File(context.getFilesDir(), "models/whisper-small-q5/ggml-small-q5_1.bin");
    try (var engine = new NativeEngine(model.getPath(), false)) {
      float[] audio = FixtureAudio.read(context);
      for (int size : new int[] {0, 640}) {
        long start = System.nanoTime();
        String[] result = engine.transcribe(audio, "sr", size);
        Benchmark.save(context, "small-context-" + size + "-10s", start, result[0]);
        assertTrue(
            "Missing key words: " + result[0], result[0].toLowerCase().contains("telefoniji"));
        assertTrue(
            "Missing menu choice: " + result[0],
            result[0].contains("1") || result[0].contains("jedan"));
      }
      for (int offset = 0; offset < audio.length; offset += 64000) {
        float[] part = Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + 64000));
        long start = System.nanoTime();
        String[] result = engine.transcribe(part, "sr", 384);
        Benchmark.save(context, "small-context-384-4s-" + offset, start, result[0]);
        assertFalse("Empty chunk at " + offset, result[0].isBlank());
      }
      try (var automatic = new NativeEngine(model.getPath(), false)) {
        long start = System.nanoTime();
        String[] result = automatic.transcribe(audio, "auto", 640);
        Benchmark.save(context, "small-context-640-auto-10s", start, result[1] + ": " + result[0]);
        assertFalse("Automatic language is missing", result[1].isBlank());
        assertTrue(
            "Missing key words in Auto: " + result[0],
            result[0].toLowerCase().contains("telefoniji"));
      }
      if (NativeEngine.gpuAvailable()) {
        try (var gpu = new NativeEngine(model.getPath(), true)) {
          long start = System.nanoTime();
          String[] result = gpu.transcribe(audio, "sr", 640);
          Benchmark.save(context, "small-context-640-vulkan-10s", start, result[0]);
          assertTrue(
              "Missing GPU words: " + result[0], result[0].toLowerCase().contains("telefoniji"));
        }
      }
    }
  }
}
