package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import dev.getjump.calltranslate.ml.NllbEngine;
import java.io.File;
import java.util.concurrent.CancellationException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CancellationDeviceTest {
  @Test
  public void cancellationBeforeJniEntryRemainsLatched() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (var engine =
        new NativeEngine(
            new File(context.getFilesDir(), "models/whisper-base/ggml-base.bin").getPath(),
            false)) {
      engine.cancel();
      assertThrows(
          IllegalStateException.class, () -> engine.transcribe(FixtureAudio.read(context), "sr"));
    }
  }

  @Test
  public void canceledLoadDoesNotOpenTranslationModels() {
    assertThrows(
        CancellationException.class, () -> new NllbEngine(new File("/missing-models"), () -> true));
  }
}
