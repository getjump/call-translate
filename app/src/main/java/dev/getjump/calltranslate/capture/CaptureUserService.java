package dev.getjump.calltranslate.capture;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import dev.getjump.calltranslate.ICaptureService;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

public final class CaptureUserService extends ICaptureService.Stub {
  private final Context context;
  private final Map<Integer, Stream> streams = new HashMap<>();
  private AudioTrack injection;
  private long injectionGeneration;
  private long injectionFrames;
  private AudioManager injectionManager;
  private AudioManager.OnModeChangedListener injectionModeListener;

  @Keep
  public CaptureUserService(Context context) {
    HiddenApiBypass.addHiddenApiExemptions("");
    this.context = new ShellContext(context);
  }

  @Override
  public synchronized ParcelFileDescriptor start(int source) throws RemoteException {
    if (source != MediaRecorder.AudioSource.VOICE_DOWNLINK
        && source != MediaRecorder.AudioSource.VOICE_UPLINK)
      throw new RemoteException("Unsupported call audio source");
    Stream existing = streams.remove(source);
    if (existing != null) existing.close();
    AudioRecord recorder = null;
    ParcelFileDescriptor[] pipe = null;
    try {
      AudioRecord.Builder builder = new AudioRecord.Builder();
      AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, context);
      builder.setAudioSource(source);
      builder.setAudioFormat(
          new AudioFormat.Builder()
              .setSampleRate(16000)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build());
      int minimum =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      builder.setBufferSizeInBytes(Math.max(6400, minimum * 4));
      try {
        recorder = builder.build();
      } catch (SecurityException denied) {
        throw new IOException("Shell call-audio permission denied", denied);
      }
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IOException("Call audio initialization failed");
      recorder.startRecording();
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("Call audio capture did not start");
      pipe = ParcelFileDescriptor.createReliablePipe();
      Stream stream = new Stream(recorder, pipe[1], source);
      streams.put(source, stream);
      stream.thread.start();
      return pipe[0];
    } catch (Exception error) {
      if (recorder != null) recorder.release();
      if (pipe != null)
        for (ParcelFileDescriptor end : pipe) {
          try {
            end.close();
          } catch (IOException closeError) {
            Log.e("CallCapture", "Pipe cleanup failed", closeError);
          }
        }
      throw new RemoteException(error.toString());
    }
  }

  @Override
  public synchronized void stop() {
    stopInput();
    stopInjection();
  }

  @Override
  public synchronized void stopInput() {
    for (Stream stream : streams.values()) stream.close();
    streams.clear();
  }

  @Override
  public synchronized boolean injectionDrained(long generation) throws RemoteException {
    requireInjectionMode();
    if (injection == null || generation != injectionGeneration)
      throw new RemoteException("Call injection changed");
    return Integer.toUnsignedLong(injection.getPlaybackHeadPosition()) >= injectionFrames;
  }

  @Override
  public synchronized void openInjection(int sampleRate) throws RemoteException {
    stopInjection();
    if (sampleRate < 8000 || sampleRate > 48000)
      throw new RemoteException("Unsupported injection sample rate");
    try {
      AudioManager manager = context.getSystemService(AudioManager.class);
      if (manager.getMode() != AudioManager.MODE_IN_CALL)
        throw new IllegalStateException("SIM call is not active");
      boolean supported =
          (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(manager);
      if (!supported)
        throw new IllegalStateException("This phone does not support PSTN audio injection");
      AudioFormat format =
          new AudioFormat.Builder()
              .setSampleRate(sampleRate)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build();
      injection =
          (AudioTrack)
              AudioManager.class
                  .getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class)
                  .invoke(manager, format);
      if (injection == null || injection.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IllegalStateException("Call injection initialization failed");
      injectionManager = manager;
      injectionModeListener =
          mode -> {
            if (mode != AudioManager.MODE_IN_CALL) {
              synchronized (CaptureUserService.this) {
                stopInjection();
              }
            }
          };
      manager.addOnModeChangedListener(Runnable::run, injectionModeListener);
      requireInjectionMode();
      injectionGeneration = 0;
      injectionFrames = 0;
      injection.setStartThresholdInFrames(1);
      injection.play();
    } catch (Exception error) {
      stopInjection();
      Throwable cause =
          error instanceof java.lang.reflect.InvocationTargetException ? error.getCause() : error;
      throw new RemoteException("Call injection unavailable: " + cause.getMessage());
    }
  }

  @Override
  public synchronized int writeInjection(byte[] pcm16, long generation) throws RemoteException {
    if (pcm16 == null || pcm16.length > 9600 || pcm16.length % 2 != 0)
      throw new RemoteException("Invalid injection PCM chunk");
    requireInjectionMode();
    if (generation != injectionGeneration)
      throw new RemoteException("Stale call injection generation");
    if (injection == null) throw new RemoteException("Call injection is stopped");
    try {
      int written = injection.write(pcm16, 0, pcm16.length, AudioTrack.WRITE_NON_BLOCKING);
      if (written > 0) injectionFrames += written / 2;
      return written;
    } catch (Exception error) {
      throw new RemoteException("Call injection write failed");
    }
  }

  @Override
  public synchronized void flushInjection(long generation) throws RemoteException {
    requireInjectionMode();
    injectionGeneration = generation;
    if (injection == null) throw new RemoteException("Call injection is stopped");
    try {
      injection.pause();
      injection.flush();
      injectionFrames = 0;
      injection.play();
    } catch (Exception error) {
      throw new RemoteException("Call injection flush failed");
    }
  }

  @Override
  public synchronized void stopInjection() {
    AudioTrack track = injection;
    injection = null;
    if (injectionManager != null && injectionModeListener != null) {
      try {
        injectionManager.removeOnModeChangedListener(injectionModeListener);
      } catch (RuntimeException error) {
        Log.e("CallCapture", "Removing call mode listener failed", error);
      }
    }
    injectionManager = null;
    injectionModeListener = null;
    if (track == null) return;
    try {
      track.pause();
      track.flush();
    } catch (IllegalStateException ignored) {
    } finally {
      track.release();
    }
  }

  private void requireInjectionMode() throws RemoteException {
    if (context.getSystemService(AudioManager.class).getMode() != AudioManager.MODE_IN_CALL) {
      stopInjection();
      throw new RemoteException("SIM call audio mode ended");
    }
  }

  @Override
  public void destroy() {
    stop();
    System.exit(0);
  }

  @Override
  public String diagnostics() throws RemoteException {
    try {
      AudioManager manager = context.getSystemService(AudioManager.class);
      boolean capability =
          (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(manager);
      return "uid=" + Process.myUid() + ", pstn=" + capability + ", mode=" + manager.getMode();
    } catch (Exception error) {
      throw new RemoteException(error.toString());
    }
  }

  private static final class Stream {
    private final AudioRecord recorder;
    private final ParcelFileDescriptor output;
    private final Thread thread;
    private volatile boolean active = true;

    Stream(AudioRecord recorder, ParcelFileDescriptor output, int source) {
      this.recorder = recorder;
      this.output = output;
      thread = new Thread(this::pump, "call-audio-" + source);
    }

    private void pump() {
      try (OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
        byte[] buffer = new byte[3200];
        while (active) {
          int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
          if (count < 0) throw new IOException("Audio read failed: " + count);
          if (count > 0 && active) sink.write(buffer, 0, count);
        }
      } catch (Exception error) {
        if (active) Log.e("CallCapture", "Call audio pipe ended", error);
      } finally {
        active = false;
        recorder.release();
      }
    }

    void close() {
      active = false;
      try {
        output.close();
      } catch (IOException error) {
        Log.e("CallCapture", "Closing audio pipe failed", error);
      }
      try {
        recorder.stop();
      } catch (IllegalStateException error) {
        Log.d("CallCapture", "Audio already stopped", error);
      }
      try {
        thread.join(1000);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive()) recorder.release();
    }
  }
}
