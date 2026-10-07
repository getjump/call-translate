package dev.getjump.calltranslate.cloud.agent;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Test;

public class ElevenAgentsProtocolTest {
  private static final String key = "synthetic-api-key";
  private static final String signed =
      "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent_fake&conversation_signature=synthetic-secret";
  private static final HttpUrl base = HttpUrl.get("https://fake.invalid/");

  @Test
  public void ownedListAuthenticatesPaginatesAndDoesNotExposeCredentials() throws Exception {
    var paths = new ArrayList<String>();
    var fake =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  var request = chain.request();
                  assertEquals(key, request.header("xi-api-key"));
                  assertEquals("@me", request.url().queryParameter("created_by_user_id"));
                  assertEquals("false", request.url().queryParameter("archived"));
                  assertFalse(request.url().toString().contains(key));
                  paths.add(request.url().queryParameter("cursor"));
                  return response(
                      request,
                      200,
                      paths.size() == 1
                          ? "{\"agents\":[{\"agent_id\":\"agent_a\",\"name\":\"A\"}],\"has_more\":true,\"next_cursor\":\"next\"}"
                          : "{\"agents\":[{\"agent_id\":\"agent_b\",\"name\":\"B\"}],\"has_more\":false}");
                })
            .build();
    var agents = ElevenAgentsApi.listOwned(key, fake, base);
    assertEquals(
        List.of(
            new ElevenAgentsApi.Agent("agent_a", "A"), new ElevenAgentsApi.Agent("agent_b", "B")),
        agents);
    assertEquals(2, paths.size());
    assertNull(paths.get(0));
    assertEquals("next", paths.get(1));
  }

  @Test
  public void repeatedPaginationAndPermissionFailuresAreExplicitAndSanitized() throws Exception {
    var fake = fake(200, "{\"agents\":[],\"has_more\":true,\"next_cursor\":\"same\"}");
    var error = assertThrows(Exception.class, () -> ElevenAgentsApi.listOwned(key, fake, base));
    assertTrue(error.getMessage(), error.getMessage().contains("pagination"));
    for (int code : List.of(401, 403, 429, 500)) {
      var denied =
          fake(
              code,
              "{\"detail\":{\"status\":\"missing_permissions\",\"message\":\""
                  + key
                  + signed
                  + "\"}}");
      error = assertThrows(Exception.class, () -> ElevenAgentsApi.listOwned(key, denied, base));
      assertFalse(error.getMessage().contains(key));
      assertFalse(error.getMessage().contains("synthetic-secret"));
    }
    error =
        assertThrows(Exception.class, () -> ElevenAgentsApi.listOwned(key, fake(403, ""), base));
    assertTrue(error.getMessage().contains("convai_read"));
  }

  @Test
  public void detailsReadActualFormatsAndSignedUrlCannotLeaveElevenLabs() throws Exception {
    var details =
        ElevenAgentsApi.getAgent(
            key,
            "agent_fake",
            fake(
                200,
                "{\"agent_id\":\"agent_fake\",\"name\":\"Fake\",\"conversation_config\":{\"asr\":{\"user_input_audio_format\":\"pcm_16000\"},\"tts\":{\"agent_output_audio_format\":\"pcm_24000\"},\"agent\":{\"language\":\"sr\"},\"conversation\":{\"text_only\":false,\"client_events\":[\"audio\",\"interruption\"]}}}"),
            base);
    assertEquals(16000, details.inputSampleRate());
    assertEquals(24000, details.outputSampleRate());
    assertEquals("sr", details.configuredLanguage());
    assertFalse(details.textOnly());
    assertEquals(List.of("audio", "interruption"), details.clientEvents());
    String signedResponse = new JSONObject().put("signed_url", signed).toString();
    var received =
        ElevenAgentsApi.signedUrl(
            key,
            "agent_fake",
            new OkHttpClient.Builder()
                .addInterceptor(
                    chain -> {
                      assertEquals("agent_fake", chain.request().url().queryParameter("agent_id"));
                      assertEquals(
                          "true", chain.request().url().queryParameter("include_conversation_id"));
                      return response(chain.request(), 200, signedResponse);
                    })
                .build(),
            base);
    assertEquals(signed, received);
    for (String invalid :
        List.of(
            "ws://api.elevenlabs.io/v1/convai/conversation",
            "wss://evil.invalid/v1/convai/conversation",
            "wss://api.elevenlabs.io:444/v1/convai/conversation",
            "wss://user:pass@api.elevenlabs.io/v1/convai/conversation")) {
      var error = assertThrows(Exception.class, () -> ElevenAgentsApi.validatedSignedUrl(invalid));
      assertFalse(error.getMessage().contains(invalid));
    }
    assertThrows(Exception.class, () -> ElevenAgentsApi.pcmSampleRate("ulaw_8000"));
  }

  @Test
  public void initiationCarriesExplicitTaskAndContextBeforeMicrophoneBecomesReady()
      throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      assertNull(wire.request.header("xi-api-key"));
      assertFalse(session.sendPcm16(new byte[3200]));
      wire.open();
      var initiation = new JSONObject(wire.sent.get(0));
      assertEquals("conversation_initiation_client_data", initiation.getString("type"));
      assertFalse(initiation.has("conversation_config_override"));
      var variables = initiation.getJSONObject("dynamic_variables");
      assertEquals("sr", variables.getString("source_language"));
      assertEquals("ru", variables.getString("target_language"));
      assertEquals("Book a table for two", variables.getString("task_brief"));
      wire.metadata("pcm_16000", "pcm_24000");
      assertEquals(List.of("conv_fake:16000:24000"), capture.ready);
      var context = new JSONObject(wire.sent.get(1));
      assertEquals("contextual_update", context.getString("type"));
      assertTrue(context.getString("text").contains("Interlocutor language: sr"));
      assertTrue(context.getString("text").contains("UI and task language: ru"));
      assertTrue(session.send(new float[] {-1f, 0f, 1f, Float.NaN}));
      var chunk = new JSONObject(wire.sent.get(2));
      assertArrayEquals(
          new byte[] {0, -128, 0, 0, -1, 127, 0, 0},
          Base64.getDecoder().decode(chunk.getString("user_audio_chunk")));
      wire.event("{\"type\":\"ping\",\"ping_event\":{\"event_id\":42,\"ping_ms\":50}}");
      var pong = new JSONObject(wire.sent.get(3));
      assertEquals("pong", pong.getString("type"));
      assertEquals(42, pong.getLong("event_id"));
    }
    assertEquals(1, capture.closes.get());
    assertTrue(wire.closeRequested);
  }

  @Test
  public void optionalPreferencesStartReadyWithAnInboundObjectiveAndExplicitLanguages()
      throws Exception {
    for (String task : new String[] {null, "", "  \n  "}) {
      Capture capture = new Capture();
      FakeSocket wire = new FakeSocket();
      var config = new ElevenAgentSession.SessionConfig(task, "SR", "RU");
      try (var session =
          new ElevenAgentSession(
              signed,
              config,
              capture,
              (request, listener) -> {
                wire.request = request;
                wire.listener = listener;
                return wire;
              },
              10000)) {
        wire.open();
        var variables = new JSONObject(wire.sent.get(0)).getJSONObject("dynamic_variables");
        String objective = variables.getString("task_brief");
        assertTrue(objective, objective.startsWith("First listen."));
        assertTrue(objective, objective.contains("who is calling and why"));
        assertTrue(objective, objective.contains("clarifying questions in sr"));
        assertTrue(objective, objective.contains("without the owner's instruction"));
        assertTrue(objective, objective.contains("Do not recite private context aloud"));
        assertEquals("sr", variables.getString("source_language"));
        assertEquals("ru", variables.getString("target_language"));
        wire.metadata("pcm_16000", "pcm_24000");
        assertEquals(List.of("conv_fake:16000:24000"), capture.ready);
        assertTrue(new JSONObject(wire.sent.get(1)).getString("text").contains(objective));
        assertTrue(capture.failures.toString(), capture.failures.isEmpty());
        assertTrue(session.sendPcm16(new byte[3200]));
      }
    }
  }

  @Test
  public void audioTranscriptsInterruptionAndUnsupportedToolsFollowProtocol() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      wire.open();
      wire.metadata("pcm_16000", "pcm_24000");
      wire.audio(5);
      wire.audio(5);
      assertEquals(2, capture.audio.size());
      assertArrayEquals(new byte[] {1, 0, 2, 0}, capture.audio.get(0));
      wire.event(
          "{\"type\":\"user_transcript\",\"user_transcription_event\":{\"user_transcript\":\"Dobar"
              + " dan\"}}");
      wire.event(
          "{\"type\":\"agent_response\",\"agent_response_event\":{\"agent_response\":\"Dobar"
              + " dan!\"}}");
      assertEquals(List.of("user:Dobar dan", "agent:Dobar dan!"), capture.transcripts);
      wire.event("{\"type\":\"interruption\",\"interruption_event\":{\"event_id\":5}}");
      wire.audio(5);
      wire.audio(6);
      assertEquals(List.of(5L), capture.interruptions);
      assertEquals(3, capture.audio.size());
      wire.event(
          "{\"type\":\"client_tool_call\",\"client_tool_call\":{\"tool_call_id\":\"tool_1\",\"expects_response\":true,\"tool_name\":\"fake\"}}");
      var result = new JSONObject(wire.sent.get(2));
      assertEquals("client_tool_result", result.getString("type"));
      assertEquals("tool_1", result.getString("tool_call_id"));
      assertTrue(result.getBoolean("is_error"));
      assertTrue(capture.failures.isEmpty());
    }
    wire.audio(7);
    assertEquals(3, capture.audio.size());
    assertEquals(1, capture.closes.get());
  }

  @Test
  public void backpressureTimeoutUnsupportedFormatAndFailureCloseOnlyOnce() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      wire.open();
      wire.metadata("pcm_16000", "pcm_24000");
      wire.queued = 98304;
      assertFalse(session.sendPcm16(new byte[3200]));
      assertEquals(1, capture.failures.size());
      assertTrue(capture.failures.get(0).contains("bounded"));
      assertTrue(wire.cancelled);
      wire.listener.onFailure(wire, new IllegalArgumentException(signed), null);
      assertEquals(1, capture.failures.size());
      assertEquals(1, capture.closes.get());
      assertFalse(capture.failures.get(0).contains("synthetic-secret"));
    }
    capture = new Capture();
    wire = new FakeSocket();
    try (var session = session(wire, capture, 20)) {
      assertTrue(capture.failed.await(2, TimeUnit.SECONDS));
      assertTrue(capture.failures.get(0).contains("timed out"));
      assertTrue(wire.cancelled);
    }
    capture = new Capture();
    wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      wire.open();
      wire.metadata("ulaw_8000", "pcm_24000");
      assertTrue(capture.ready.isEmpty());
      assertEquals(1, capture.failures.size());
      assertTrue(capture.failures.get(0).contains("PCM16"));
    }
    assertThrows(Exception.class, () -> new ElevenAgentSession.SessionConfig("Task", "auto", "ru"));
    assertThrows(
        Exception.class, () -> new ElevenAgentSession.SessionConfig("x".repeat(8001), "sr", "ru"));
  }

  @Test
  public void serverNormalCloseIsCompletionAndEarlyCloseIsSanitizedFailure() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      wire.open();
      wire.metadata("pcm_16000", "pcm_24000");
      wire.listener.onClosing(wire, 1000, signed);
      wire.listener.onClosed(wire, 1000, signed);
      assertTrue(capture.failures.isEmpty());
      assertEquals(1, capture.closes.get());
      assertFalse(session.sendPcm16(new byte[3200]));
    }
    capture = new Capture();
    wire = new FakeSocket();
    try (var session = session(wire, capture, 10000)) {
      wire.open();
      wire.listener.onClosing(wire, 1000, signed);
      assertEquals(1, capture.failures.size());
      assertFalse(capture.failures.get(0).contains("synthetic-secret"));
    }
  }

  @Test
  public void privateOwnerInstructionsUseContextOnlyAndRequireLiveReadySession() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    var session = session(wire, capture, 10000);
    assertFalse(session.sendContext("Ask about the price"));
    wire.open();
    assertFalse(session.sendContext("Ask about the price"));
    wire.metadata("pcm_16000", "pcm_24000");
    assertFalse(session.sendContext(null));
    assertFalse(session.sendContext(" "));
    assertFalse(session.sendContext("x".repeat(4001)));
    assertFalse(session.sendContext("bad\u0000instruction"));
    assertEquals(2, wire.sent.size());
    assertTrue(session.sendContext("Ask about the price"));
    var context = new JSONObject(wire.sent.get(2));
    assertEquals("contextual_update", context.getString("type"));
    assertTrue(context.getString("text").contains("do not quote or repeat it aloud"));
    assertTrue(context.getString("text").contains("interlocutor language sr"));
    assertTrue(context.getString("text").contains("Ask about the price"));
    session.close();
    assertFalse(session.sendContext("Ask again"));
    assertEquals(3, wire.sent.size());
    assertEquals(1, capture.closes.get());
  }

  @Test
  public void creationRequiresReviewedPrivateConfigAndDoesNotRetryUnknownOutcome()
      throws Exception {
    var config =
        new JSONObject()
            .put("name", "Fake private agent")
            .put("conversation_config", new JSONObject())
            .put(
                "platform_settings",
                new JSONObject().put("auth", new JSONObject().put("enable_auth", true)));
    AtomicInteger calls = new AtomicInteger();
    var fake =
        new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  assertEquals("POST", chain.request().method());
                  assertEquals("/v1/convai/agents/create", chain.request().url().encodedPath());
                  assertEquals(key, chain.request().header("xi-api-key"));
                  var payload = new okio.Buffer();
                  chain.request().body().writeTo(payload);
                  assertTrue(payload.readUtf8().contains("Fake private agent"));
                  return response(chain.request(), 200, "{\"agent_id\":\"agent_created\"}");
                })
            .build();
    assertEquals("agent_created", ElevenAgentsApi.createAgent(key, config, fake, base));
    assertEquals(1, calls.get());
    config.getJSONObject("platform_settings").getJSONObject("auth").put("enable_auth", false);
    assertThrows(Exception.class, () -> ElevenAgentsApi.createAgent(key, config, fake, base));
    assertEquals(1, calls.get());
    config.getJSONObject("platform_settings").getJSONObject("auth").put("enable_auth", true);
    var uncertain =
        new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(
                chain -> {
                  throw new java.io.IOException(signed);
                })
            .build();
    var error =
        assertThrows(
            Exception.class, () -> ElevenAgentsApi.createAgent(key, config, uncertain, base));
    assertTrue(error.getMessage().contains("outcome is unknown"));
    assertFalse(error.getMessage().contains("synthetic-secret"));
    for (int status : List.of(200, 500)) {
      var malformed = fake(status, signed);
      error =
          assertThrows(
              Exception.class, () -> ElevenAgentsApi.createAgent(key, config, malformed, base));
      assertTrue(error.getMessage().contains("outcome is unknown"));
      assertFalse(error.getMessage().contains("synthetic-secret"));
    }
    error =
        assertThrows(
            Exception.class, () -> ElevenAgentsApi.createAgent(key, config, fake(403, ""), base));
    assertTrue(error.getMessage().contains("convai_write"));
  }

  @Test
  public void namedVoicePickerUsesActualPagedCatalogAndSpecificReadPermission() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var fake =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  int page = calls.incrementAndGet();
                  assertEquals("/v2/voices", chain.request().url().encodedPath());
                  assertEquals(key, chain.request().header("xi-api-key"));
                  assertEquals(
                      "false", chain.request().url().queryParameter("include_total_count"));
                  if (page == 1)
                    assertNull(chain.request().url().queryParameter("next_page_token"));
                  else
                    assertEquals("next", chain.request().url().queryParameter("next_page_token"));
                  return response(
                      chain.request(),
                      200,
                      page == 1
                          ? "{\"voices\":[{\"voice_id\":\"voice_a\",\"name\":\"Alice\"}],\"has_more\":true,\"next_page_token\":\"next\"}"
                          : "{\"voices\":[{\"voice_id\":\"voice_b\",\"name\":\"Bob\"}],\"has_more\":false}");
                })
            .build();
    assertEquals(
        List.of(
            new ElevenAgentsApi.Voice("voice_a", "Alice"),
            new ElevenAgentsApi.Voice("voice_b", "Bob")),
        ElevenAgentsApi.listVoices(key, fake, base));
    assertEquals(2, calls.get());
    var error =
        assertThrows(
            Exception.class, () -> ElevenAgentsApi.listVoices(key, fake(403, signed), base));
    assertTrue(error.getMessage().contains("voices_read"));
    assertFalse(error.getMessage().contains("synthetic-secret"));
  }

  @Test
  public void privateDraftBindsSpeechToInterlocutorAndTaskWithoutRemoteOverrides()
      throws Exception {
    var draft =
        PrivateAgentDraft.build("Reviewed", "sr", new ElevenAgentsApi.Voice("voice_a", "Alice"));
    var config = draft.getJSONObject("conversation_config");
    assertEquals("sr", config.getJSONObject("agent").getString("language"));
    assertEquals("", config.getJSONObject("agent").getString("first_message"));
    assertEquals("eleven_v3_conversational", config.getJSONObject("tts").getString("model_id"));
    assertEquals("voice_a", config.getJSONObject("tts").getString("voice_id"));
    assertEquals("pcm_16000", config.getJSONObject("asr").getString("user_input_audio_format"));
    String prompt = config.getJSONObject("agent").getJSONObject("prompt").getString("prompt");
    assertTrue(prompt.contains("Speak only {{source_language}}"));
    assertTrue(prompt.contains("{{target_language}}"));
    assertTrue(prompt.contains("{{task_brief}}"));
    assertTrue(prompt.contains("without quoting or repeating"));
    var auth = draft.getJSONObject("platform_settings").getJSONObject("auth");
    assertTrue(auth.getBoolean("enable_auth"));
    assertEquals(0, auth.getJSONArray("allowlist").length());
    assertFalse(draft.has("conversation_config_override"));
    assertThrows(
        Exception.class,
        () ->
            PrivateAgentDraft.build(
                "Reviewed", "auto", new ElevenAgentsApi.Voice("voice_a", "Alice")));
  }

  private static ElevenAgentSession session(FakeSocket wire, Capture capture, long timeout)
      throws Exception {
    return new ElevenAgentSession(
        signed,
        new ElevenAgentSession.SessionConfig("Book a table for two", "sr", "ru"),
        capture,
        (request, listener) -> {
          wire.request = request;
          wire.listener = listener;
          return wire;
        },
        timeout);
  }

  private static OkHttpClient fake(int code, String body) {
    return new OkHttpClient.Builder()
        .addInterceptor(chain -> response(chain.request(), code, body))
        .build();
  }

  private static Response response(Request request, int code, String body) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("synthetic")
        .body(ResponseBody.create(body, MediaType.get("application/json")))
        .build();
  }

  private static final class Capture implements ElevenAgentSession.Listener {
    final List<String> ready = new ArrayList<>();
    final List<String> transcripts = new ArrayList<>();
    final List<byte[]> audio = new ArrayList<>();
    final List<Long> interruptions = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    final AtomicInteger closes = new AtomicInteger();
    final CountDownLatch failed = new CountDownLatch(1);

    @Override
    public void status(String message) {}

    @Override
    public void ready(String id, int input, int output) {
      ready.add(id + ":" + input + ":" + output);
    }

    @Override
    public void transcript(boolean agent, String text) {
      transcripts.add((agent ? "agent:" : "user:") + text);
    }

    @Override
    public void audio(byte[] pcm, int rate, long id) {
      assertEquals(24000, rate);
      audio.add(pcm);
    }

    @Override
    public void interrupted(long id) {
      interruptions.add(id);
    }

    @Override
    public void failed(String message) {
      failures.add(message);
      failed.countDown();
    }

    @Override
    public void closed() {
      closes.incrementAndGet();
    }
  }

  private static final class FakeSocket implements WebSocket {
    Request request;
    WebSocketListener listener;
    final List<String> sent = new ArrayList<>();
    long queued;
    boolean cancelled;
    boolean closeRequested;

    void open() {
      listener.onOpen(this, response(request, 101, ""));
    }

    void event(String json) {
      listener.onMessage(this, json);
    }

    void metadata(String input, String output) throws Exception {
      event(
          new JSONObject()
              .put("type", "conversation_initiation_metadata")
              .put(
                  "conversation_initiation_metadata_event",
                  new JSONObject()
                      .put("conversation_id", "conv_fake")
                      .put("user_input_audio_format", input)
                      .put("agent_output_audio_format", output))
              .toString());
    }

    void audio(long id) throws Exception {
      event(
          new JSONObject()
              .put("type", "audio")
              .put(
                  "audio_event",
                  new JSONObject().put("event_id", id).put("audio_base_64", "AQACAA=="))
              .toString());
    }

    @Override
    public Request request() {
      return request;
    }

    @Override
    public long queueSize() {
      return queued;
    }

    @Override
    public boolean send(String text) {
      sent.add(text);
      return !cancelled;
    }

    @Override
    public boolean send(ByteString bytes) {
      return false;
    }

    @Override
    public boolean close(int code, String reason) {
      closeRequested = true;
      return true;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
