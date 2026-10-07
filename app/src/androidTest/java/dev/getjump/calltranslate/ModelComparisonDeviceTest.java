package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ModelComparisonDeviceTest {
  @Test
  public void compareWhisperOnIdenticalAudio() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var arguments = InstrumentationRegistry.getArguments();
    String id = arguments.getString("candidate", "whisper-small-q5");
    boolean gpu = arguments.getString("backend", "CPU").equals("Vulkan");
    var repository = new ModelRepository(context);
    var model = repository.model(id);
    assertTrue("Missing model: " + id, repository.installed(model));
    String label = "compare-" + id + "-" + (gpu ? "vulkan" : "cpu");
    boolean fullOnly = arguments.getString("scope", "all").equals("full");
    float[] audio = FixtureAudio.read(context);
    long load = System.nanoTime();
    try (var engine = new NativeEngine(repository.asrFile(model).getPath(), gpu)) {
      Benchmark.save(context, label + "-load", load, NativeEngine.systemInfo());
      for (int iteration = 0; iteration < 3; iteration++) {
        transcribe(context, engine, audio, 0, label + "-full-10s-" + iteration);
        if (!fullOnly)
          transcribe(context, engine, audio, 640, label + "-context640-10s-" + iteration);
      }
      if (fullOnly) return;
      StringBuilder chunks = new StringBuilder();
      for (int offset = 0; offset < audio.length; offset += 64000) {
        float[] part = Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + 64000));
        chunks.append(transcribe(context, engine, part, 384, label + "-isolated4s-" + offset));
        chunks.append(' ');
        transcribe(context, engine, part, 0, label + "-isolated4s-full-" + offset);
      }
      Benchmark.save(context, label + "-isolated4s-combined", System.nanoTime(), chunks.toString());
      transcribe(context, engine, Arrays.copyOf(audio, 128000), 576, label + "-context576-8s");
      transcribe(context, engine, Arrays.copyOf(audio, 128000), 0, label + "-full-8s");
      transcribe(context, engine, Arrays.copyOf(audio, 128000), 1024, label + "-context1024-8s");
    }
  }

  private String transcribe(
      Context context, NativeEngine engine, float[] audio, int encoderContext, String label)
      throws Exception {
    long start = System.nanoTime();
    String[] result = engine.transcribe(audio, "sr", encoderContext);
    Benchmark.save(context, label, start, result[0]);
    assertFalse("Empty transcript: " + label, result[0].isBlank());
    assertEquals("Wrong language: " + label, "sr", result[1]);
    return result[0];
  }

  @Test
  public void compareMetaCtcOnIdenticalAudio() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var directory = new File(context.getFilesDir(), "models/meta-ctc-300m-v2-int8");
    assertTrue("Missing Meta CTC model", new File(directory, "model.int8.onnx").isFile());
    Map<Integer, String> tokens = new HashMap<>();
    for (String line : Files.readAllLines(new File(directory, "tokens.txt").toPath())) {
      int separator = line.lastIndexOf(' ');
      assertTrue("Invalid token row: " + line, separator >= 0);
      tokens.put(Integer.parseInt(line.substring(separator + 1)), line.substring(0, separator));
    }
    var environment = OrtEnvironment.getEnvironment();
    Benchmark.save(
        context,
        "compare-onnx-providers",
        System.nanoTime(),
        OrtEnvironment.getAvailableProviders().toString());
    float[] audio = FixtureAudio.read(context);
    var arguments = InstrumentationRegistry.getArguments();
    String backend = arguments.getString("backend", "CPU");
    boolean profiling = arguments.getString("profile", "true").equals("true");
    boolean fullOnly = arguments.getString("scope", "all").equals("full");
    int requestedThreads = Integer.parseInt(arguments.getString("threads", "0"));
    int[] threadCounts =
        requestedThreads > 0
            ? new int[] {requestedThreads}
            : backend.equals("CPU") ? new int[] {4, 6} : new int[] {6};
    for (int threads : threadCounts) {
      long load = System.nanoTime();
      try (var options = new OrtSession.SessionOptions()) {
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (backend.equals("NNAPI")) {
          options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED));
        } else if (backend.equals("WebGPU")) {
          options.addWebGPU(Map.of());
        } else if (backend.equals("XNNPACK")) {
          options.addXnnpack(Map.of("intra_op_num_threads", "6"));
        }
        if (profiling && !backend.equals("CPU"))
          options.enableProfiling(new File(context.getFilesDir(), "meta-" + backend).getPath());
        try (var session =
            environment.createSession(new File(directory, "model.int8.onnx").getPath(), options)) {
          String label =
              "compare-meta-ctc-"
                  + backend.toLowerCase(java.util.Locale.ROOT)
                  + "-"
                  + threads
                  + (profiling ? "" : "-unprofiled");
          Benchmark.save(
              context,
              label + "-load",
              load,
              session.getInputInfo().toString() + " " + session.getOutputInfo());
          for (int iteration = 0; iteration < 3; iteration++) {
            meta(context, environment, session, tokens, audio, label + "-10s-" + iteration);
          }
          for (int offset = 0; !fullOnly && offset < audio.length; offset += 64000) {
            meta(
                context,
                environment,
                session,
                tokens,
                Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + 64000)),
                label + "-isolated4s-" + offset);
          }
          if (profiling && !backend.equals("CPU"))
            Benchmark.save(context, label + "-profile", System.nanoTime(), session.endProfiling());
        }
      }
    }
  }

  private void meta(
      Context context,
      OrtEnvironment environment,
      OrtSession session,
      Map<Integer, String> tokens,
      float[] samples,
      String label)
      throws Exception {
    long start = System.nanoTime();
    double mean = 0;
    for (float value : samples) mean += value;
    mean /= samples.length;
    double variance = 0;
    for (float value : samples) variance += (value - mean) * (value - mean);
    double scale = Math.sqrt(variance / samples.length + 1e-5);
    float[] normalized = new float[samples.length];
    for (int i = 0; i < samples.length; i++) normalized[i] = (float) ((samples[i] - mean) / scale);
    try (var input =
            OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(normalized), new long[] {1, samples.length});
        var output = session.run(Map.of("x", input))) {
      OnnxTensor tensor = (OnnxTensor) output.get("logits").orElseThrow();
      long[] shape = tensor.getInfo().getShape();
      assertEquals(3, shape.length);
      int frames = Math.toIntExact(shape[1]);
      int vocabulary = Math.toIntExact(shape[2]);
      FloatBuffer logits = tensor.getFloatBuffer();
      StringBuilder text = new StringBuilder();
      int previous = -1;
      for (int frame = 0; frame < frames; frame++) {
        int best = 0;
        float highest = Float.NEGATIVE_INFINITY;
        for (int token = 0; token < vocabulary; token++) {
          float value = logits.get(frame * vocabulary + token);
          if (value > highest) {
            highest = value;
            best = token;
          }
        }
        if (best != 0 && best != previous) text.append(tokens.get(best));
        previous = best;
      }
      Benchmark.save(context, label, start, text.toString());
      assertFalse("Empty Meta transcript: " + label, text.toString().isBlank());
    }
  }
}
