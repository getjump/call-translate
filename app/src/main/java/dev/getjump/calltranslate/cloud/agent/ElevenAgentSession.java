package dev.getjump.calltranslate.cloud.agent;

import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

public final class ElevenAgentSession implements AutoCloseable {
  public record SessionConfig(String taskBrief, String sourceLanguage, String targetLanguage) {
    public SessionConfig {
      taskBrief = taskBrief == null ? "" : taskBrief.trim();
      if (taskBrief.length() > 8000)
        throw new IllegalArgumentException(
            "Agent preferences must contain at most 8000 characters");
      sourceLanguage = language(sourceLanguage, "interlocutor");
      targetLanguage = language(targetLanguage, "owner");
      if (taskBrief.isEmpty())
        taskBrief =
            "First listen. Find out who is calling and why, ask short clarifying questions in "
                + sourceLanguage
                + ". Follow the owner's private instructions when provided. Do not invent the"
                + " owner's details or confirm agreements or bookings without the owner's"
                + " instruction. Do not recite private context aloud.";
    }
  }

  public interface Listener {
    void status(String message);

    void ready(String conversationId, int inputSampleRate, int outputSampleRate);

    void transcript(boolean agent, String text);

    void audio(byte[] pcm16, int sampleRate, long eventId);

    void interrupted(long eventId);

    void failed(String message);

    void closed();
  }

  private static final long maxSocketBytes = 98304;
  private static final int maxMessageCharacters = 1048576;
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
  private final SessionConfig config;
  private final ScheduledThreadPoolExecutor deadlines;
  private WebSocket socket;
  private ScheduledFuture<?> connectionTimeout;
  private boolean closed;
  private boolean opened;
  private boolean ready;
  private int inputSampleRate;
  private int outputSampleRate;
  private long interruptedThrough = -1;
  private long lastAudioEventId = -1;

  public ElevenAgentSession(String signedUrl, SessionConfig config, Listener listener)
      throws Exception {
    this(signedUrl, config, listener, client, 15000);
  }

  ElevenAgentSession(
      String signedUrl,
      SessionConfig config,
      Listener listener,
      WebSocket.Factory sockets,
      long connectionTimeoutMs)
      throws Exception {
    var url = ElevenAgentsApi.validatedSignedUrl(signedUrl);
    if (config == null || listener == null)
      throw new IllegalArgumentException("Agent task and listener are required");
    if (connectionTimeoutMs <= 0)
      throw new IllegalArgumentException("Agent connection timeout must be positive");
    this.config = config;
    this.listener = listener;
    deadlines =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "eleven-agent-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    deadlines.setRemoveOnCancelPolicy(true);
    synchronized (lock) {
      listener.status("ElevenLabs agent: connecting");
      connectionTimeout =
          deadlines.schedule(
              () ->
                  fail(
                      "ElevenLabs agent initialization timed out; check network and agent"
                          + " settings"),
              connectionTimeoutMs,
              TimeUnit.MILLISECONDS);
      try {
        socket = sockets.newWebSocket(new Request.Builder().url(url).build(), new Events());
        if (closed) socket.cancel();
      } catch (RuntimeException error) {
        shutdownLocked(false);
        throw new IllegalStateException("Cannot open the ElevenLabs agent connection");
      }
    }
  }

  public boolean sendPcm16(byte[] pcm16) {
    synchronized (lock) {
      if (closed || !ready) return false;
      if (pcm16 == null
          || pcm16.length == 0
          || pcm16.length % 2 != 0
          || pcm16.length > inputSampleRate * 2) {
        failLocked("Agent audio must be mono PCM16 with chunks of at most one second");
        return false;
      }
      try {
        return writeLocked(
            new JSONObject()
                .put("user_audio_chunk", Base64.getEncoder().encodeToString(pcm16))
                .toString());
      } catch (org.json.JSONException error) {
        failLocked("Cannot encode ElevenLabs agent audio");
        return false;
      }
    }
  }

  public boolean send(float[] samples) {
    synchronized (lock) {
      if (closed || !ready) return false;
      if (samples == null || samples.length == 0 || samples.length > inputSampleRate) {
        failLocked("Agent audio chunks must contain at most one second of mono samples");
        return false;
      }
      byte[] pcm = new byte[samples.length * 2];
      for (int i = 0; i < samples.length; i++) {
        float value = Float.isFinite(samples[i]) ? Math.max(-1f, Math.min(1f, samples[i])) : 0f;
        int sample = Math.round(value * (value < 0 ? 32768 : 32767));
        pcm[i * 2] = (byte) sample;
        pcm[i * 2 + 1] = (byte) (sample >> 8);
      }
      return sendPcm16(pcm);
    }
  }

