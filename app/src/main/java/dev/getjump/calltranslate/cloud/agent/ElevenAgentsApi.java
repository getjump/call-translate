package dev.getjump.calltranslate.cloud.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ElevenAgentsApi {
  private static final HttpUrl base = HttpUrl.get("https://api.elevenlabs.io/");
  private static final int responseLimit = 1048576;
  private static final OkHttpClient client =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .callTimeout(25, TimeUnit.SECONDS)
          .build();

  public record Agent(String id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  public record AgentDetails(
      String id,
      String name,
      int inputSampleRate,
      int outputSampleRate,
      String configuredLanguage,
      boolean textOnly,
      List<String> clientEvents) {}

  public record Voice(String id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  public static List<Agent> listOwned(String apiKey) throws Exception {
    return listOwned(apiKey, client, base);
  }

  public static AgentDetails getAgent(String apiKey, String agentId) throws Exception {
    return getAgent(apiKey, agentId, client, base);
  }

  public static AgentDetails getAgent(String apiKey, String agentId, Call.Factory calls)
      throws Exception {
    return getAgent(apiKey, agentId, calls, base);
  }

  public static String signedUrl(String apiKey, String agentId, Call.Factory calls)
      throws Exception {
    return signedUrl(apiKey, agentId, calls, base);
  }

  public static String signedUrl(String apiKey, String agentId) throws Exception {
    return signedUrl(apiKey, agentId, client, base);
  }

  public static List<Voice> listVoices(String apiKey) throws Exception {
    return listVoices(apiKey, client, base);
  }

  static List<Voice> listVoices(String apiKey, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    String key = validatedKey(apiKey);
    var voices = new LinkedHashMap<String, Voice>();
    var tokens = new HashSet<String>();
    String token = "";
    for (int page = 0; page < 20; page++) {
      HttpUrl.Builder url =
          endpoint
              .resolve("v2/voices")
              .newBuilder()
              .addQueryParameter("page_size", "100")
              .addQueryParameter("sort", "name")
              .addQueryParameter("include_total_count", "false");
      if (!token.isEmpty()) url.addQueryParameter("next_page_token", token);
      JSONObject response;
      try {
        response = get(calls, url.build(), key);
      } catch (IllegalStateException error) {
        if (error.getMessage().contains("denied agent access"))
          throw new IllegalStateException(
              "ElevenLabs denied voice access; enable Voices Read (voices_read) for this API key"
                  + " and check IP restrictions");
        throw error;
      }
      try {
        JSONArray values = response.getJSONArray("voices");
        for (int i = 0; i < values.length(); i++) {
          JSONObject voice = values.getJSONObject(i);
          String id = validatedAgentId(voice.getString("voice_id"));
          String name = voice.optString("name", id).trim();
          if (name.length() > 256) name = name.substring(0, 256);
          voices.put(id, new Voice(id, name.isEmpty() ? id : name));
        }
        if (!response.getBoolean("has_more")) return List.copyOf(voices.values());
        token = response.getString("next_page_token");
        if (token.isBlank() || token.length() > 4096 || !tokens.add(token))
          throw new IllegalStateException("ElevenLabs returned invalid voice pagination");
      } catch (org.json.JSONException error) {
        throw new IllegalStateException("ElevenLabs returned an invalid voice list");
      }
    }
    throw new IllegalStateException("ElevenLabs voice list exceeded the 2000-voice limit");
  }

  public static String createAgent(String apiKey, JSONObject reviewedConfiguration)
      throws Exception {
    return createAgent(apiKey, reviewedConfiguration, client, base);
  }

  static String createAgent(
      String apiKey, JSONObject configuration, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    String key = validatedKey(apiKey);
    String body;
    try {
      if (configuration == null)
        throw new IllegalArgumentException("Review an agent configuration");
      JSONObject auth = configuration.getJSONObject("platform_settings").getJSONObject("auth");
      JSONArray allowlist = auth.optJSONArray("allowlist");
      if (!auth.getBoolean("enable_auth") || (allowlist != null && allowlist.length() > 0))
        throw new IllegalArgumentException(
            "New Call Translate agents must require signed-URL authentication without an"
                + " allowlist");
      configuration.getJSONObject("conversation_config");
      body = configuration.toString();
      if (body.length() > 65536)
        throw new IllegalArgumentException("Agent configuration exceeded the size limit");
    } catch (org.json.JSONException error) {
      throw new IllegalArgumentException(
          "Review a private agent configuration with conversation and authentication settings");
    }
    Request request =
        new Request.Builder()
            .url(endpoint.resolve("v1/convai/agents/create"))
            .header("xi-api-key", key)
            .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
            .build();
    JSONObject response = execute(calls, request);
    try {
      return validatedAgentId(response.getString("agent_id"));
    } catch (Exception error) {
      throw new IllegalStateException(
          "ElevenLabs agent creation outcome is unknown; refresh the owned-agent list before trying"
              + " again");
    }
  }

  static List<Agent> listOwned(String apiKey, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    String key = validatedKey(apiKey);
    var agents = new LinkedHashMap<String, Agent>();
    var cursors = new HashSet<String>();
    String cursor = "";
    for (int page = 0; page < 20; page++) {
      HttpUrl.Builder url =
          endpoint
              .resolve("v1/convai/agents")
              .newBuilder()
              .addQueryParameter("created_by_user_id", "@me")
              .addQueryParameter("archived", "false")
              .addQueryParameter("page_size", "100")
              .addQueryParameter("sort_by", "name")
              .addQueryParameter("sort_direction", "asc");
      if (!cursor.isEmpty()) url.addQueryParameter("cursor", cursor);
      JSONObject response = get(calls, url.build(), key);
      try {
        JSONArray values = response.getJSONArray("agents");
        for (int i = 0; i < values.length(); i++) {
          JSONObject item = values.getJSONObject(i);
          String id = validatedAgentId(item.getString("agent_id"));
          String name = item.optString("name", id).trim();
          if (name.length() > 256) name = name.substring(0, 256);
          agents.put(id, new Agent(id, name.isEmpty() ? id : name));
        }
        if (!response.getBoolean("has_more")) return List.copyOf(agents.values());
        cursor = response.getString("next_cursor");
        if (cursor.isBlank() || cursor.length() > 4096 || !cursors.add(cursor))
          throw new IllegalStateException("ElevenLabs returned invalid agent pagination");
      } catch (org.json.JSONException error) {
        throw new IllegalStateException("ElevenLabs returned an invalid agent list");
      }
    }
    throw new IllegalStateException("ElevenLabs agent list exceeded the 2000-agent limit");
  }

  static AgentDetails getAgent(String apiKey, String agentId, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    String id = validatedAgentId(agentId);
    HttpUrl url =
        endpoint.newBuilder().addPathSegments("v1/convai/agents").addPathSegment(id).build();
    JSONObject response = get(calls, url, validatedKey(apiKey));
    try {
      if (!id.equals(response.getString("agent_id")))
        throw new IllegalStateException("ElevenLabs returned a different agent");
      JSONObject config = response.getJSONObject("conversation_config");
      JSONObject asr = config.getJSONObject("asr");
      JSONObject tts = config.getJSONObject("tts");
      JSONObject agent = config.getJSONObject("agent");
      JSONObject conversation = config.getJSONObject("conversation");
      var events = new ArrayList<String>();
      JSONArray values = conversation.optJSONArray("client_events");
      if (values != null) {
        for (int i = 0; i < values.length(); i++) events.add(values.getString(i));
      }
      return new AgentDetails(
          id,
          response.optString("name", id),
          pcmSampleRate(asr.getString("user_input_audio_format")),
          pcmSampleRate(tts.getString("agent_output_audio_format")),
          agent.optString("language", ""),
          conversation.optBoolean("text_only", false),
          List.copyOf(events));
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("ElevenLabs returned an invalid agent configuration");
    }
  }

  static String signedUrl(String apiKey, String agentId, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    HttpUrl url =
        endpoint
            .resolve("v1/convai/conversation/get-signed-url")
            .newBuilder()
            .addQueryParameter("agent_id", validatedAgentId(agentId))
            .addQueryParameter("include_conversation_id", "true")
            .build();
    JSONObject response = get(calls, url, validatedKey(apiKey));
    try {
      String signed = response.getString("signed_url");
      validatedSignedUrl(signed);
      return signed;
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("ElevenLabs returned an invalid conversation URL");
    }
  }

  static HttpUrl validatedSignedUrl(String signed) {
    if (signed == null || signed.length() > 16384 || !signed.startsWith("wss://"))
      throw new IllegalArgumentException("ElevenLabs returned an invalid conversation URL");
    HttpUrl url;
    try {
      url = HttpUrl.get("https://" + signed.substring(6));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("ElevenLabs returned an invalid conversation URL");
    }
    if (!url.host().equals("api.elevenlabs.io")
        || url.port() != 443
        || !url.encodedPath().equals("/v1/convai/conversation")
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || url.fragment() != null)
      throw new IllegalArgumentException("ElevenLabs returned an untrusted conversation URL");
    return url;
  }

  static int pcmSampleRate(String format) {
    return switch (format) {
      case "pcm_8000" -> 8000;
      case "pcm_16000" -> 16000;
      case "pcm_22050" -> 22050;
      case "pcm_24000" -> 24000;
      case "pcm_44100" -> 44100;
      case "pcm_48000" -> 48000;
      default ->
          throw new IllegalArgumentException(
              "This agent needs mono PCM16 input and output; change its audio formats in"
                  + " ElevenLabs");
    };
  }

  static String httpError(int status, String body) {
    String type = "";
    try {
      JSONObject detail = new JSONObject(body).optJSONObject("detail");
      if (detail != null) type = detail.optString("status", "");
    } catch (Exception ignored) {
    }
    if (type.equals("quota_exceeded") || type.equals("insufficient_credits") || status == 402)
      return "ElevenLabs agent credits are exhausted; check account and API key limits";
    if (type.equals("missing_permissions") || status == 403)
      return "ElevenLabs denied agent access; enable ElevenAgents Read (convai_read) and"
          + " conversation access for this API key, and check IP restrictions";
    if (status == 401) return "ElevenLabs rejected the API key; verify it in cloud settings";
    if (status == 404) return "ElevenLabs agent was not found; refresh the owned-agent list";
    if (status == 429) return "ElevenLabs agent rate or concurrency limit reached; try again later";
    if (status >= 500) return "ElevenLabs agent service is unavailable; try again later";
    if (status == 422 || status == 400)
      return "ElevenLabs rejected the agent configuration; check its audio, security and language"
          + " settings";
    return "ElevenLabs agent request failed (HTTP " + status + ")";
  }

  private static JSONObject get(Call.Factory calls, HttpUrl url, String key) throws Exception {
    Request request = new Request.Builder().url(url).header("xi-api-key", key).get().build();
    return execute(calls, request);
  }

  private static JSONObject execute(Call.Factory calls, Request request) throws Exception {
    try (var response = calls.newCall(request).execute()) {
      if (response.body() == null)
        throw responseFailure(request, "ElevenLabs returned no agent response");
      byte[] data;
      try (var input = response.body().byteStream()) {
        data = input.readNBytes(responseLimit + 1);
      }
      if (data.length > responseLimit)
        throw responseFailure(request, "ElevenLabs agent response exceeded the size limit");
      String body = new String(data, StandardCharsets.UTF_8);
      if (!response.isSuccessful()) {
        if (response.code() == 403 && request.method().equals("POST"))
          throw new IllegalStateException(
              "ElevenLabs denied agent creation; enable ElevenAgents Write (convai_write) for this"
                  + " API key and check IP restrictions");
        if (response.code() >= 500 && request.method().equals("POST"))
          throw responseFailure(
              request, "ElevenLabs agent service is unavailable; try again later");
        throw new IllegalStateException(httpError(response.code(), body));
      }
      try {
        return new JSONObject(body);
      } catch (org.json.JSONException error) {
        throw responseFailure(request, "ElevenLabs returned an invalid agent response");
      }
    } catch (java.io.IOException error) {
      if (request.method().equals("POST"))
        throw new IllegalStateException(
            "ElevenLabs agent creation outcome is unknown; refresh the owned-agent list before"
                + " trying again");
      throw new IllegalStateException("ElevenLabs agent request failed; check the network");
    }
  }

  private static IllegalStateException responseFailure(Request request, String message) {
    return new IllegalStateException(
        request.method().equals("POST")
            ? "ElevenLabs agent creation outcome is unknown; refresh the owned-agent list before"
                + " trying again"
            : message);
  }

  private static String validatedKey(String value) {
    String key = value == null ? "" : value.trim();
    if (key.isEmpty()) throw new IllegalArgumentException("Enter your ElevenLabs API key");
    if (key.length() > 4096) throw new IllegalArgumentException("ElevenLabs API key is too long");
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c <= 32 || c >= 127)
        throw new IllegalArgumentException("ElevenLabs API key contains invalid characters");
    }
    return key;
  }

  private static String validatedAgentId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("Select a valid ElevenLabs agent");
    return value;
  }

  private ElevenAgentsApi() {}
}
