package dev.getjump.calltranslate.ml;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class NllbEngine implements AutoCloseable {
  private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
  private OrtSession embedding;
  private OrtSession encoder;
  private OrtSession initializer;
  private OrtSession decoder;
  private long tokenizer;

  public NllbEngine(File directory) throws OrtException {
    this(directory, () -> false);
  }

  public NllbEngine(File directory, BooleanSupplier canceled) throws OrtException {
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(4);
      options.setInterOpNumThreads(1);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      checkCanceled(canceled);
      embedding =
          environment.createSession(
              new File(directory, "NLLB_embed_and_lm_head.onnx").getPath(), options);
      checkCanceled(canceled);
      encoder =
          environment.createSession(new File(directory, "NLLB_encoder.onnx").getPath(), options);
      checkCanceled(canceled);
      initializer =
          environment.createSession(
              new File(directory, "NLLB_cache_initializer.onnx").getPath(), options);
      checkCanceled(canceled);
      decoder =
          environment.createSession(new File(directory, "NLLB_decoder.onnx").getPath(), options);
      checkCanceled(canceled);
      tokenizer =
          NativeEngine.loadTokenizer(new File(directory, "sentencepiece_bpe.model").getPath());
    } catch (Exception error) {
      close();
      throw error;
    }
  }

  public synchronized String translate(String text, int source, int target) throws OrtException {
    return translate(text, source, target, () -> false);
  }

  public synchronized String translate(
      String text, int source, int target, BooleanSupplier canceled) throws OrtException {
    if (tokenizer == 0) throw new IllegalStateException("Translation engine is closed");
    if (source == target) return text;
    checkCanceled(canceled);
    int[] pieces = NativeEngine.encode(tokenizer, text);
    if (pieces.length > 256)
      throw new IllegalArgumentException("Translation segment exceeds 256 tokens");
    long[] input = new long[pieces.length + 2];
    input[0] = source;
    for (int i = 0; i < pieces.length; i++)
      input[i + 1] = pieces[i] == 0 ? 3 : pieces[i] == 1 ? 0 : pieces[i] == 2 ? 2 : pieces[i] + 1;
    input[input.length - 1] = 2;
    long[] attention = new long[input.length];
    Arrays.fill(attention, 1);
    try (OnnxTensor ids = ids(input);
        OnnxTensor mask = ids(attention);
        OnnxTensor zeros =
            OnnxTensor.createTensor(
                environment, FloatBuffer.allocate(1024), new long[] {1, 1, 1024});
        OnnxTensor dummyIds = ids(new long[] {0, 0});
        OnnxTensor embedMode = mode(false);
        OnnxTensor headMode = mode(true);
        OnnxTensor emptyCache =
            OnnxTensor.createTensor(
                environment, FloatBuffer.allocate(0), new long[] {1, 16, 0, 64});
        OrtSession.Result embedResult =
            embedding.run(
                Map.of("input_ids", ids, "pre_logits", zeros, "use_lm_head", embedMode),
                Set.of("embed_matrix"));
        OrtSession.Result encoded =
            encoder.run(
                Map.of(
                    "input_ids",
                    ids,
                    "attention_mask",
                    mask,
                    "embed_matrix",
                    tensor(embedResult, "embed_matrix")));
        OrtSession.Result cross =
            initializer.run(
                Map.of("encoder_hidden_states", tensor(encoded, "last_hidden_state")))) {
      OrtSession.Result previous = null;
      ArrayList<Integer> output = new ArrayList<>();
      int token = 2;
      try {
        for (int step = 0; step < 129; step++) {
          checkCanceled(canceled);
          try (OnnxTensor current = ids(new long[] {token});
              OrtSession.Result currentEmbed =
                  embedding.run(
                      Map.of("input_ids", current, "pre_logits", zeros, "use_lm_head", embedMode),
                      Set.of("embed_matrix"))) {
            Map<String, OnnxTensor> decoderInput = new HashMap<>();
            decoderInput.put("input_ids", current);
            decoderInput.put("embed_matrix", tensor(currentEmbed, "embed_matrix"));
            decoderInput.put("encoder_attention_mask", mask);
            for (int layer = 0; layer < 12; layer++) {
              for (String kind : new String[] {"key", "value"}) {
                decoderInput.put(
                    "past_key_values." + layer + ".encoder." + kind,
                    tensor(cross, "present." + layer + ".encoder." + kind));
                decoderInput.put(
                    "past_key_values." + layer + ".decoder." + kind,
                    previous == null
                        ? emptyCache
                        : tensor(previous, "present." + layer + ".decoder." + kind));
              }
            }
            OrtSession.Result next = decoder.run(decoderInput);
            if (previous != null) previous.close();
            previous = next;
          }
          if (step == 0) {
            token = target;
            continue;
          }
          try (OrtSession.Result head =
              embedding.run(
                  Map.of(
                      "input_ids",
                      dummyIds,
                      "pre_logits",
                      tensor(previous, "pre_logits"),
                      "use_lm_head",
                      headMode),
                  Set.of("logits"))) {
            FloatBuffer logits = tensor(head, "logits").getFloatBuffer();
            long[] shape = tensor(head, "logits").getInfo().getShape();
            int vocabulary = (int) shape[shape.length - 1];
            int offset = logits.limit() - vocabulary;
            token = 0;
            float maximum = Float.NEGATIVE_INFINITY;
            for (int index = 0; index < vocabulary; index++) {
              float value = logits.get(offset + index);
              if (value > maximum) {
                maximum = value;
                token = index;
              }
            }
          }
          if (token == 2) break;
          if (token > 3 && token < 256000) output.add(token - 1);
          if (step == 128) throw new IllegalStateException("Translation exceeded the output limit");
        }
        checkCanceled(canceled);
        int[] decoded = new int[output.size()];
        for (int i = 0; i < decoded.length; i++) decoded[i] = output.get(i);
        return NativeEngine.decode(tokenizer, decoded);
      } finally {
        if (previous != null) previous.close();
      }
    }
  }

  private void checkCanceled(BooleanSupplier canceled) {
    if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Translation canceled");
  }

  private OnnxTensor ids(long[] tokens) throws OrtException {
    return OnnxTensor.createTensor(
        environment, LongBuffer.wrap(tokens), new long[] {1, tokens.length});
  }

  private OnnxTensor mode(boolean enabled) throws OrtException {
    ByteBuffer value = ByteBuffer.allocateDirect(1);
    value.put(0, (byte) (enabled ? 1 : 0));
    return OnnxTensor.createTensor(environment, value, new long[] {1}, OnnxJavaType.BOOL);
  }

  private static OnnxTensor tensor(OrtSession.Result result, String name) {
    return (OnnxTensor)
        result
            .get(name)
            .orElseThrow(() -> new IllegalStateException("Missing ONNX output: " + name));
  }

  @Override
  public synchronized void close() {
    if (tokenizer != 0) {
      NativeEngine.freeTokenizer(tokenizer);
      tokenizer = 0;
    }
    for (OrtSession session : new OrtSession[] {decoder, initializer, encoder, embedding}) {
      if (session != null) {
        try {
          session.close();
        } catch (OrtException error) {
          android.util.Log.e("CallTranslate", "Closing ONNX session failed", error);
        }
      }
    }
    decoder = null;
    initializer = null;
    encoder = null;
    embedding = null;
  }
}
