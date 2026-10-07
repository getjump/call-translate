package dev.getjump.calltranslate.cloud;

import org.json.JSONObject;

final class ElevenLabsErrors {
  static String event(String type) {
    return switch (type) {
      case "auth_error" -> "ElevenLabs rejected the API key; verify the key and its permissions";
      case "quota_exceeded" -> "ElevenLabs quota is exhausted; check account credits";
      case "rate_limited", "commit_throttled" -> "ElevenLabs rate limit reached; try again later";
      case "unaccepted_terms" -> "Accept the ElevenLabs terms in your account before transcribing";
      case "queue_overflow" -> "ElevenLabs audio queue overflowed; restart the session";
      case "resource_exhausted" -> "ElevenLabs capacity is exhausted; try again later";
      case "session_time_limit_exceeded" ->
          "ElevenLabs session time limit reached; restart the session";
      case "chunk_size_exceeded" -> "ElevenLabs rejected the audio chunk size";
      case "insufficient_audio_activity" -> "ElevenLabs stopped after insufficient audio activity";
      case "invalid_request", "input_error" -> "ElevenLabs rejected the transcription parameters";
      default -> "ElevenLabs transcription failed; restart the session";
    };
  }

  static String http(int status, String body) {
    String type = "";
    try {
      JSONObject detail = new JSONObject(body).optJSONObject("detail");
      if (detail != null) type = detail.optString("status");
    } catch (Exception ignored) {
    }
    if (type.equals("quota_exceeded") || type.equals("unaccepted_terms")) return event(type);
    if (status == 401) return event("auth_error");
    if (status == 403)
      return "ElevenLabs denied access; allow account read and speech-to-text permissions";
    if (status == 429) return event("rate_limited");
    if (status >= 300 && status < 400)
      return "ElevenLabs redirected the request; authentication redirects are disabled";
    if (status >= 500) return "ElevenLabs is unavailable (HTTP " + status + "); try again later";
    return "ElevenLabs rejected the request (HTTP " + status + ")";
  }

  private ElevenLabsErrors() {}
}
