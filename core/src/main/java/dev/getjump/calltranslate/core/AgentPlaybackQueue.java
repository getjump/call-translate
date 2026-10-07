package dev.getjump.calltranslate.core;

import java.util.ArrayDeque;

public final class AgentPlaybackQueue implements AutoCloseable {
  public record Frame(byte[] pcm, long generation, boolean finish) {}

  private final ArrayDeque<Frame> frames = new ArrayDeque<>();
  private final int maxBytes;
  private final int maxFrameBytes;
  private int queuedBytes;
  private volatile long generation;
  private boolean closed;
  private boolean finishing;

  public AgentPlaybackQueue(int sampleRate) {
    if (sampleRate < 8000 || sampleRate > 48000)
      throw new IllegalArgumentException("Unsupported playback sample rate");
    maxBytes = sampleRate * 120;
    maxFrameBytes = sampleRate * 4;
  }

  public synchronized boolean offer(byte[] pcm) {
    if (closed
        || finishing
        || pcm == null
        || pcm.length == 0
        || pcm.length % 2 != 0
        || pcm.length > maxFrameBytes
        || frames.size() >= 2048
        || queuedBytes + pcm.length > maxBytes) return false;
    frames.addLast(new Frame(pcm.clone(), generation, false));
    queuedBytes += pcm.length;
    notifyAll();
    return true;
  }

  public synchronized Frame take() throws InterruptedException {
    while (frames.isEmpty() && !closed) wait();
    if (closed) return null;
    Frame frame = frames.removeFirst();
    if (frame.pcm() != null) queuedBytes -= frame.pcm().length;
    return frame;
  }

  public synchronized void interrupt() {
    if (closed) return;
    generation++;
    frames.clear();
    queuedBytes = 0;
    frames.addLast(new Frame(null, generation, false));
    if (finishing) frames.addLast(new Frame(null, generation, true));
    notifyAll();
  }

  public synchronized void finish() {
    if (closed || finishing) return;
    finishing = true;
    frames.addLast(new Frame(null, generation, true));
    notifyAll();
  }

  public long generation() {
    return generation;
  }

  @Override
  public synchronized void close() {
    closed = true;
    generation++;
    frames.clear();
    queuedBytes = 0;
    notifyAll();
  }
}
