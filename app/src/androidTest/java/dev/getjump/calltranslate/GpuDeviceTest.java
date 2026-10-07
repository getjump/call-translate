package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GpuDeviceTest {
  @Test
  public void gpuRecognizesRealSerbianAudio() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File model = new File(context.getFilesDir(), "models/whisper-base/ggml-base.bin");
    try (NativeEngine engine = new NativeEngine(model.getAbsolutePath(), true)) {
      long start = System.nanoTime();
      String[] result = engine.transcribe(FixtureAudio.read(context), "sr");
      Benchmark.save(context, "base-vulkan-10s", start, result[0]);
      android.util.Log.i(
          "CallTranslateTest",
          "GPU ASR ms=" + (System.nanoTime() - start) / 1000000 + " text=" + result[0]);
      assertFalse(result[0].isBlank());
      assertEquals("sr", result[1]);
    }
  }
}
