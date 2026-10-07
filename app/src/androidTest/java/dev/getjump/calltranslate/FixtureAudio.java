package dev.getjump.calltranslate;

import android.content.Context;
import java.io.DataInputStream;

final class FixtureAudio {
  static float[] read(Context context) throws Exception {
    try (DataInputStream input =
        new DataInputStream(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("test-serbian.pcm"))) {
      byte[] bytes = input.readAllBytes();
      float[] result = new float[bytes.length / 2];
      for (int i = 0; i < result.length; i++) {
        result[i] = (short) (((bytes[2 * i + 1] & 255) << 8) | (bytes[2 * i] & 255)) / 32768f;
      }
      return result;
    }
  }
}
