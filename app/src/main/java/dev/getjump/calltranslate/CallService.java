package dev.getjump.calltranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import dev.getjump.calltranslate.capture.ShizukuConnection;
import dev.getjump.calltranslate.core.AudioWindow;
import dev.getjump.calltranslate.core.CaptionHistory;
import dev.getjump.calltranslate.core.SpeechSegmenter;
import dev.getjump.calltranslate.ml.InferenceWorker;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class CallService extends Service {
  public static final String UPDATE = "dev.getjump.calltranslate.UPDATE";
  public static final String STOP = "stop";
  public static final String LANGUAGE = "language";
  public static final String PAUSE = "pause";
  public static final String SIM = "sim";
  public static final String MIC = "mic";
  public static volatile boolean running;
  public static volatile String latestStatus = "Выбери модели и запусти перевод";
  public static final CaptionHistory history = new CaptionHistory();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<InputStream> inputs = new ArrayList<>();
  private final List<Thread> readers = new ArrayList<>();
  private ShizukuConnection connection;
  private ICaptureService capture;
  private InferenceWorker worker;
  private CallTestRecorder recording;
  private AudioRecord microphone;
  private CaptionOverlay overlay;
  private LanguageCatalog languages;
  private TelephonyManager telephone;
  private PhoneObserver observer;
  private boolean inCall;
  private boolean paused;
  private boolean sim;
  private boolean newConversation = true;
  private long workerGeneration;
  private volatile boolean active;
  private volatile long captureGeneration;

  private final class PhoneObserver extends TelephonyCallback
      implements TelephonyCallback.CallStateListener {
    @Override
    public void onCallStateChanged(int state) {
      if (!sim || !running) return;
      boolean wasInCall = inCall;
      inCall = state == TelephonyManager.CALL_STATE_OFFHOOK;
      if (inCall && !wasInCall) {
        newConversation = true;
        if (!paused) startSession();
      } else if (!inCall && wasInCall) {
        stopSession();
        if (!paused && worker != null && worker.ready()) status("Модели готовы. Ожидаю SIM-звонок");
      }
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      languages = new LanguageCatalog(this);
      overlay = new CaptionOverlay(this);
    } catch (Exception error) {
      status(error.toString());
      stopSelf();
    }
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "captions", "Перевод звонков", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = intent.getAction();
    if (STOP.equals(action)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (LANGUAGE.equals(action)) {
      if (worker != null) worker.languagesChanged();
      history.finishPending("Язык изменен. Перевод прерван");
      updateHistory();
      return START_NOT_STICKY;
    }
    if (PAUSE.equals(action)) {
      paused = !paused;
      if (paused) {
        stopSession(false);
        status("Перевод на паузе");
      } else if (!sim || inCall) startSession();
      else if (worker != null && worker.ready()) status("Модели готовы. Ожидаю SIM-звонок");
      return START_NOT_STICKY;
    }
    if (running) return START_NOT_STICKY;
    stopService(new Intent(this, VoiceAgentService.class));
    sim = SIM.equals(action);
    if (sim && !ShizukuConnection.permitted()) {
      status("Для SIM запусти Shizuku и разреши доступ");
      stopSelf();
      return START_NOT_STICKY;
    }
    int type =
        sim
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
    try {
      startForeground(10, notification(), type);
    } catch (RuntimeException error) {
      status("Не удалось запустить фоновый перевод: " + error.getMessage());
      stopSelf();
      return START_NOT_STICKY;
    }
    running = true;
    history.clear();
    try {
      prepareWorker();
    } catch (Exception error) {
      status(error.getMessage() == null ? error.toString() : error.getMessage());
      stopSelf();
      return START_NOT_STICKY;
    }
    if (sim) {
      status("Подготовка моделей для SIM-звонков...");
      telephone = getSystemService(TelephonyManager.class);
      observer = new PhoneObserver();
      try {
        telephone.registerTelephonyCallback(getMainExecutor(), observer);
      } catch (SecurityException error) {
        status("Разреши доступ к состоянию телефона");
        stopSelf();
      }
    } else {
      startSession();
    }
    return START_NOT_STICKY;
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, CallService.class).setAction(PAUSE),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, CallService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "captions")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Перевод звонка")
        .setContentText(latestStatus)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(
            new Notification.Action.Builder(null, paused ? "Продолжить" : "Пауза", pause).build())
        .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
        .build();
  }

  private void status(String text) {
    recordStatus(text);
    latestStatus = recording == null ? text : text + " | Тест: запись";
    if (overlay != null) overlay.status(text);
    if (running) getSystemService(NotificationManager.class).notify(10, notification());
    sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
  }

  private void updateHistory() {
    if (overlay != null) overlay.render(history.snapshot());
    sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
  }

  private void prepareWorker() throws Exception {
    long lifetime = ++workerGeneration;
    worker =
        new InferenceWorker(
            this,
            new InferenceWorker.Listener() {
              @Override
              public void status(String text) {
                if (running && lifetime == workerGeneration && !paused)
                  CallService.this.status(text);
              }

              @Override
              public void ready(long milliseconds) {
                if (running && lifetime == workerGeneration && !paused) {
                  CallService.this.status(
                      active ? "Слушаю. Модели готовы" : "Модели готовы. Ожидаю SIM-звонок");
                }
              }

              @Override
              public void failed(String text) {
                if (lifetime == workerGeneration) {
                  stopSession();
                  if (worker != null) worker.close();
                  worker = null;
                  workerGeneration++;
                  CallService.this.status(text);
                }
              }

              @Override
              public void caption(CaptionHistory.Entry entry) {
                if (!active || lifetime != workerGeneration) return;
                if (recording != null) {
                  try {
                    recording.caption(entry);
                  } catch (Exception error) {
                    stopRecording(error);
                  }
                }
                history.update(entry);
                updateHistory();
              }
            });
    worker.endSession();
  }

  private void startSession() {
    if (active || languages == null || worker == null) return;
    if (newConversation) history.clear();
    worker.beginSession(newConversation);
    newConversation = false;
    active = true;
    long generation = ++captureGeneration;
    try {
      recording = CallTestRecorder.startIfArmed(this, sim);
    } catch (Exception error) {
      android.util.Log.e("CallTranslate", "Test recording setup failed", error);
    }
    try {
      overlay.show(
          languages,
          () -> onStartCommand(new Intent().setAction(PAUSE), 0, 0),
          this::stopSelf,
          code -> {
            new SettingsStore(this).set("source", code);
            onStartCommand(new Intent().setAction(LANGUAGE), 0, 0);
          });
      overlay.render(history.snapshot());
      if (sim) {
        if (worker.ready()) status("Подключение захвата через Shizuku...");
        connection =
            new ShizukuConnection(
                this,
                new ShizukuConnection.Listener() {
                  @Override
                  public void connected(ICaptureService service) {
                    main.post(() -> startCallCapture(service, generation));
                  }

                  @Override
                  public void failed(String reason) {
                    main.post(
                        () -> {
                          if (generation == captureGeneration) {
                            stopSession();
                            status(reason);
                          }
                        });
                  }
                });
        connection.connect();
      } else {
        startMicrophone(generation);
      }
    } catch (Exception error) {
      stopSession();
      status(error.getMessage() == null ? error.toString() : error.getMessage());
    }
  }

  private void startCallCapture(ICaptureService service, long generation) {
    if (!active || generation != captureGeneration) return;
    capture = service;
    try {
      ParcelFileDescriptor downlink = capture.start(MediaRecorder.AudioSource.VOICE_DOWNLINK);
      read(new ParcelFileDescriptor.AutoCloseInputStream(downlink), "Собеседник", generation);
      if (worker.ready()) status("Слушаю. Модели готовы");
      if (new SettingsStore(this).owner()) {
        try {
          read(
              new ParcelFileDescriptor.AutoCloseInputStream(
                  capture.start(MediaRecorder.AudioSource.VOICE_UPLINK)),
              "Я",
              generation);
        } catch (Exception error) {
          status("Доступен только собеседник. Uplink: " + error.getMessage());
        }
      }
    } catch (Exception error) {
      stopSession();
      status("Захват SIM недоступен: " + error.getMessage());
    }
  }

  private void startMicrophone(long generation) throws Exception {
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("Разреши доступ к микрофону");
    int minimum =
        AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    microphone =
        new AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
            .setBufferSizeInBytes(Math.max(6400, minimum * 4))
            .build();
    microphone.startRecording();
    if (worker.ready()) status("Слушаю. Модели готовы");
    AudioRecord recorder = microphone;
    read(
        new InputStream() {
          @Override
          public int read() {
            throw new UnsupportedOperationException();
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            int count = recorder.read(buffer, offset, length, AudioRecord.READ_BLOCKING);
            return count < 0 ? -1 : count;
          }
        },
        "Микрофон",
        generation);
  }

  private void read(InputStream input, String side, long generation) {
    inputs.add(input);
    InferenceWorker sessionWorker = worker;
    long captureSession = sessionWorker.session();
    CallTestRecorder sessionRecording = recording;
    Thread reader =
        new Thread(
            () -> {
              long revision = sessionWorker.revision();
              AudioWindow streamChunks = new AudioWindow(1600);
              SpeechSegmenter phrases = new SpeechSegmenter();
              try {
                byte[] buffer = new byte[3200];
                while (active && captureGeneration == generation) {
                  int count = input.read(buffer);
                  if (!active || captureGeneration != generation) break;
                  if (count < 0) throw new java.io.IOException("Аудиопоток завершился");
                  if (sessionRecording != null) {
                    try {
                      sessionRecording.audio(side, buffer, count);
                    } catch (Exception error) {
                      try {
                        sessionRecording.close();
                      } catch (Exception closeError) {
                        error.addSuppressed(closeError);
                      }
                      android.util.Log.e("CallTranslate", "Test audio recording failed", error);
                    }
                  }
                  long current = sessionWorker.revision();
                  if (current != revision) {
                    revision = current;
                    streamChunks = new AudioWindow(1600);
                    phrases = new SpeechSegmenter();
                  }
                  for (float[] samples :
                      sessionWorker.streaming()
                          ? streamChunks.append(buffer, count)
                          : phrases.append(buffer, count))
                    sessionWorker.submit(samples, side, captureSession, revision);
                }
              } catch (Exception error) {
                main.post(
                    () -> {
                      if (active && captureGeneration == generation) {
                        stopSession();
                        status("Захват остановлен: " + error.getMessage());
                      }
                    });
              } finally {
                try {
                  input.close();
                } catch (Exception error) {
                  android.util.Log.e("CallTranslate", "Audio reader cleanup failed", error);
                }
              }
            },
            "pcm-" + side);
    readers.add(reader);
    reader.start();
  }

  private void stopSession() {
    stopSession(true);
  }

  private void stopSession(boolean closeOverlay) {
    active = false;
    captureGeneration++;
    if (worker != null) worker.endSession();
    history.finishPending("Перевод прерван");
    updateHistory();
    stopRecording(null);
    for (InputStream input : inputs) {
      try {
        input.close();
      } catch (Exception error) {
        android.util.Log.e("CallTranslate", "Closing capture input failed", error);
      }
    }
    inputs.clear();
    if (microphone != null) {
      microphone.release();
      microphone = null;
    }
    if (capture != null) {
      try {
        capture.stop();
      } catch (Exception error) {
        android.util.Log.e("CallTranslate", "Stopping shell capture failed", error);
      }
      capture = null;
    }
    if (connection != null) {
      connection.close();
      connection = null;
    }
    for (Thread reader : readers) reader.interrupt();
    readers.clear();
    if (overlay != null && closeOverlay) overlay.close();
  }

  private void recordStatus(String value) {
    if (recording == null) return;
    try {
      recording.status(value);
    } catch (Exception error) {
      stopRecording(error);
    }
  }

  private void stopRecording(Exception reason) {
    CallTestRecorder previous = recording;
    recording = null;
    if (previous == null) return;
    try {
      previous.close();
    } catch (Exception error) {
      if (reason == null) reason = error;
      else reason.addSuppressed(error);
    }
    if (reason != null) android.util.Log.e("CallTranslate", "Test recording failed", reason);
  }

  @Override
  public void onDestroy() {
    running = false;
    if (telephone != null && observer != null) telephone.unregisterTelephonyCallback(observer);
    stopSession();
    workerGeneration++;
    if (worker != null) {
      worker.close();
      worker = null;
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    status("Перевод остановлен");
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
