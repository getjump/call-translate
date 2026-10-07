package dev.getjump.calltranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import dev.getjump.calltranslate.agent.AgentAudioIo;
import dev.getjump.calltranslate.capture.ShizukuConnection;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.agent.ElevenAgentSession;
import dev.getjump.calltranslate.cloud.agent.ElevenAgentsApi;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;

public final class VoiceAgentService extends Service {
  public static final String UPDATE = "dev.getjump.calltranslate.AGENT_UPDATE";
  public static final String START_MIC = "agent_mic";
  public static final String START_SIM = "agent_sim";
  public static final String STOP = "agent_stop";
  public static final String INSTRUCT = "agent_instruction";
  public static final String MUTE = "agent_mute";
  public static volatile boolean running;
  public static volatile boolean simMode;
  public static volatile boolean connected;
  public static volatile boolean microphoneMuted;
  public static volatile String latestStatus = "Выбери агента";
  public static final CaptionHistory history = new CaptionHistory();
  private static final AtomicLong ids = new AtomicLong();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final AtomicLong epoch = new AtomicLong();
  private final AtomicReference<Call> activeHttp = new AtomicReference<>();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .callTimeout(25, TimeUnit.SECONDS)
          .build();
  private volatile ElevenAgentSession session;
  private volatile AgentAudioIo audio;
  private ShizukuConnection connection;
  private TelephonyManager telephone;
  private Observer observer;
  private boolean inCall;
  private boolean ownsMute;
  private boolean originalMute;
  private boolean requestedMute;
  private String restoreWarning = "";
  private SettingsStore settings;
  private CaptionOverlay overlay;

