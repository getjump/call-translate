package dev.getjump.calltranslate.core;

import java.util.Locale;

public final class DraftTranslation {
  public record Request(String original, String language, long version, boolean committed) {}

  private String original = "";
  private String language = "";
  private boolean committed;
  private long version;
  private String translation;
  private String translatedSource;
  private boolean translationFinal;
  private String requestedSource = "";
  private String requestedLanguage = "";
  private long requestedVersion = -1;
  private long requestedAt = -900;

  public void update(String original, String language, boolean committed) {
    if (this.committed && !committed) return;
    String detected = language == null ? "" : language;
    if (!this.original.equals(original)
        || !this.language.equals(detected)
        || this.committed != committed) version++;
    if (!this.language.equals(detected)
        || translatedSource != null && !prefix(original, translatedSource)) {
      translation = null;
      translatedSource = null;
      translationFinal = false;
    }
    this.original = original;
    this.language = detected;
    this.committed = committed;
  }

  public Request request(long milliseconds) {
    if (language.isBlank() || language.equals("auto")) return null;
    if (committed) {
      if (requestedVersion == version) return null;
    } else if (milliseconds - requestedAt < 900) return null;
    String input = original.trim();
    if (!committed && !input.matches("(?s).*[.!?\u3002\uFF01\uFF1F]$")) {
      int space = input.lastIndexOf(' ');
      if (space >= 0) input = input.substring(0, space).trim();
    }
    if (input.isBlank() || !committed && input.length() < 8) return null;
    if (!committed && requestedSource.equals(input) && requestedLanguage.equals(language))
      return null;
    requestedAt = milliseconds;
    requestedSource = input;
    requestedLanguage = language;
    requestedVersion = version;
    return new Request(input, language, version, committed);
  }

  public boolean accepts(Request request) {
    if (!language.equals(request.language())) return false;
    if (request.committed())
      return committed && version == request.version() && original.equals(request.original());
    return !committed && !translationFinal && prefix(original, request.original());
  }

  public boolean complete(Request request, String value) {
    if (!accepts(request)) return false;
    if (translatedSource != null
        && !request.committed()
        && request.original().length() < translatedSource.length()) return false;
    translation = value;
    translatedSource = request.original();
    translationFinal = request.committed();
    return true;
  }

  public String original() {
    return original;
  }

  public String language() {
    return language;
  }

  public String translation() {
    return translation;
  }

  public boolean draft() {
    return !committed || translation != null && !translationFinal;
  }

  private static boolean prefix(String original, String prefix) {
    String current = original.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    String previous = prefix.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    return current.equals(previous) || current.startsWith(previous + " ");
  }
}
