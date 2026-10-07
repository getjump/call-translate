package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SmallModelDeviceTest {
  @Test
  public void smallQuantizedModelRecognizesSerbianOnCpuAndGpu() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var model = new File(context.getFilesDir(), "models/whisper-small-q5/ggml-small-q5_1.bin");
    for (boolean gpu : new boolean[] {false, true}) {
      try (var engine = new NativeEngine(model.getPath(), gpu)) {
        long start = System.nanoTime();
        String[] result = engine.transcribe(FixtureAudio.read(context), "sr");
        Benchmark.save(context, "small-q5-" + (gpu ? "vulkan" : "cpu") + "-10s", start, result[0]);
        assertEquals("sr", result[1]);
        assertFalse(result[0].isBlank());
      }
    }
  }
}