  @Override
  public void onCreate() {
    super.onCreate();
    settings = new SettingsStore(this);
    overlay = new CaptionOverlay(this);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "voice-agent", "Голосовой агент", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      if (intent != null) status("Агент остановлен");
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = intent.getAction();
    if (INSTRUCT.equals(action)) {
      String instruction = intent.getStringExtra("instruction");
      ElevenAgentSession current = session;
      if (current != null && current.sendContext(instruction)) {
        caption("Я, приватно", instruction);
        status("Поручение передано агенту приватно");
      } else status("Сначала дождись подключения агента");
      return START_NOT_STICKY;
    }
    if (MUTE.equals(action)) {
      mute(intent.getBooleanExtra("muted", false));
      return START_NOT_STICKY;
    }
    if (!START_MIC.equals(action) && !START_SIM.equals(action)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (running) {
      status("Сначала останови текущую сессию агента");
      return START_NOT_STICKY;
    }
    simMode = START_SIM.equals(action);
    if (simMode && !ShizukuConnection.permitted()) {
      status("Для SIM запусти Shizuku и разреши доступ");
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      startForeground(
          20,
          notification(),
          simMode
              ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
              : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      stopService(new Intent(this, CallService.class));
      running = true;
      connected = false;
      history.clear();
      if (simMode) {
        telephone = getSystemService(TelephonyManager.class);
        observer = new Observer();
        telephone.registerTelephonyCallback(getMainExecutor(), observer);
        status("Ожидаю входящий или исходящий SIM-звонок. Ты тоже услышишь агента");
      } else startSession();
    } catch (Exception error) {
      status(message(error, "Агент не запустился"));
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private final class Observer extends TelephonyCallback
      implements TelephonyCallback.CallStateListener {
    @Override
    public void onCallStateChanged(int state) {
      if (!running || !simMode) return;
      boolean previous = inCall;
      android.util.Log.i("CallAgent", "call_state=" + state + " previous_in_call=" + previous);
      inCall = state == TelephonyManager.CALL_STATE_OFFHOOK;
      if (inCall && !previous) {
        history.clear();
        startSession();
      } else if (!inCall && previous) {
        stopSession();
        status("Звонок завершен. Ожидаю входящий или исходящий SIM-звонок");
      }
    }
  }

  private boolean current(long generation) {
    return running && epoch.get() == generation && (!simMode || inCall);
  }

  private void startSession() {
    stopSession();
    long generation = epoch.incrementAndGet();
    android.util.Log.i("CallAgent", "session_start epoch=" + generation);
    if (simMode)
      overlay.showAgent(
          "Подключение голосового агента...",
          () ->
              startActivity(
                  new Intent(this, AgentActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)),
          this::stopSelf);
    status("Подключение голосового агента...");
    String agentId = getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentId", "");
    String brief = getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentBrief", "");
    String speech =
        getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentLanguage", "sr");
    network.execute(
        () -> {
          try {
            if (!current(generation)) return;
            Call.Factory calls =
                request -> {
                  Call call = http.newCall(request);
                  activeHttp.set(call);
                  if (!current(generation)) call.cancel();
                  return call;
                };
            if (agentId.isBlank())
              throw new IllegalStateException("Сначала выбери ElevenLabs-агента");
            String key = new CredentialStore(this).load();
            var details = ElevenAgentsApi.getAgent(key, agentId, calls);
            if (!current(generation)) return;
            if (details.textOnly() || !details.clientEvents().contains("audio"))
              throw new IllegalStateException(
                  "В ElevenLabs включи голос и событие audio для этого агента");
            if (!details.configuredLanguage().equals(speech))
              throw new IllegalStateException(
                  "Язык агента в ElevenLabs: "
                      + details.configuredLanguage()
                      + ". Выбери такой же язык в приложении или измени настройку агента");
            String owner = new LanguageCatalog(this).target(settings.target()).code;
            var config = new ElevenAgentSession.SessionConfig(brief, speech, owner);
            if (!current(generation)) return;
            String signed = ElevenAgentsApi.signedUrl(key, agentId, calls);
            if (!current(generation)) return;
            main.post(
                () -> {
                  if (!current(generation)) return;
                  if (simMode) attachCall(generation, details, signed, config);
                  else open(generation, details, signed, config, null);
                });
          } catch (Exception error) {
            main.post(() -> fail(generation, message(error, "Не удалось подключить агента")));
          }
        });
  }

  private void attachCall(
      long generation,
      ElevenAgentsApi.AgentDetails details,
      String signed,
      ElevenAgentSession.SessionConfig config) {
    connection =
        new ShizukuConnection(
            this,
            new ShizukuConnection.Listener() {
              @Override
              public void connected(ICaptureService capture) {
                main.post(
                    () -> {
                      if (current(generation)) open(generation, details, signed, config, capture);
                    });
              }

              @Override
              public void failed(String reason) {
                main.post(() -> fail(generation, reason));
              }
            });
    connection.connect();
  }

  private void open(
      long generation,
      ElevenAgentsApi.AgentDetails details,
      String signed,
      ElevenAgentSession.SessionConfig config,
      ICaptureService capture) {
    if (!current(generation)) return;
    try {
      audio =
          new AgentAudioIo(
              this,
              details.inputSampleRate(),
              details.outputSampleRate(),
              capture,
              new AgentAudioIo.Listener() {
                @Override
                public boolean input(byte[] pcm) {
                  ElevenAgentSession active = session;
                  if (!current(generation) || active == null) return false;
                  active.sendPcm16(pcm);
                  return current(generation);
                }

                @Override
                public void failed(String reason) {
                  main.post(() -> fail(generation, reason));
                }
              });
      session =
          new ElevenAgentSession(
              signed,
              config,
              new ElevenAgentSession.Listener() {
                private final AtomicLong receivedAudioBytes = new AtomicLong();

                @Override
                public void status(String value) {
                  main.post(
                      () -> {
                        if (current(generation)) VoiceAgentService.this.status(value);
                      });
                }

                @Override
                public void ready(String conversationId, int inputRate, int outputRate) {
                  android.util.Log.i(
                      "CallAgent",
                      "session_ready epoch="
                          + generation
                          + " input_hz="
                          + inputRate
                          + " output_hz="
                          + outputRate);
                  main.post(
                      () -> {
                        if (!current(generation)) return;
                        try {
                          if (inputRate != details.inputSampleRate()
                              || outputRate != details.outputSampleRate())
                            throw new IllegalStateException(
                                "ElevenLabs изменил аудиоформат; обнови конфигурацию агента");
                          audio.start();
                          connected = true;
                          VoiceAgentService.this.status(
                              simMode
                                  ? "Агент слушает SIM и отвечает в звонок и тебе. SIM-вывод -"
                                      + " эксперимент"
                                  : "Агент слушает микрофон и отвечает через динамик");
                        } catch (Exception error) {
                          fail(generation, message(error, "Звук агента не запустился"));
                        }
                      });
                }

                @Override
                public void transcript(boolean agent, String text) {
                  main.post(
                      () -> {
                        if (current(generation))
                          caption(agent ? "Агент" : simMode ? "Собеседник" : "Ты", text);
                      });
                }

                @Override
                public void audio(byte[] pcm16, int rate, long eventId) {
                  AgentAudioIo route = audio;
                  if (!current(generation) || route == null) return;
                  if (receivedAudioBytes.getAndAdd(pcm16.length) == 0)
                    android.util.Log.i("CallAgent", "speech_audio_received epoch=" + generation);
                  if (!route.play(pcm16, rate))
                    main.post(
                        () ->
                            fail(
                                generation,
                                "Голос агента отстает: очередь заполнена или формат неверен"));
                }

                @Override
                public void interrupted(long eventId) {
                  android.util.Log.i(
                      "CallAgent", "speech_interrupted epoch=" + generation + " event=" + eventId);
                  AgentAudioIo route = audio;
                  if (current(generation) && route != null) route.interruptPlayback();
                }

                @Override
                public void failed(String reason) {
                  main.post(() -> fail(generation, reason));
                }

                @Override
                public void closed() {
                  android.util.Log.i("CallAgent", "session_closed epoch=" + generation);
                  main.post(
                      () -> {
                        if (current(generation)) finishConversation(generation);
                      });
                }
              });
    } catch (Exception error) {
      fail(generation, message(error, "Маршрут голоса недоступен"));
    }
  }

  private void finishConversation(long generation) {
    if (!current(generation)) return;
    connected = false;
    AgentAudioIo route = audio;
    if (route == null) {
      fail(generation, "Диалог агента завершен");
      return;
    }
    status("Агент заканчивает ответ...");
    try {
      route.finishPlayback(
          () ->
              main.post(
                  () -> {
                    if (!current(generation)) return;
                    android.util.Log.i("CallAgent", "normal_drain_complete epoch=" + generation);
                    stopSession(false);
                    status("Диалог агента завершен");
                    if (!simMode) stopSelf();
                  }));
    } catch (Exception error) {
      fail(generation, message(error, "Не удалось завершить ответ агента"));
    }
  }

  private void caption(String speaker, String text) {
    if (text == null || text.isBlank()) return;
    history.update(new CaptionHistory.Entry(ids.incrementAndGet(), speaker, text, "", "", 0, 0));
    if (overlay != null) overlay.render(history.snapshot());
    broadcast();
  }

  private void fail(long generation, String reason) {
    if (!current(generation)) return;
    android.util.Log.i("CallAgent", "session_failed epoch=" + generation);
    stopSession(false);
    status(reason);
    if (!simMode) stopSelf();
  }

  private void mute(boolean muted) {
    if (!running
        || !simMode
        || !inCall
        || getSystemService(AudioManager.class).getMode() != AudioManager.MODE_IN_CALL) {
      status("Микрофон звонка можно отключить только во время SIM-сессии");
      return;
    }
    AudioManager manager = getSystemService(AudioManager.class);
    if (!ownsMute) originalMute = manager.isMicrophoneMute();
    try {
      manager.setMicrophoneMute(muted);
      if (manager.isMicrophoneMute() != muted)
        throw new IllegalStateException("Система не изменила mute микрофона");
      restoreWarning = "";
      requestedMute = muted;
      ownsMute = true;
      status(
          muted
              ? "Твой микрофон выключен. Влияние mute на голос агента требует проверки"
              : "Твой микрофон включен");
    } catch (Exception error) {
      status(message(error, "Не удалось изменить mute"));
    }
  }

  private void stopSession() {
    stopSession(true);
  }

  private void stopSession(boolean closeOverlay) {
    if (session != null || audio != null)
      android.util.Log.i(
          "CallAgent", "session_stop epoch=" + epoch.get() + " close_overlay=" + closeOverlay);
    epoch.incrementAndGet();
    Call request = activeHttp.getAndSet(null);
    if (request != null) request.cancel();
    connected = false;
    ElevenAgentSession previous = session;
    session = null;
    if (previous != null) previous.close();
    AgentAudioIo route = audio;
    audio = null;
    if (route != null) route.close();
    if (connection != null) {
      connection.close();
      connection = null;
    }
    if (ownsMute) {
      AudioManager manager = getSystemService(AudioManager.class);
      try {
        if (manager.isMicrophoneMute() == requestedMute) {
          manager.setMicrophoneMute(originalMute);
          if (manager.isMicrophoneMute() != originalMute)
            throw new IllegalStateException("Microphone mute did not restore");
        }
      } catch (RuntimeException error) {
        android.util.Log.e("CallAgent", "Restoring microphone mute failed", error);
        restoreWarning = "Проверь кнопку mute в системном экране звонка";
        latestStatus = restoreWarning;
      } finally {
        ownsMute = false;
      }
    }
    microphoneMuted = getSystemService(AudioManager.class).isMicrophoneMute();
    if (closeOverlay && overlay != null) overlay.close();
  }

  private void status(String value) {
    latestStatus = restoreWarning.isEmpty() ? value : value + ". " + restoreWarning;
    if (overlay != null) overlay.status(latestStatus);
    broadcast();
    if (running) getSystemService(NotificationManager.class).notify(20, notification());
  }

  private void broadcast() {
    microphoneMuted = getSystemService(AudioManager.class).isMicrophoneMute();
    sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            20,
            new Intent(this, AgentActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            21,
            new Intent(this, VoiceAgentService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "voice-agent")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Голосовой агент")
        .setContentText(latestStatus)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
        .build();
  }

  private static String message(Exception error, String fallback) {
    return error.getMessage() == null ? fallback : error.getMessage();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    running = false;
    stopSession();
    if (telephone != null && observer != null) telephone.unregisterTelephonyCallback(observer);
    network.shutdownNow();
    http.connectionPool().evictAll();
    broadcast();
    super.onDestroy();
  }
}
