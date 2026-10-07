package dev.getjump.calltranslate.ml.npu;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SharedMemory;
import android.system.Os;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

public final class NpuService extends Service {
  private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
  private final AtomicLong nextSessionId = new AtomicLong();
  private final Object assetsLock = new Object();
  private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
  private volatile boolean destroyed;
  private volatile WhisperVocabulary vocabulary;
  private volatile byte[] melFilters;

  private final INpuService.Stub binder =
      new INpuService.Stub() {
        @Override
        public long openSession(String modelDirectory, IBinder owner) {
          checkCaller();
          if (destroyed) throw new IllegalStateException("NPU service is stopping");
          if (owner == null) throw new IllegalArgumentException("NPU session owner is required");
          long id = nextSessionId.incrementAndGet();
          Session session = new Session(id, owner);
          sessions.put(id, session);
          try {
            owner.linkToDeath(session.death, 0);
            synchronized (session.state) {
              session.linked = true;
              if (session.closed) {
                owner.unlinkToDeath(session.death, 0);
                throw new IllegalStateException("NPU session owner died during initialization");
              }
            }
            synchronized (assetsLock) {
              if (vocabulary == null) vocabulary = new WhisperVocabulary(NpuService.this);
              if (melFilters == null) {
                try (var input = getAssets().open("npu/mel_filters_128.bin")) {
                  melFilters = input.readAllBytes();
                }
              }
            }
            File directory = validateModel(modelDirectory);
            long handle =
                HtpWhisper.nativeInit(
                    getApplicationInfo().nativeLibraryDir,
                    new File(directory, "encoder.onnx").getPath(),
                    new File(directory, "decoder.onnx").getPath(),
                    melFilters);
            synchronized (session.state) {
              if (session.closed || destroyed) {
                HtpWhisper.nativeFree(handle);
                throw new IllegalStateException("NPU session closed during initialization");
              }
              session.handle = handle;
            }
            return id;
          } catch (Exception | LinkageError error) {
            close(id);
            throw new IllegalStateException(
                "NPU session initialization failed: " + error.getMessage(), error);
          }
        }

        @Override
        public Bundle transcribe(
            long sessionId, long requestId, SharedMemory audio, int sampleCount, String language) {
          checkCaller();
          try (SharedMemory memory = audio) {
            Session session = sessions.get(sessionId);
            if (session == null) throw new IllegalStateException("NPU session is closed");
            if (requestId <= 0)
              throw new IllegalArgumentException("Invalid NPU request identifier");
            if (memory == null || sampleCount < 16000 || sampleCount > 480000)
              throw new IllegalArgumentException(
                  "NPU audio must contain 1 to 30 seconds at 16 kHz");
            synchronized (session.inference) {
              long handle;
              synchronized (session.state) {
                if (session.closed) throw new IllegalStateException("NPU session is closed");
                if (requestId <= session.lastRequestId)
                  throw new IllegalArgumentException("NPU request identifier was already used");
                session.lastRequestId = requestId;
                handle = session.handle;
              }
              int languageToken = vocabulary.languageToken(language);
              float[] samples = readAudio(memory, sampleCount);
              int[] tokens =
                  HtpWhisper.nativeTranscribe(
                      handle,
                      requestId,
                      samples,
                      languageToken,
                      vocabulary.languageTokens,
                      vocabulary.startToken,
                      vocabulary.transcribeToken,
                      vocabulary.noTimestampsToken);
              Bundle result = new Bundle();
              result.putString("original", vocabulary.decode(tokens));
              result.putString("language", vocabulary.languageCode(tokens[0]));
              return result;
            }
          } catch (CancellationException error) {
            return failure("canceled", error);
          } catch (IllegalArgumentException error) {
            return failure("invalid", error);
          } catch (Exception | LinkageError error) {
            return failure("failed", error);
          }
        }

        @Override
        public void cancel(long sessionId, long requestId) {
          checkCaller();
          Session session = sessions.get(sessionId);
          if (session == null || requestId <= 0) return;
          synchronized (session.state) {
            if (!session.closed && session.handle != 0)
              HtpWhisper.nativeCancel(session.handle, requestId);
          }
        }

        @Override
        public void closeSession(long sessionId) {
          checkCaller();
          close(sessionId);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    if (!Application.getProcessName().endsWith(":asr_npu"))
      throw new IllegalStateException("QNN runtime requires the dedicated asr_npu process");
    try {
      Os.setenv(
          "ADSP_LIBRARY_PATH",
          getApplicationInfo().nativeLibraryDir + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp",
          true);
    } catch (Exception error) {
      throw new IllegalStateException("Cannot configure QNN DSP library path", error);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    for (Long id : sessions.keySet()) cleanup.execute(() -> close(id));
    cleanup.shutdown();
    super.onDestroy();
  }

  private void close(long id) {
    Session session = sessions.remove(id);
    if (session == null) return;
    synchronized (session.state) {
      session.closed = true;
      if (session.handle != 0) {
        HtpWhisper.nativeCancel(session.handle, Long.MAX_VALUE);
        HtpWhisper.nativeFree(session.handle);
        session.handle = 0;
      }
    }
    synchronized (session.state) {
      if (session.linked) {
        session.owner.unlinkToDeath(session.death, 0);
        session.linked = false;
      }
    }
  }

  private File validateModel(String path) throws Exception {
    if (path == null) throw new IllegalArgumentException("NPU model directory is required");
    File root = new File(getFilesDir(), "models").getCanonicalFile();
    File directory = new File(path).getCanonicalFile();
    if (!directory.getPath().startsWith(root.getPath() + File.separator))
      throw new IllegalArgumentException("NPU model must be installed in private model storage");
    for (String name :
        new String[] {
          "encoder.onnx", "encoder.bin", "decoder.onnx", "decoder.bin", "metadata.json"
        }) {
      File file = new File(directory, name);
      if (!file.isFile() || file.length() == 0)
        throw new IllegalArgumentException("Missing NPU model file: " + name);
    }
    JSONObject metadata =
        new JSONObject(
            new String(
                Files.readAllBytes(new File(directory, "metadata.json").toPath()),
                StandardCharsets.UTF_8));
    if (!metadata.getString("model_id").equals("whisper_large_v3_turbo_quantized")
        || !metadata.getString("precision").equals("w8a16")
        || !metadata.getString("runtime").equals("precompiled_qnn_onnx")
        || metadata.getJSONObject("chipset_attributes").getInt("htp_version") != 81)
      throw new IllegalArgumentException("NPU backend requires Whisper turbo w8a16 HTP81");
    JSONObject features =
        metadata
            .getJSONObject("model_files")
            .getJSONObject("encoder.onnx")
            .getJSONObject("inputs")
            .getJSONObject("input_features");
    var shape = features.getJSONArray("shape");
    JSONObject quantization = features.getJSONObject("quantization_parameters");
    if (!features.getString("dtype").equals("uint16")
        || shape.length() != 3
        || shape.getInt(0) != 1
        || shape.getInt(1) != 128
        || shape.getInt(2) != 3000
        || quantization.getInt("zero_point") != 31605
        || Math.abs(quantization.getDouble("scale") - 0.00004746108970721252) > 1e-12)
      throw new IllegalArgumentException("Unsupported NPU mel input contract");
    JSONObject modelFiles = metadata.getJSONObject("model_files");
    JSONObject encoderOutputs = modelFiles.getJSONObject("encoder.onnx").getJSONObject("outputs");
    JSONObject decoder = modelFiles.getJSONObject("decoder.onnx");
    JSONObject decoderInputs = decoder.getJSONObject("inputs");
    JSONObject decoderOutputs = decoder.getJSONObject("outputs");
    if (encoderOutputs.length() != 8
        || decoderInputs.length() != 19
        || decoderOutputs.length() != 9)
      throw new IllegalArgumentException("Unsupported NPU encoder or decoder outputs");
    validateTensor(decoderInputs, "input_ids", "int32", 1, 1);
    validateTensor(decoderInputs, "position_ids", "int32", 1);
    JSONObject mask = validateTensor(decoderInputs, "attention_mask", "uint16", 1, 1, 1, 200);
    validateQuantization(mask, 65535, 0.0015259021893143654);
    JSONObject logits = validateTensor(decoderOutputs, "logits", "uint16", 1, 51866, 1, 1);
    validateQuantization(logits, 23109, 0.0007861469639465213);
    for (int layer = 0; layer < 4; layer++) {
      for (String kind : new String[] {"k", "v"}) {
        long[] crossShape =
            kind.equals("k") ? new long[] {20, 1, 64, 1500} : new long[] {20, 1, 1500, 64};
        long[] selfShape =
            kind.equals("k") ? new long[] {20, 1, 64, 199} : new long[] {20, 1, 199, 64};
        String crossName = kind + "_cache_cross_" + layer;
        JSONObject encoded = validateTensor(encoderOutputs, crossName, "uint8", crossShape);
        JSONObject crossInput = validateTensor(decoderInputs, crossName, "uint8", crossShape);
        matchCacheQuantization(encoded, crossInput);
        JSONObject selfInput =
            validateTensor(
                decoderInputs, kind + "_cache_self_" + layer + "_in", "uint8", selfShape);
        JSONObject selfOutput =
            validateTensor(
                decoderOutputs, kind + "_cache_self_" + layer + "_out", "uint8", selfShape);
        matchCacheQuantization(selfInput, selfOutput);
      }
    }
    return directory;
  }

  private static JSONObject validateTensor(
      JSONObject tensors, String name, String dtype, long... expectedShape) throws Exception {
    JSONObject tensor = tensors.getJSONObject(name);
    var shape = tensor.getJSONArray("shape");
    if (!tensor.getString("dtype").equals(dtype) || shape.length() != expectedShape.length)
      throw new IllegalArgumentException("Unsupported NPU tensor: " + name);
    for (int index = 0; index < expectedShape.length; index++) {
      if (shape.getLong(index) != expectedShape[index])
        throw new IllegalArgumentException("Unsupported NPU tensor shape: " + name);
    }
    return tensor;
  }

  private static void validateQuantization(JSONObject tensor, int zeroPoint, double scale)
      throws Exception {
    JSONObject parameters = tensor.getJSONObject("quantization_parameters");
    if (parameters.getInt("zero_point") != zeroPoint
        || Math.abs(parameters.getDouble("scale") - scale) > 1e-12)
      throw new IllegalArgumentException("Unsupported NPU quantization parameters");
  }

  private static void matchCacheQuantization(JSONObject source, JSONObject target)
      throws Exception {
    JSONObject sourceParameters = source.getJSONObject("quantization_parameters");
    JSONObject targetParameters = target.getJSONObject("quantization_parameters");
    double scale = sourceParameters.getDouble("scale");
    if (sourceParameters.getInt("zero_point") != 128
        || targetParameters.getInt("zero_point") != 128
        || !Double.isFinite(scale)
        || scale <= 0
        || Double.compare(scale, targetParameters.getDouble("scale")) != 0)
      throw new IllegalArgumentException("NPU cache quantization contracts do not match");
  }

  private static float[] readAudio(SharedMemory audio, int sampleCount) throws Exception {
    if (audio.getSize() != sampleCount * 4)
      throw new IllegalArgumentException("NPU audio shared memory size does not match samples");
    ByteBuffer buffer = audio.mapReadOnly().order(ByteOrder.LITTLE_ENDIAN);
    try {
      float[] samples = new float[sampleCount];
      for (int i = 0; i < samples.length; i++) {
        samples[i] = buffer.getFloat();
        if (!Float.isFinite(samples[i]))
          throw new IllegalArgumentException("NPU audio contains a non-finite sample");
      }
      return samples;
    } finally {
      SharedMemory.unmap(buffer);
    }
  }

  private static Bundle failure(String kind, Throwable error) {
    Bundle result = new Bundle();
    result.putString("error", kind);
    result.putString("message", error.getMessage() == null ? error.toString() : error.getMessage());
    return result;
  }

  private static void checkCaller() {
    if (Binder.getCallingUid() != Process.myUid())
      throw new SecurityException("NPU service is private to the application");
  }

  private final class Session {
    final Object state = new Object();
    final Object inference = new Object();
    final IBinder owner;
    final IBinder.DeathRecipient death;
    long handle;
    long lastRequestId;
    boolean closed;
    boolean linked;

    Session(long id, IBinder owner) {
      this.owner = owner;
      death = () -> close(id);
    }
  }
}
