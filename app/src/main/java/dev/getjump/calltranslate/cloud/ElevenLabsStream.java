package dev.getjump.calltranslate.cloud;

import android.util.Base64;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

public final class ElevenLabsStream implements AutoCloseable {
  public interface Listener {
    void status(String message);

    void transcript(long segmentId, String text, String language, boolean committed);

    void failed(String message);
  }

  private static final int chunkSamples = 1600;
  private static final int maxPendingChunks = 20;
  private static final long maxSocketBytes = 98304;
  private static final Map<String, String> isoLanguages = isoLanguages();
  private static final OkHttpClient client =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.SECONDS)
          .pingInterval(15, TimeUnit.SECONDS)
          .build();
  private final Object lock = new Object();
  private final Listener listener;
  private final String source;
  private final long languageTimeoutMs;
  private final ScheduledThreadPoolExecutor deadlines;
  private final ArrayDeque<String> audio = new ArrayDeque<>();
  private final ArrayDeque<Segment> pending = new ArrayDeque<>();
  private final LinkedHashSet<String> timestamps = new LinkedHashSet<>();
  private final byte[] chunk = new byte[chunkSamples * 2];
  private WebSocket socket;
  private ScheduledFuture<?> connectionTimeout;
  private boolean closed;
  private boolean started;
  private int bufferedSamples;
  private long segmentId = 1;

  private static final class Segment {
    final long id;
    final String text;
    ScheduledFuture<?> deadline;

    Segment(long id, String text) {
      this.id = id;
      this.text = text;
    }
  }

  public ElevenLabsStream(String apiKey, String modelId, String sourceLanguage, Listener listener)
      throws Exception {
    this(apiKey, modelId, sourceLanguage, listener, client, 5000, 12000);
  }

  ElevenLabsStream(
      String apiKey,
      String modelId,
      String sourceLanguage,
      Listener listener,
      WebSocket.Factory sockets,
      long languageTimeoutMs,
      long connectionTimeoutMs)
      throws Exception {
    String key = ElevenLabsApi.validatedKey(apiKey);
    if (!ElevenLabsApi.REALTIME_MODEL.equals(modelId))
      throw new IllegalArgumentException("This ElevenLabs model does not support realtime ASR");
    if (listener == null) throw new IllegalArgumentException("ElevenLabs listener is required");
    String language =
        sourceLanguage == null ? "auto" : sourceLanguage.trim().toLowerCase(Locale.ROOT);
    if (!language.equals("auto") && !language.matches("[a-z]{2,3}"))
      throw new IllegalArgumentException("ElevenLabs source language must be an ISO language code");
    this.listener = listener;
    source = language.equals("auto") ? "" : normalizeLanguage(language);
    this.languageTimeoutMs = languageTimeoutMs;
    deadlines =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "elevenlabs-stream-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    deadlines.setRemoveOnCancelPolicy(true);
    HttpUrl.Builder url =
        HttpUrl.get("https://api.elevenlabs.io/v1/speech-to-text/realtime")
            .newBuilder()
            .addQueryParameter("model_id", modelId)
            .addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("commit_strategy", "vad")
            .addQueryParameter("vad_silence_threshold_secs", "0.5")
            .addQueryParameter("min_speech_duration_ms", "100")
            .addQueryParameter("min_silence_duration_ms", "100")
            .addQueryParameter("include_language_detection", "true")
            .addQueryParameter("include_timestamps", "true");
    if (!source.isEmpty()) url.addQueryParameter("language_code", source);
    Request request =
        new Request.Builder()
            .url(url.build().toString().replaceFirst("^https:", "wss:"))
            .header("xi-api-key", key)
            .build();
    synchronized (lock) {
      listener.status("ElevenLabs: connecting");
      connectionTimeout =
          deadlines.schedule(
              () -> fail("ElevenLabs connection timed out; check the network"),
              connectionTimeoutMs,
              TimeUnit.MILLISECONDS);
      try {
        socket = sockets.newWebSocket(request, new Events());
        if (closed) socket.cancel();
      } catch (RuntimeException error) {
        shutdownLocked();
        throw new IllegalStateException("Cannot open the ElevenLabs connection", error);
      }
    }
  }

  public boolean send(float[] samples) {
    synchronized (lock) {
      if (closed) return false;
      if (samples == null) {
        failLocked("ElevenLabs received an invalid audio buffer");
        return false;
      }
      for (float sample : samples) {
        float bounded = Float.isFinite(sample) ? Math.max(-1f, Math.min(1f, sample)) : 0f;
        int pcm = Math.round(bounded * (bounded < 0 ? 32768 : 32767));
        chunk[bufferedSamples * 2] = (byte) pcm;
        chunk[bufferedSamples * 2 + 1] = (byte) (pcm >> 8);
        bufferedSamples++;
        if (bufferedSamples == chunkSamples) {
          bufferedSamples = 0;
          String message;
          try {
            message =
                new JSONObject()
                    .put("message_type", "input_audio_chunk")
                    .put("audio_base_64", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    .put("commit", false)
                    .put("sample_rate", 16000)
                    .toString();
          } catch (Exception error) {
            failLocked("Cannot encode ElevenLabs audio");
            return false;
          }
          if (started) {
            if (!writeLocked(message)) return false;
          } else {
            if (audio.size() >= maxPendingChunks) {
              failLocked(
                  "ElevenLabs did not connect before the audio buffer filled; restart the session");
              return false;
            }
            audio.addLast(message);
          }
        }
      }
      return !closed;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      shutdownLocked();
    }
  }

  private boolean writeLocked(String message) {
    if (closed) return false;
    if (socket == null || socket.queueSize() + message.length() > maxSocketBytes) {
      failLocked("ElevenLabs network is too slow; the bounded audio queue filled");
      return false;
    }
    if (!socket.send(message)) {
      failLocked("ElevenLabs connection stopped accepting audio; restart the session");
      return false;
    }
    return true;
  }

  private void receive(String json) {
    synchronized (lock) {
      if (closed) return;
      try {
        JSONObject event = new JSONObject(json);
        String type = event.getString("message_type");
        switch (type) {
          case "session_started" -> {
            if (started) return;
            started = true;
            connectionTimeout.cancel(false);
            listener.status("ElevenLabs: streaming");
            while (!audio.isEmpty() && !closed) writeLocked(audio.removeFirst());
          }
          case "partial_transcript" -> {
            String text = event.getString("text").trim();
            if (!text.isEmpty()) listener.transcript(segmentId, text, source, false);
          }
          case "committed_transcript" -> commitLocked(event.getString("text").trim());
          case "committed_transcript_with_timestamps" -> timedCommitLocked(event);
          case "warning" ->
              listener.status("ElevenLabs returned a session warning; check account settings");
          default -> {
            if (event.has("error") || type.endsWith("_error"))
              failLocked(ElevenLabsErrors.event(type));
          }
        }
      } catch (Exception error) {
        failLocked("ElevenLabs returned an invalid realtime response");
      }
    }
  }

  private void commitLocked(String text) {
    if (text.isEmpty()) return;
    long id = segmentId++;
    if (!source.isEmpty()) {
      listener.transcript(id, text, source, true);
      return;
    }
    if (pending.size() >= 16) {
      failLocked("ElevenLabs language metadata queue filled; select a fixed source language");
      return;
    }
    Segment segment = new Segment(id, text);
    pending.addLast(segment);
    segment.deadline =
        deadlines.schedule(
            () -> missingLanguage(segment), languageTimeoutMs, TimeUnit.MILLISECONDS);
    listener.transcript(id, text, "", false);
  }

  private void timedCommitLocked(JSONObject event) throws Exception {
    if (!source.isEmpty()) return;
    String text = event.getString("text").trim();
    if (text.isEmpty()) return;
    String language = normalizeLanguage(event.optString("language_code", ""));
    String signature = text + "\u0000" + language + "\u0000" + event.optString("words", "");
    if (timestamps.contains(signature)) return;
    Segment match = null;
    for (Segment segment : pending) {
      if (!segment.text.equals(text)) continue;
      if (match != null) {
        failLocked(
            "ElevenLabs returned ambiguous language metadata; select a fixed source language");
        return;
      }
      match = segment;
    }
    if (match == null) {
      failLocked("ElevenLabs returned language metadata without a matching segment");
      return;
    }
    if (language.isEmpty()) {
      failLocked(
          "ElevenLabs did not detect this segment's language; select a fixed source language");
      return;
    }
    pending.remove(match);
    match.deadline.cancel(false);
    timestamps.add(signature);
    if (timestamps.size() > 64) timestamps.remove(timestamps.iterator().next());
    listener.transcript(match.id, match.text, language, true);
  }

  private void missingLanguage(Segment segment) {
    synchronized (lock) {
      if (!closed && pending.contains(segment))
        failLocked("ElevenLabs language detection timed out; select a fixed source language");
    }
  }

  private void fail(String message) {
    synchronized (lock) {
      failLocked(message);
    }
  }

  private void failLocked(String message) {
    if (closed) return;
    shutdownLocked();
    listener.failed(message);
  }

  private void shutdownLocked() {
    if (closed) return;
    closed = true;
    started = false;
    bufferedSamples = 0;
    audio.clear();
    pending.clear();
    timestamps.clear();
    deadlines.shutdownNow();
    if (socket != null) socket.cancel();
  }

  private static String normalizeLanguage(String language) {
    String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z]{2,3}")) return "";
    return isoLanguages.getOrDefault(normalized, normalized);
  }

  private static Map<String, String> isoLanguages() {
    Map<String, String> result = new HashMap<>();
    for (String code : Locale.getISOLanguages()) {
      try {
        Locale locale = Locale.forLanguageTag(code);
        result.put(locale.getISO3Language(), locale.getLanguage());
      } catch (java.util.MissingResourceException ignored) {
      }
    }
    result.put("cmn", "zh");
    result.put("fil", "tl");
    result.put("jw", "jv");
    return Map.copyOf(result);
  }

  private final class Events extends WebSocketListener {
    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      synchronized (lock) {
        if (closed) {
          webSocket.cancel();
          return;
        }
        socket = webSocket;
        listener.status("ElevenLabs: connected, starting transcription");
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      if (text.length() > 1048576) {
        fail("ElevenLabs realtime response exceeded the size limit");
        return;
      }
      receive(text);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      String message =
          response == null
              ? "ElevenLabs connection failed; check the network and restart the session"
              : ElevenLabsErrors.http(response.code(), "");
      if (response != null) response.close();
      fail(message);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      fail("ElevenLabs disconnected (" + code + "); restart the session");
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      fail("ElevenLabs disconnected (" + code + "); restart the session");
    }
  }
}
