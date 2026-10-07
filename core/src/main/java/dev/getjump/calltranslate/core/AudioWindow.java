package dev.getjump.calltranslate.core;

import java.util.ArrayList;
import java.util.List;

public final class AudioWindow {
  private float[] samples;
  private int used;
  private int lowByte = -1;

  public AudioWindow(int samples) {
    if (samples < 1 || samples > 480000)
      throw new IllegalArgumentException("invalid audio window size");
    this.samples = new float[samples];
  }

  public List<float[]> append(byte[] bytes, int length) {
    if (length < 0 || length > bytes.length)
      throw new IllegalArgumentException("invalid PCM byte count");
    List<float[]> result = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      if (lowByte < 0) {
        lowByte = bytes[i] & 255;
      } else {
        samples[used++] = (short) ((bytes[i] << 8) | lowByte) / 32768f;
        lowByte = -1;
        if (used == samples.length) {
          result.add(samples);
          samples = new float[samples.length];
          used = 0;
        }
      }
    }
    return result;
  }
}
