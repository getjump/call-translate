package dev.getjump.calltranslate.core;

public final class SessionGate {
  private long session;
  private long revision;
  private boolean active;

  public synchronized long start() {
    active = true;
    return ++session;
  }

  public synchronized long languageChanged() {
    return ++revision;
  }

  public synchronized void stop() {
    active = false;
  }

  public synchronized long revision() {
    return revision;
  }

  public synchronized boolean accepts(long session, long revision) {
    return active && this.session == session && this.revision == revision;
  }
}
