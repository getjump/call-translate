package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallTestRecorderDeviceTest {
  @Test
  public void optInIsConsumedOnlyBySimAndRecordsExactPcmAndCaptionUpdates() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File root = new File(context.getCacheDir(), "recording-test-" + UUID.randomUUID());
    Files.createDirectories(root.toPath());
    Context isolated =
        new ContextWrapper(context) {
          @Override
          public File getNoBackupFilesDir() {
            return root;
          }
        };
    try {
      assertNull(CallTestRecorder.startIfArmed(isolated, true));
      File flag = new File(root, "record-next-sim.flag");
      Files.createFile(flag.toPath());
      assertNull(CallTestRecorder.startIfArmed(isolated, false));
      assertTrue(flag.exists());
      CallTestRecorder recorder = CallTestRecorder.startIfArmed(isolated, true);
      assertNotNull(recorder);
      assertFalse(flag.exists());
      assertNull(CallTestRecorder.startIfArmed(isolated, true));
      byte[] pcm = {1, 2, 3, 4, 5, 6};
      recorder.audio("Собеседник", pcm, 4);
      recorder.audio("Собеседник", new byte[] {9, 10}, 2);
      recorder.audio("Я", pcm, pcm.length);
      recorder.caption(new CaptionHistory.Entry(7, "Собеседник", "jedan", null, "sr", 10, 10));
      recorder.caption(new CaptionHistory.Entry(7, "Собеседник", "jedan", "один", "sr", 10, 20));
      recorder.status("Слушаю");
      recorder.close();
      recorder.audio("Собеседник", pcm, pcm.length);
      recorder.close();
      File directory = new File(root, "call-tests").listFiles()[0];
      assertArrayEquals(
          new byte[] {1, 2, 3, 4, 9, 10},
          Files.readAllBytes(new File(directory, "downlink.pcm").toPath()));
      assertArrayEquals(pcm, Files.readAllBytes(new File(directory, "uplink.pcm").toPath()));
      var events = Files.readAllLines(new File(directory, "events.jsonl").toPath());
      var captions =
          events.stream()
              .map(CallTestRecorderDeviceTest::json)
              .filter(v -> v.optString("type").equals("caption"))
              .toList();
      assertEquals(2, captions.size());
      assertEquals(7, captions.get(0).getLong("id"));
      assertEquals(7, captions.get(1).getLong("id"));
      assertTrue(captions.get(0).isNull("translated"));
      assertEquals("один", captions.get(1).getString("translated"));
      assertEquals(20, captions.get(1).getLong("totalMilliseconds"));
      JSONObject stop = json(events.get(events.size() - 1));
      assertEquals("stop", stop.getString("type"));
      assertEquals(6, stop.getJSONObject("pcmBytes").getLong("downlink"));
      assertTrue(stop.getLong("elapsedMilliseconds") >= 0);
    } finally {
      try (var paths = Files.walk(root.toPath())) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static JSONObject json(String value) {
    try {
      return new JSONObject(value);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