  public boolean sendContext(String instruction) {
    synchronized (lock) {
      if (closed || !ready) return false;
      String text = instruction == null ? "" : instruction.trim();
      if (text.isEmpty() || text.length() > 4000 || text.indexOf('\u0000') >= 0) return false;
      try {
        return writeLocked(
            new JSONObject()
                .put("type", "contextual_update")
                .put(
                    "text",
                    "Private instruction from phone owner; do not quote or repeat it aloud. "
                        + "Carry out the goal speaking the interlocutor language "
                        + config.sourceLanguage()
                        + ". Owner instruction: "
                        + text)
                .toString());
      } catch (org.json.JSONException error) {
        failLocked("Cannot encode the private agent instruction");
        return false;
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      shutdownLocked(true);
      notifyClosed();
    }
  }

  private void receive(String json) {
    synchronized (lock) {
      if (closed) return;
      try {
        JSONObject event = new JSONObject(json);
        String type = event.getString("type");
        switch (type) {
          case "conversation_initiation_metadata" -> {
            if (ready)
              throw new IllegalStateException("Agent sent duplicate initialization metadata");
            JSONObject data = event.getJSONObject("conversation_initiation_metadata_event");
            String conversationId = data.getString("conversation_id");
            if (conversationId.isBlank() || conversationId.length() > 128)
              throw new IllegalStateException("Agent sent an invalid conversation ID");
            inputSampleRate =
                ElevenAgentsApi.pcmSampleRate(data.getString("user_input_audio_format"));
            outputSampleRate =
                ElevenAgentsApi.pcmSampleRate(data.getString("agent_output_audio_format"));
            if (!writeLocked(
                new JSONObject()
                    .put("type", "contextual_update")
                    .put("text", taskContext())
                    .toString())) return;
            ready = true;
            connectionTimeout.cancel(false);
            listener.status("ElevenLabs agent: voice session ready");
            listener.ready(conversationId, inputSampleRate, outputSampleRate);
          }
          case "ping" -> {
            long id = event.getJSONObject("ping_event").getLong("event_id");
            writeLocked(new JSONObject().put("type", "pong").put("event_id", id).toString());
          }
          case "audio" -> {
            requireReady();
            JSONObject data = event.getJSONObject("audio_event");
            long id = data.getLong("event_id");
            if (id <= interruptedThrough) return;
            String encoded = data.getString("audio_base_64");
            if (encoded.length() > 262144)
              throw new IllegalStateException("Agent audio chunk exceeded the size limit");
            byte[] pcm = Base64.getDecoder().decode(encoded);
            if (pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > outputSampleRate * 4)
              throw new IllegalStateException("Agent audio chunk has an invalid PCM16 size");
            lastAudioEventId = Math.max(lastAudioEventId, id);
            listener.audio(pcm, outputSampleRate, id);
          }
          case "interruption" -> {
            requireReady();
            JSONObject data = event.optJSONObject("interruption_event");
            long id = data == null ? -1 : data.optLong("event_id", -1);
            interruptedThrough = Math.max(interruptedThrough, id >= 0 ? id : lastAudioEventId);
            listener.interrupted(id);
          }
          case "user_transcript" -> {
            requireReady();
            String text =
                event.getJSONObject("user_transcription_event").getString("user_transcript").trim();
            if (!text.isEmpty()) listener.transcript(false, text);
          }
          case "agent_response" -> {
            requireReady();
            String text =
                event.getJSONObject("agent_response_event").getString("agent_response").trim();
            if (!text.isEmpty()) listener.transcript(true, text);
          }
          case "agent_response_correction" -> {
            requireReady();
            listener.status("ElevenLabs agent response was interrupted");
          }
          case "client_tool_call" -> {
            JSONObject data = event.getJSONObject("client_tool_call");
            if (data.optBoolean("expects_response", true))
              writeLocked(
                  new JSONObject()
                      .put("type", "client_tool_result")
                      .put("tool_call_id", data.getString("tool_call_id"))
                      .put("is_error", true)
                      .put("result", "This Call Translate session does not execute client tools")
                      .toString());
            listener.status("ElevenLabs agent requested an unsupported client tool");
          }
          case "queue_status" -> {
            String status = event.getJSONObject("queue_status_event").getString("status");
            if (status.equals("timed_out"))
              failLocked("ElevenLabs agent queue timed out; try again later");
            else if (status.equals("waiting"))
              listener.status("ElevenLabs agent: waiting for capacity");
            else if (status.equals("admitted")) listener.status("ElevenLabs agent: admitted");
          }
          case "client_error", "error", "auth_error", "quota_exceeded", "rate_limited" ->
              failLocked(eventError(type));
          default -> {
            if (type.endsWith("_error") || event.has("error")) failLocked(eventError(type));
          }
        }
      } catch (IllegalArgumentException error) {
        failLocked(
            "ElevenLabs agent returned unsupported audio; use mono PCM16 formats in agent"
                + " settings");
      } catch (Exception error) {
        failLocked("ElevenLabs agent returned an invalid response; check its configuration");
      }
    }
  }

  private String taskContext() {
    return "Call Translate live voice session. Interlocutor language: "
        + config.sourceLanguage()
        + ". Speak to the interlocutor in this language. Phone owner's UI and task language: "
        + config.targetLanguage()
        + ". The owner's language does not determine the spoken reply language. "
        + "Do not quote or repeat the owner's private task or private instructions aloud. "
        + "Private objective and owner preferences: "
        + config.taskBrief();
  }

  private String initiation() throws Exception {
    return new JSONObject()
        .put("type", "conversation_initiation_client_data")
        .put(
            "dynamic_variables",
            new JSONObject()
                .put("task_brief", config.taskBrief())
                .put("source_language", config.sourceLanguage())
                .put("target_language", config.targetLanguage())
                .put("interlocutor_language", config.sourceLanguage())
                .put("owner_language", config.targetLanguage()))
        .toString();
  }

  private void requireReady() {
    if (!ready) throw new IllegalStateException("Agent sent content before metadata");
  }

  private boolean writeLocked(String message) {
    if (closed) return false;
    if (socket == null || !opened || socket.queueSize() + message.length() > maxSocketBytes) {
      failLocked("ElevenLabs agent network is too slow; the bounded audio queue filled");
      return false;
    }
    if (!socket.send(message)) {
      failLocked("ElevenLabs agent stopped accepting audio; restart the demo");
      return false;
    }
    return true;
  }

  private void fail(String message) {
    synchronized (lock) {
      failLocked(message);
    }
  }

  private void failLocked(String message) {
    if (closed) return;
    shutdownLocked(false);
    try {
      listener.failed(message);
    } catch (RuntimeException ignored) {
    }
    notifyClosed();
  }

  private void shutdownLocked(boolean graceful) {
    if (closed) return;
    closed = true;
    ready = false;
    if (connectionTimeout != null) connectionTimeout.cancel(false);
    if (socket != null) {
      if (graceful && opened && socket.close(1000, "")) {
        WebSocket closing = socket;
        deadlines.schedule(closing::cancel, 1000, TimeUnit.MILLISECONDS);
        deadlines.shutdown();
      } else {
        socket.cancel();
        deadlines.shutdownNow();
      }
    } else {
      deadlines.shutdownNow();
    }
  }

  private void notifyClosed() {
    try {
      listener.closed();
    } catch (RuntimeException ignored) {
    }
  }

  private static String eventError(String type) {
    return switch (type) {
      case "auth_error" ->
          "ElevenLabs denied the conversation; refresh the agent and check API key permissions";
      case "quota_exceeded" -> "ElevenLabs agent credits are exhausted; check account limits";
      case "rate_limited" -> "ElevenLabs agent rate or concurrency limit reached; try again later";
      default ->
          "ElevenLabs agent rejected this conversation; check its audio, security, language and"
              + " dynamic-variable settings";
    };
  }

  private static String language(String value, String role) {
    String code = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (code.equals("auto") || !code.matches("[a-z]{2,3}(-[a-z0-9]{2,8})?"))
      throw new IllegalArgumentException("Select an explicit " + role + " language for the agent");
    return code;
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
        opened = true;
        try {
          writeLocked(initiation());
        } catch (Exception error) {
          failLocked("Cannot initialize the ElevenLabs agent task");
        }
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      if (text.length() > maxMessageCharacters) {
        fail("ElevenLabs agent response exceeded the size limit");
        return;
      }
      receive(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      fail("ElevenLabs agent returned an unexpected binary response");
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      String message =
          response == null
              ? "ElevenLabs agent connection failed; check network and restart the demo"
              : ElevenAgentsApi.httpError(response.code(), "");
      if (response != null && response.body() != null) response.close();
      fail(message);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
      synchronized (lock) {
        if (closed) return;
        if (code == 1000 && ready) {
          shutdownLocked(true);
          notifyClosed();
        } else {
          failLocked(
              "ElevenLabs agent disconnected ("
                  + code
                  + "); check agent security, language and account limits");
        }
      }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      synchronized (lock) {
        deadlines.shutdownNow();
        if (closed) return;
        if (code == 1000 && ready) {
          shutdownLocked(false);
          notifyClosed();
        } else {
          failLocked(
              "ElevenLabs agent disconnected ("
                  + code
                  + "); check agent settings and restart the demo");
        }
      }
    }
  }
}
