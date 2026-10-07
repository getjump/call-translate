package dev.getjump.calltranslate;

import android.content.Context;
import android.os.SystemClock;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

final class CallTestRecorder implements AutoCloseable {
  private static final long MAX_PCM_BYTES = 16000L * 2 * 600;
  private final File directory;
  private final BufferedOutputStream events;
  private final Map<String, BufferedOutputStream> streams = new LinkedHashMap<>();
  private final Map<String, Long> bytes = new LinkedHashMap<>();
  private final long started = SystemClock.elapsedRealtime();
  private boolean closed;

  static CallTestRecorder startIfArmed(Context context, boolean sim) throws Exception {
    File flag = new File(context.getNoBackupFilesDir(), "record-next-sim.flag");
    if (!BuildConfig.DEBUG || !sim || !Files.deleteIfExists(flag.toPath())) return null;
    File directory =
        new File(
            context.getNoBackupFilesDir(),
            "call-tests/test-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
    Files.createDirectories(directory.toPath());
    CallTestRecorder recorder = new CallTestRecorder(directory);
    try {
      SettingsStore settings = new SettingsStore(context);
      recorder.event(
          new JSONObject()
              .put("type", "start")
              .put("epochMilliseconds", System.currentTimeMillis())
              .put("sampleRate", 16000)
              .put("encoding", "PCM16_LE")
              .put("channels", 1)
              .put("provider", settings.provider())
              .put(
                  "asr",
                  settings.provider().equals("elevenlabs") ? settings.cloudModel() : settings.asr())
              .put("backend", settings.backend())
              .put("translation", settings.translation())
              .put("source", settings.source())
              .put("target", settings.target())
              .put("owner", settings.owner()));
      return recorder;
    } catch (Exception error) {
      recorder.close();
      throw error;
    }
  }

  CallTestRecorder(File directory) throws IOException {
    this.directory = directory;
    events =
        new BufferedOutputStream(
            Files.newOutputStream(new File(directory, "events.jsonl").toPath()));
  }

  synchronized void audio(String side, byte[] pcm, int count) throws Exception {
    if (closed || count <= 0) return;
    String channel =
        switch (side) {
          case "Собеседник" -> "downlink";
          case "Я" -> "uplink";
          default -> "microphone";
        };
    long previous = bytes.getOrDefault(channel, 0L);
    if (previous >= MAX_PCM_BYTES) return;
    BufferedOutputStream output = streams.get(channel);
    if (output == null) {
      output =
          new BufferedOutputStream(
              Files.newOutputStream(new File(directory, channel + ".pcm").toPath()));
      streams.put(channel, output);
      event(new JSONObject().put("type", "audio-start").put("channel", channel));
    }
    int accepted = (int) Math.min(count, MAX_PCM_BYTES - previous);
    output.write(pcm, 0, accepted);
    bytes.put(channel, previous + accepted);
    if (previous + accepted == MAX_PCM_BYTES)
      event(new JSONObject().put("type", "audio-limit").put("channel", channel));
  }

  synchronized void caption(CaptionHistory.Entry entry) throws Exception {
    event(
        new JSONObject()
            .put("type", "caption")
            .put("id", entry.id())
            .put("speaker", entry.speaker())
            .put("original", entry.original())
            .put("translated", entry.translated() == null ? JSONObject.NULL : entry.translated())
            .put("language", entry.language())
            .put("draft", entry.draft())
            .put("recognitionMilliseconds", entry.recognitionMilliseconds())
            .put("totalMilliseconds", entry.totalMilliseconds()));
  }

  synchronized void status(String value) throws Exception {
    event(new JSONObject().put("type", "status").put("value", value));
  }

  private synchronized void event(JSONObject value) throws Exception {
    if (closed) return;
    value.put("elapsedMilliseconds", SystemClock.elapsedRealtime() - started);
    events.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    events.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    IOException failure = null;
    try {
      event(new JSONObject().put("type", "stop").put("pcmBytes", new JSONObject(bytes)));
    } catch (Exception error) {
      failure = new IOException("Recording summary failed", error);
    }
    closed = true;
    for (BufferedOutputStream output : streams.values()) {
      try {
        output.close();
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    }
    try {
      events.close();
    } catch (IOException error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure != null) throw failure;
  }
}
