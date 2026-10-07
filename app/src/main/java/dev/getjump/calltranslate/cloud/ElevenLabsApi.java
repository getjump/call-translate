package dev.getjump.calltranslate.cloud;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ElevenLabsApi {
  public static final String REALTIME_MODEL = "scribe_v2_realtime";
  private static final Set<String> speechModels = Set.of("scribe_v1", "scribe_v2", REALTIME_MODEL);
  private static final HttpUrl base = HttpUrl.get("https://api.elevenlabs.io/");
  private static final OkHttpClient client =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .callTimeout(25, TimeUnit.SECONDS)
          .build();

  public record Model(String id, String name, boolean realtime) {
    @Override
    public String toString() {
      return name + " [" + id + "]" + (realtime ? "" : " - unavailable in realtime mode");
    }
  }

  public static List<Model> verifyAndList(String apiKey) throws Exception {
    return verifyAndList(apiKey, client, base);
  }

  static List<Model> verifyAndList(String apiKey, Call.Factory calls, HttpUrl endpoint)
      throws Exception {
    String key = validatedKey(apiKey);
    JSONObject user;
    try {
      user = new JSONObject(get(calls, endpoint.resolve("v1/user"), key));
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("ElevenLabs returned an invalid account response");
    }
    if (user.optString("user_id").isBlank())
      throw new IllegalStateException("ElevenLabs returned an invalid account response");
    JSONArray catalog;
    try {
      catalog = new JSONArray(get(calls, endpoint.resolve("v1/models"), key));
    } catch (org.json.JSONException error) {
      throw new IllegalStateException("ElevenLabs returned an invalid model catalog");
    }
    var result = new LinkedHashMap<String, Model>();
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject item = catalog.getJSONObject(i);
      String id = item.getString("model_id");
      if (!speechModels.contains(id) && !item.optBoolean("can_do_speech_to_text", false)) continue;
      String name = item.optString("name", id);
      result.put(id, new Model(id, name.isBlank() ? id : name, id.equals(REALTIME_MODEL)));
    }
    result.putIfAbsent(
        REALTIME_MODEL,
        new Model(
            REALTIME_MODEL, "Scribe v2 Realtime (documented API; absent from catalog)", true));
    return List.copyOf(new ArrayList<>(result.values()));
  }

  static String validatedKey(String value) {
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

  private static String get(Call.Factory calls, HttpUrl url, String key) throws Exception {
    Request request = new Request.Builder().url(url).header("xi-api-key", key).get().build();
    try (var response = calls.newCall(request).execute()) {
      if (response.body() == null)
        throw new IllegalStateException("ElevenLabs returned no response");
      byte[] data;
      try (var input = response.body().byteStream()) {
        data = input.readNBytes(1048577);
      }
      if (data.length > 1048576)
        throw new IllegalStateException("ElevenLabs response exceeded the size limit");
      String body = new String(data, StandardCharsets.UTF_8);
      if (!response.isSuccessful())
        throw new IllegalStateException(ElevenLabsErrors.http(response.code(), body));
      return body;
    }
  }

  private ElevenLabsApi() {}
}
