package dev.getjump.calltranslate.cloud;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ElevenLabsDeviceTest {
  private static final String key = "synthetic-test-key-not-a-real-secret";

  @Test
  public void credentialRoundTripAndDeleteUseIsolatedNonBackupStorage() throws Exception {
    Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String suffix = UUID.randomUUID().toString();
    File directory = new File(target.getNoBackupFilesDir(), "elevenlabs-test-" + suffix);
    assertTrue(directory.mkdirs());
    Context isolated =
        new ContextWrapper(target) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getNoBackupFilesDir() {
            return directory;
          }

          @Override
          public String getPackageName() {
            return target.getPackageName() + ".test." + suffix;
          }
        };
    CredentialStore credentials = new CredentialStore(isolated);
    try {
      assertEquals("", credentials.load());
      credentials.save(key);
      assertEquals(key, new CredentialStore(isolated).load());
      File encrypted = new File(directory, "elevenlabs.credential");
      byte[] data = Files.readAllBytes(encrypted.toPath());
      assertFalse(new String(data, StandardCharsets.UTF_8).contains(key));
      data[data.length - 1] ^= 1;
      Files.write(encrypted.toPath(), data);
      assertThrows(Exception.class, credentials::load);
      credentials.save(key);
      credentials.clear();
      assertEquals("", credentials.load());
      assertFalse(encrypted.exists());
    } finally {
      credentials.clear();
      assertTrue(directory.delete());
    }
  }

  @Test
  public void authenticationPrecedesCatalogAndOnlyAsrIsExposed() throws Exception {
    var paths = new ArrayList<String>();
    OkHttpClient fake =
        new OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(
                chain -> {
                  Request request = chain.request();
                  paths.add(request.url().encodedPath());
                  assertEquals(key, request.header("xi-api-key"));
                  assertFalse(request.url().toString().contains(key));
                  return response(
                      request,
                      200,
                      request.url().encodedPath().equals("/v1/user")
                          ? "{\"user_id\":\"fake-user\"}"
                          : "[{\"model_id\":\"eleven_multilingual_v2\",\"name\":\"TTS\",\"can_do_text_to_speech\":true},{\"model_id\":\"scribe_v2\",\"name\":\"Scribe"
                                + " v2\"},{\"model_id\":\"scribe_v2_realtime\",\"name\":\"Scribe"
                                + " Realtime\"}]");
                })
            .build();
    List<ElevenLabsApi.Model> models =
        ElevenLabsApi.verifyAndList(key, fake, HttpUrl.get("https://fake.invalid/"));
    assertEquals(List.of("/v1/user", "/v1/models"), paths);
    assertEquals(2, models.size());
    assertEquals("scribe_v2", models.get(0).id());
    assertFalse(models.get(0).realtime());
    assertEquals("scribe_v2_realtime", models.get(1).id());
    assertTrue(models.get(1).realtime());
    assertEquals("Scribe Realtime", models.get(1).name());
  }

  @Test
  public void invalidKeyCannotUsePublicCatalogAndMissingAsrIsExplicit() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    OkHttpClient denied =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  calls.incrementAndGet();
                  return response(chain.request(), 401, "{\"detail\":\"" + key + "\"}");
                })
            .build();
    Exception error =
        assertThrows(
            Exception.class,
            () -> ElevenLabsApi.verifyAndList(key, denied, HttpUrl.get("https://fake.invalid/")));
    assertEquals(1, calls.get());
    assertTrue(error.getMessage().contains("API key"));
    assertFalse(error.getMessage().contains(key));
    OkHttpClient empty =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain ->
                    response(
                        chain.request(),
                        200,
                        chain.request().url().encodedPath().equals("/v1/user")
                            ? "{\"user_id\":\"fake-user\"}"
                            : "[]"))
            .build();
    List<ElevenLabsApi.Model> models =
        ElevenLabsApi.verifyAndList(key, empty, HttpUrl.get("https://fake.invalid/"));
    assertEquals(1, models.size());
    assertEquals(ElevenLabsApi.REALTIME_MODEL, models.get(0).id());
    assertTrue(models.get(0).name().contains("absent from catalog"));
  }

  @Test
  public void fixedLanguagePreservesSegmentIdAndCommitsOnlyOnce() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("sr", capture, wire, 5000)) {
      assertEquals("sr", wire.request.url().queryParameter("language_code"));
      assertEquals(key, wire.request.header("xi-api-key"));
      assertFalse(wire.request.url().toString().contains(key));
      wire.event("{\"message_type\":\"session_started\"}");
      wire.event("{\"message_type\":\"partial_transcript\",\"text\":\"Dobar\"}");
      wire.event("{\"message_type\":\"partial_transcript\",\"text\":\"Dobar dan\"}");
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Dobar dan.\"}");
      wire.event(timed("Dobar dan.", "srp", 0));
      wire.event(timed("Dobar dan.", "srp", 0));
      assertEquals(3, capture.transcripts.size());
      for (Transcript value : capture.transcripts) assertEquals(1, value.id());
      assertTrue(capture.transcripts.get(2).committed());
      assertEquals("sr", capture.transcripts.get(2).language());
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Dobar dan.\"}");
      assertEquals(2, capture.transcripts.get(3).id());
      assertTrue(capture.transcripts.get(3).committed());
      assertTrue(capture.failures.isEmpty());
    }
    int count = capture.transcripts.size();
    wire.event("{\"message_type\":\"partial_transcript\",\"text\":\"late\"}");
    wire.listener.onFailure(wire, new IllegalStateException(), null);
    assertEquals(count, capture.transcripts.size());
    assertTrue(capture.failures.isEmpty());
    assertTrue(wire.cancelled);
  }

  @Test
  public void manualWhisperLanguageAliasUsesProviderIsoCode() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("jw", capture, wire, 5000)) {
      assertEquals("jv", wire.request.url().queryParameter("language_code"));
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Halo\"}");
      assertEquals(1, capture.transcripts.size());
      assertEquals("jv", capture.transcripts.get(0).language());
      assertTrue(capture.transcripts.get(0).committed());
      assertTrue(capture.failures.isEmpty());
    }
  }

  @Test
  public void autoPairsDelayedLanguageWithItsOwnCommittedSegment() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("auto", capture, wire, 5000)) {
      assertNull(wire.request.url().queryParameter("language_code"));
      wire.event("{\"message_type\":\"partial_transcript\",\"text\":\"Dobar dan\"}");
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Dobar dan.\"}");
      wire.event("{\"message_type\":\"partial_transcript\",\"text\":\"Hello\"}");
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Hello.\"}");
      assertEquals(0, capture.transcripts.stream().filter(Transcript::committed).count());
      wire.event(timed("Hello.", "eng", 1));
      wire.event(timed("Dobar dan.", "srp", 0));
      wire.event(timed("Dobar dan.", "srp", 0));
      List<Transcript> finals = capture.transcripts.stream().filter(Transcript::committed).toList();
      assertEquals(2, finals.size());
      assertEquals(2, finals.get(0).id());
      assertEquals("en", finals.get(0).language());
      assertEquals(1, finals.get(1).id());
      assertEquals("sr", finals.get(1).language());
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Dobar dan.\"}");
      wire.event(timed("Dobar dan.", "srp", 2));
      assertEquals(3, capture.transcripts.stream().filter(Transcript::committed).count());
      assertTrue(capture.failures.isEmpty());
    }
  }

  @Test
  public void duplicateOldMetadataCannotFinalizeNewIdenticalTextWithWrongLanguage()
      throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("auto", capture, wire, 5000)) {
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      wire.event(timed("Da", "srp", 0));
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      wire.event(timed("Da", "srp", 0));
      List<Transcript> finals = capture.transcripts.stream().filter(Transcript::committed).toList();
      assertEquals(1, finals.size());
      assertEquals(1, finals.get(0).id());
      assertEquals("sr", finals.get(0).language());
      assertTrue(capture.failures.isEmpty());
      wire.event(timed("Da", "eng", 1));
      finals = capture.transcripts.stream().filter(Transcript::committed).toList();
      assertEquals(2, finals.size());
      assertEquals(2, finals.get(1).id());
      assertEquals("en", finals.get(1).language());
      assertTrue(capture.failures.isEmpty());
    }
  }

  @Test
  public void indistinguishableRepeatedMetadataTimesOutInsteadOfGuessingLanguage()
      throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("auto", capture, wire, 1000)) {
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      wire.event(timed("Da", "srp", 0));
      wire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      wire.event(timed("Da", "srp", 0));
      assertEquals(1, capture.transcripts.stream().filter(Transcript::committed).count());
      assertTrue(capture.failed.await(3, TimeUnit.SECONDS));
      assertTrue(capture.failures.get(0).contains("timed out"));
      assertEquals(1, capture.transcripts.stream().filter(Transcript::committed).count());
    }
  }

  @Test
  public void silenceIsStreamedInSmallPcmChunksAndQueuesAreBounded() throws Exception {
    Capture capture = new Capture();
    FakeSocket wire = new FakeSocket();
    try (var stream = stream("sr", capture, wire, 5000)) {
      assertTrue(stream.send(new float[3200]));
      assertTrue(wire.sent.isEmpty());
      wire.event("{\"message_type\":\"session_started\"}");
      assertEquals(2, wire.sent.size());
      JSONObject message = new JSONObject(wire.sent.get(0));
      assertEquals("input_audio_chunk", message.getString("message_type"));
      assertEquals(16000, message.getInt("sample_rate"));
      assertFalse(message.getBoolean("commit"));
      byte[] pcm = Base64.decode(message.getString("audio_base_64"), Base64.NO_WRAP);
      assertEquals(3200, pcm.length);
      for (byte value : pcm) assertEquals(0, value);
      wire.queued = 98304;
      assertFalse(stream.send(new float[1600]));
      assertEquals(1, capture.failures.size());
      assertTrue(capture.failures.get(0).contains("too slow"));
      assertTrue(wire.cancelled);
    }
    capture = new Capture();
    wire = new FakeSocket();
    try (var stream = stream("sr", capture, wire, 5000)) {
      assertTrue(stream.send(new float[32000]));
      assertFalse(stream.send(new float[1600]));
      assertEquals(1, capture.failures.size());
      assertTrue(capture.failures.get(0).contains("buffer filled"));
    }
  }

  @Test
  public void errorsTimeoutAndAmbiguousLanguageFailWithoutGuessing() throws Exception {
    for (String type :
        List.of(
            "auth_error", "quota_exceeded", "rate_limited", "unaccepted_terms", "queue_overflow")) {
      Capture capture = new Capture();
      FakeSocket wire = new FakeSocket();
      try (var stream = stream("auto", capture, wire, 5000)) {
        wire.event("{\"message_type\":\"" + type + "\",\"error\":\"" + key + "\"}");
        wire.event("{\"message_type\":\"" + type + "\",\"error\":\"repeat\"}");
        assertEquals(type, 1, capture.failures.size());
        assertFalse(capture.failures.get(0).contains(key));
        assertFalse(stream.send(new float[1600]));
        assertTrue(wire.cancelled);
      }
    }
    Capture timeout = new Capture();
    FakeSocket timeoutWire = new FakeSocket();
    try (var stream = stream("auto", timeout, timeoutWire, 20)) {
      timeoutWire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Hello\"}");
      assertTrue(timeout.failed.await(2, TimeUnit.SECONDS));
      assertTrue(timeout.failures.get(0).contains("timed out"));
      assertEquals(0, timeout.transcripts.stream().filter(Transcript::committed).count());
    }
    Capture ambiguous = new Capture();
    FakeSocket ambiguousWire = new FakeSocket();
    try (var stream = stream("auto", ambiguous, ambiguousWire, 5000)) {
      ambiguousWire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      ambiguousWire.event("{\"message_type\":\"committed_transcript\",\"text\":\"Da\"}");
      ambiguousWire.event(timed("Da", "srp", 0));
      assertEquals(1, ambiguous.failures.size());
      assertTrue(ambiguous.failures.get(0).contains("ambiguous"));
      assertEquals(0, ambiguous.transcripts.stream().filter(Transcript::committed).count());
    }
  }

  private static ElevenLabsStream stream(
      String source, Capture capture, FakeSocket wire, long languageTimeoutMs) throws Exception {
    return new ElevenLabsStream(
        key,
        ElevenLabsApi.REALTIME_MODEL,
        source,
        capture,
        (request, listener) -> {
          wire.request = request;
          wire.listener = listener;
          return wire;
        },
        languageTimeoutMs,
        10000);
  }

  private static String timed(String text, String language, int start) throws Exception {
    return new JSONObject()
        .put("message_type", "committed_transcript_with_timestamps")
        .put("text", text)
        .put("language_code", language)
        .put(
            "words",
            new org.json.JSONArray("[{\"start\":" + start + ",\"end\":" + (start + 1) + "}]"))
        .toString();
  }

  private static Response response(Request request, int code, String body) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake response")
        .body(ResponseBody.create(body, MediaType.get("application/json")))
        .build();
  }

  private record Transcript(long id, String text, String language, boolean committed) {}

  private static final class Capture implements ElevenLabsStream.Listener {
    final List<Transcript> transcripts = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    final CountDownLatch failed = new CountDownLatch(1);

    @Override
    public void status(String message) {}

    @Override
    public void transcript(long id, String text, String language, boolean committed) {
      transcripts.add(new Transcript(id, text, language, committed));
    }

    @Override
    public void failed(String message) {
      failures.add(message);
      failed.countDown();
    }
  }

  private static final class FakeSocket implements WebSocket {
    Request request;
    WebSocketListener listener;
    final List<String> sent = new ArrayList<>();
    long queued;
    boolean cancelled;

    void event(String json) {
      listener.onMessage(this, json);
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
      return !cancelled;
    }

    @Override
    public boolean close(int code, String reason) {
      cancelled = true;
      return true;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
