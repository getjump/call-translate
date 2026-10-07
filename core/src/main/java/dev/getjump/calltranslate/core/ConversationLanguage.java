package dev.getjump.calltranslate.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class ConversationLanguage {
  private final Map<String, ArrayDeque<String>> evidence = new HashMap<>();
  private final Map<String, String> locked = new HashMap<>();

  public synchronized String input(String speaker, String requested) {
    return requested.equals("auto") ? locked.getOrDefault(speaker, "auto") : requested;
  }

  public synchronized void observe(String speaker, String detected) {
    if (locked.containsKey(speaker) || detected.isBlank() || detected.equals("auto")) return;
    ArrayDeque<String> recent = evidence.computeIfAbsent(speaker, key -> new ArrayDeque<>());
    recent.addLast(detected);
    if (recent.size() > 3) recent.removeFirst();
    if (recent.stream().filter(detected::equals).count() >= 2) locked.put(speaker, detected);
  }

  public synchronized void clear() {
    evidence.clear();
    locked.clear();
  }
}
