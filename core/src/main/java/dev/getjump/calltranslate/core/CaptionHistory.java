package dev.getjump.calltranslate.core;

import java.util.LinkedHashMap;
import java.util.List;

public final class CaptionHistory {
  public record Entry(
      long id,
      String speaker,
      String original,
      String translated,
      String language,
      long recognitionMilliseconds,
      long totalMilliseconds,
      boolean draft) {
    public Entry(
        long id,
        String speaker,
        String original,
        String translated,
        String language,
        long recognitionMilliseconds,
        long totalMilliseconds) {
      this(
          id,
          speaker,
          original,
          translated,
          language,
          recognitionMilliseconds,
          totalMilliseconds,
          false);
    }
  }

  private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();

  public synchronized void update(Entry entry) {
    entries.put(entry.id(), entry);
  }

  public synchronized List<Entry> snapshot() {
    return List.copyOf(entries.values());
  }

  public synchronized void clear() {
    entries.clear();
  }

  public synchronized void finishPending(String reason) {
    entries.replaceAll(
        (id, entry) ->
            entry.translated() != null
                ? entry
                : new Entry(
                    entry.id(),
                    entry.speaker(),
                    entry.original(),
                    reason,
                    entry.language(),
                    entry.recognitionMilliseconds(),
                    entry.totalMilliseconds()));
  }
}
