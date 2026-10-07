package dev.getjump.calltranslate.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class SpeechSegmenter {
  private final AudioWindow chunks = new AudioWindow(1600);
  private final ArrayDeque<float[]> preRoll = new ArrayDeque<>();
  private final List<float[]> phrase = new ArrayList<>();
  private int silentFrames;

  public List<float[]> append(byte[] bytes, int length) {
    List<float[]> output = new ArrayList<>();
    for (float[] frame : chunks.append(bytes, length)) {
      double energy = 0;
      for (float sample : frame) energy += sample * sample;
      boolean speech = Math.sqrt(energy / frame.length) >= 0.003;
      if (phrase.isEmpty()) {
        if (!speech) {
          preRoll.addLast(frame);
          if (preRoll.size() > 3) preRoll.removeFirst();
          continue;
        }
        phrase.addAll(preRoll);
        preRoll.clear();
      }
      phrase.add(frame);
      silentFrames = speech ? 0 : silentFrames + 1;
      if (silentFrames >= 6 || phrase.size() >= 100) {
        float[] samples = new float[phrase.size() * 1600];
        for (int i = 0; i < phrase.size(); i++)
          System.arraycopy(phrase.get(i), 0, samples, i * 1600, 1600);
        output.add(samples);
        phrase.clear();
        silentFrames = 0;
      }
    }
    return output;
  }
}
