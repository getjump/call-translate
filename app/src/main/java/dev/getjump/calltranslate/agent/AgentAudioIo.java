package dev.getjump.calltranslate.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.ParcelFileDescriptor;
import dev.getjump.calltranslate.ICaptureService;
import dev.getjump.calltranslate.core.AgentPcmFanout;
import dev.getjump.calltranslate.core.AgentPlaybackQueue;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

public final class AgentAudioIo implements AutoCloseable {
  public interface Listener {
    boolean input(byte[] pcm16);

    void failed(String message);
  }

  private final AgentPlaybackQueue playback;
  private final int inputRate;
  private final int outputRate;
  private final Listener listener;
  private final ICaptureService capture;
  private final AudioManager manager;
  private int previousMode;
  private boolean changedMode;
  private AudioDeviceInfo previousDevice;
  private AudioDeviceInfo selectedDevice;
  private AudioRecord microphone;
  private AudioTrack speaker;
  private InputStream downlink;
  private AcousticEchoCanceler echo;
  private NoiseSuppressor noise;
  private volatile boolean closed;
  private volatile boolean inputClosed;
  private volatile Runnable drained;
  private long speakerFrames;
  private Thread reader;
  private Thread writer;

  public AgentAudioIo(
      Context context, int inputRate, int outputRate, ICaptureService capture, Listener listener)
      throws Exception {
    if (inputRate < 8000 || inputRate > 48000 || outputRate < 8000 || outputRate > 48000)
      throw new IllegalArgumentException("Unsupported voice sample rate");
    playback = new AgentPlaybackQueue(outputRate);
    this.inputRate = inputRate;
    this.outputRate = outputRate;
    this.capture = capture;
    this.listener = listener;
    manager = context.getSystemService(AudioManager.class);
    try {
      if (capture != null) {
        if (inputRate != 16000)
          throw new IllegalStateException("Для SIM выбери в ElevenLabs входной формат PCM 16000");
        capture.openInjection(outputRate);
        downlink =
            new ParcelFileDescriptor.AutoCloseInputStream(
                capture.start(MediaRecorder.AudioSource.VOICE_DOWNLINK));
      } else {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
          throw new SecurityException("Разреши приложению доступ к микрофону");
        previousMode = manager.getMode();
        if (previousMode != AudioManager.MODE_NORMAL)
          throw new IllegalStateException(
              "Для проверки микрофона сначала заверши текущий звонок. Для SIM выбери режим звонка");
        previousDevice = manager.getCommunicationDevice();
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        changedMode = true;
        for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
          if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            if (manager.setCommunicationDevice(device)) selectedDevice = device;
            break;
          }
        }
        AudioFormat input =
            new AudioFormat.Builder()
                .setSampleRate(inputRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        int minimum =
            AudioRecord.getMinBufferSize(
                inputRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum < 0) throw new IllegalStateException("Микрофон не поддерживает формат агента");
        microphone =
            new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(input)
                .setBufferSizeInBytes(Math.max(minimum * 4, inputRate / 2))
                .build();
        if (microphone.getState() != AudioRecord.STATE_INITIALIZED)
          throw new IllegalStateException("Микрофон не запустился");
        if (AcousticEchoCanceler.isAvailable()) {
          echo = AcousticEchoCanceler.create(microphone.getAudioSessionId());
          if (echo != null) echo.setEnabled(true);
        }
        if (NoiseSuppressor.isAvailable()) {
          noise = NoiseSuppressor.create(microphone.getAudioSessionId());
          if (noise != null) noise.setEnabled(true);
        }
      }
      int buffer =
          AudioTrack.getMinBufferSize(
              outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (buffer < 0) throw new IllegalStateException("Динамик не поддерживает формат агента");
      speaker =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(outputRate)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setBufferSizeInBytes(Math.max(buffer * 2, outputRate / 5))
              .build();
      if (speaker.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IllegalStateException("Вывод голоса не запустился");
      speaker.setStartThresholdInFrames(1);
      speaker.play();
    } catch (Exception error) {
      close();
      throw error;
    }
  }

  public synchronized void start() {
    if (closed || reader != null)
      throw new IllegalStateException("Voice audio is closed or already started");
    if (microphone != null) microphone.startRecording();
    reader = new Thread(this::read, "agent-audio-input");
    writer = new Thread(this::write, "agent-audio-output");
    writer.start();
    reader.start();
  }

  public boolean play(byte[] bytes, int sampleRate) {
    if (closed
        || bytes == null
        || bytes.length == 0
        || bytes.length % 2 != 0
        || sampleRate != outputRate
        || bytes.length > outputRate * 4) return false;
    return playback.offer(bytes);
  }

  public void interruptPlayback() {
    playback.interrupt();
  }

  public synchronized void finishPlayback(Runnable finished) throws Exception {
    if (closed || inputClosed) return;
    drained = finished;
    inputClosed = true;
    if (capture != null) capture.stopInput();
    else if (microphone != null) microphone.stop();
    if (downlink != null) downlink.close();
    playback.finish();
  }

  private void read() {
    try {
      byte[] buffer = new byte[Math.max(2, inputRate / 5 & ~1)];
      int carry = 0;
      while (!closed && !inputClosed) {
        int count =
            downlink != null
                ? downlink.read(buffer, carry, buffer.length - carry)
                : microphone.read(buffer, carry, buffer.length - carry, AudioRecord.READ_BLOCKING);
        if (count < 0) throw new IllegalStateException("Voice input stopped");
        if (count > 0) {
          int total = carry + count;
          int aligned = total & ~1;
          if (aligned > 0
              && !closed
              && !inputClosed
              && !listener.input(Arrays.copyOf(buffer, aligned)))
            throw new IllegalStateException("Агент перестал принимать звук");
          carry = total - aligned;
          if (carry != 0) buffer[0] = buffer[aligned];
        }
      }
    } catch (Exception error) {
      if (!closed && !inputClosed)
        listener.failed(error.getMessage() == null ? "Ошибка входного звука" : error.getMessage());
    }
  }

  private void write() {
    try {
      while (!closed) {
        AgentPlaybackQueue.Frame frame = playback.take();
        if (frame == null) return;
        if (frame.finish()) {
          long deadline = System.nanoTime() + 2000000000L;
          while (!closed) {
            boolean completed =
                (capture == null || capture.injectionDrained(frame.generation()))
                    && Integer.toUnsignedLong(speaker.getPlaybackHeadPosition()) >= speakerFrames;
            if (completed) {
              Runnable finished = drained;
              if (finished != null && !closed) finished.run();
              return;
            }
            if (System.nanoTime() > deadline)
              throw new IllegalStateException("Завершение вывода голоса зависло");
            LockSupport.parkNanos(5000000L);
          }
          return;
        }
        if (frame.pcm() == null) {
          if (capture != null) capture.flushInjection(frame.generation());
          speaker.pause();
          speaker.flush();
          speakerFrames = 0;
          speaker.play();
          continue;
        }
        AgentPcmFanout.Output local =
            (bytes, offset, length) -> {
              int count = speaker.write(bytes, offset, length, AudioTrack.WRITE_NON_BLOCKING);
              if (count > 0) speakerFrames += count / 2;
              return count;
            };
        AgentPcmFanout.Output[] outputs =
            capture == null
                ? new AgentPcmFanout.Output[] {local}
                : new AgentPcmFanout.Output[] {
                  (bytes, offset, length) ->
                      capture.writeInjection(
                          Arrays.copyOfRange(bytes, offset, offset + length), frame.generation()),
                  local
                };
        AgentPcmFanout.write(
            frame.pcm(),
            Math.max(2, outputRate / 5 & ~1),
            () -> !closed && frame.generation() == playback.generation(),
            outputs);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      if (!closed)
        listener.failed(error.getMessage() == null ? "Ошибка вывода голоса" : error.getMessage());
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    playback.close();
    if (writer != null) writer.interrupt();
    if (downlink != null) {
      try {
        downlink.close();
      } catch (Exception error) {
        android.util.Log.e("CallAgent", "Closing downlink failed", error);
      }
    }
    if (microphone != null) {
      try {
        microphone.stop();
      } catch (IllegalStateException ignored) {
      }
      microphone.release();
    }
    if (speaker != null) speaker.release();
    if (capture != null) {
      try {
        capture.stop();
      } catch (Exception error) {
        android.util.Log.e("CallAgent", "Stopping call audio failed", error);
      }
    } else {
      if (selectedDevice != null
          && manager.getCommunicationDevice() != null
          && selectedDevice.getId() == manager.getCommunicationDevice().getId()) {
        if (previousDevice != null) manager.setCommunicationDevice(previousDevice);
        else manager.clearCommunicationDevice();
      }
      if (changedMode && manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
        manager.setMode(previousMode);
    }
    if (echo != null) echo.release();
    if (noise != null) noise.release();
    if (reader != null) reader.interrupt();
  }
}
