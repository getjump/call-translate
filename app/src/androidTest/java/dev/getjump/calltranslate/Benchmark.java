package dev.getjump.calltranslate;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

final class Benchmark {
  static synchronized void save(Context context, String kind, long start, String text)
      throws Exception {
    android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo();
    android.os.Debug.getMemoryInfo(memory);
    JSONObject report =
        new JSONObject()
            .put("kind", kind)
            .put("milliseconds", (System.nanoTime() - start) / 1000000)
            .put("text", text)
            .put("processPssKiB", memory.getTotalPss())
            .put("time", System.currentTimeMillis());
    Files.write(
        new java.io.File(context.getFilesDir(), "benchmark.jsonl").toPath(),
        (report.toString() + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
