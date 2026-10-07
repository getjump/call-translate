package dev.getjump.calltranslate.ml;

public final class NativeEngine implements AutoCloseable {
  private long handle;
  private final Object cancellationLock = new Object();

  static {
    System.loadLibrary("calltranslate");
  }

  public NativeEngine(String path, boolean gpu) {
    handle = loadWhisper(path, gpu);
    if (handle == 0) throw new IllegalStateException("Не удалось загрузить модель распознавания");
  }

  public synchronized String[] transcribe(float[] samples, String language) {
    return transcribe(samples, language, 0);
  }

  public synchronized String[] transcribe(float[] samples, String language, int audioContext) {
    if (handle == 0) throw new IllegalStateException("Модель распознавания закрыта");
    return runWhisper(handle, samples, language, audioContext);
  }

  public void cancel() {
    synchronized (cancellationLock) {
      if (handle != 0) cancelWhisper(handle);
    }
  }

  @Override
  public synchronized void close() {
    synchronized (cancellationLock) {
      if (handle != 0) {
        freeWhisper(handle);
        handle = 0;
      }
    }
  }

  private static native long loadWhisper(String path, boolean gpu);

  private static native String[] runWhisper(
      long handle, float[] samples, String language, int audioContext);

  private static native void freeWhisper(long handle);

  private static native void cancelWhisper(long handle);

  public static native String systemInfo();

  public static native boolean gpuAvailable();

  public static native String[] asrLanguages();

  public static native long loadTokenizer(String path);

  public static native int[] encode(long tokenizer, String text);

  public static native String decode(long tokenizer, int[] tokens);

  public static native void freeTokenizer(long tokenizer);
}
