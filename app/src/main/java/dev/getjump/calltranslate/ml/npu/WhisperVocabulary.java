package dev.getjump.calltranslate.ml.npu;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;

final class WhisperVocabulary {
  private final byte[][] pieces;
  private final Map<String, Integer> languages = new HashMap<>();
  private final Map<Integer, String> languageCodes = new HashMap<>();
  private final Map<String, Integer> specialTokens = new HashMap<>();
  final int[] languageTokens;
  final int startToken;
  final int transcribeToken;
  final int noTimestampsToken;

  WhisperVocabulary(Context context) throws Exception {
    JSONArray vocabulary;
    try (var input = context.getAssets().open("npu/whisper_vocab_v3.json")) {
      vocabulary = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    if (vocabulary.length() != 51866)
      throw new IllegalStateException("Unexpected Whisper v3 vocabulary size");
    pieces = new byte[vocabulary.length()][];
    for (int token = 0; token < pieces.length; token++) {
      pieces[token] = Base64.decode(vocabulary.getString(token), Base64.DEFAULT);
      if (token < 50257) continue;
      String special = new String(pieces[token], StandardCharsets.UTF_8);
      if (!special.isEmpty()) specialTokens.put(special, token);
      if (token >= 50259 && token <= 50358 && special.matches("<\\|[a-z]{2,3}\\|>")) {
        String code = special.substring(2, special.length() - 2);
        languages.put(code, token);
        languageCodes.put(token, code);
      }
    }
    if (languages.size() != 100 || !Integer.valueOf(50303).equals(languages.get("sr")))
      throw new IllegalStateException("Unexpected Whisper v3 language tokens");
    languageTokens = languages.values().stream().mapToInt(Integer::intValue).sorted().toArray();
    startToken = requireToken("<|startoftranscript|>", 50258);
    transcribeToken = requireToken("<|transcribe|>", 50360);
    noTimestampsToken = requireToken("<|notimestamps|>", 50364);
  }

  int languageToken(String language) {
    if (language == null) throw new IllegalArgumentException("Source language is required");
    String code = language.toLowerCase(Locale.ROOT);
    if (code.equals("auto")) return -1;
    Integer token = languages.get(code);
    if (token == null)
      throw new IllegalArgumentException("Unsupported Whisper language: " + language);
    return token;
  }

  String languageCode(int token) {
    String code = languageCodes.get(token);
    if (code == null) throw new IllegalStateException("Invalid detected language token: " + token);
    return code;
  }

  String decode(int[] tokens) throws Exception {
    if (tokens == null || tokens.length == 0)
      throw new IllegalStateException("QNN returned no language token");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 1; i < tokens.length; i++) {
      int token = tokens[i];
      if (token < 0 || token >= 50257)
        throw new IllegalStateException("QNN returned an invalid text token: " + token);
      bytes.write(pieces[token]);
    }
    return bytes.toString(StandardCharsets.UTF_8).trim();
  }

  private int requireToken(String name, int expected) {
    Integer token = specialTokens.get(name);
    if (token == null || token != expected)
      throw new IllegalStateException("Invalid Whisper special token: " + name);
    return token;
  }
}
