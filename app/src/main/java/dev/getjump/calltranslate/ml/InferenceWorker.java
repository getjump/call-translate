package dev.getjump.calltranslate.ml;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.getjump.calltranslate.LanguageCatalog;
import dev.getjump.calltranslate.ModelRepository;
import dev.getjump.calltranslate.SettingsStore;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.ElevenLabsStream;
import dev.getjump.calltranslate.core.CaptionHistory;
import dev.getjump.calltranslate.core.ConversationLanguage;
import dev.getjump.calltranslate.core.DraftTranslation;
import dev.getjump.calltranslate.core.SerbianText;
import dev.getjump.calltranslate.core.SessionGate;
import dev.getjump.calltranslate.ml.npu.NpuEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class InferenceWorker implements AutoCloseable {
  public interface Listener {
    void caption(CaptionHistory.Entry entry);

    void status(String status);

    void failed(String error);

    default void ready(long milliseconds) {}
  }

  private record Window(
      float[] samples,
      String side,
      String source,
      String target,
      long session,
      long revision,
      long id,
      long submitted) {}

  private record Translation(
      Window window,
      String original,
      String detected,
      long recognition,
      DraftTranslation.Request request) {
    Translation(Window window, String original, String detected, long recognition) {
      this(window, original, detected, recognition, null);
    }
  }

  private static final class CloudCaption {
    final Window window;
    final DraftTranslation state = new DraftTranslation();
    long recognition;

    CloudCaption(Window window) {
      this.window = window;
    }
  }

  private static final java.util.concurrent.Semaphore engines =
      new java.util.concurrent.Semaphore(1, true);
  private static final AtomicLong ids = new AtomicLong();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final SessionGate gate = new SessionGate();
  private final ConversationLanguage conversationLanguages = new ConversationLanguage();
  private final ArrayBlockingQueue<Window> queue = new ArrayBlockingQueue<>(2);
  private final ArrayBlockingQueue<Translation> translations = new ArrayBlockingQueue<>(8);
  private final Map<String, CloudInput> streams = new HashMap<>();
  private final Map<Long, CloudCaption> cloudCaptions = new HashMap<>();
  private final SettingsStore settings;
  private final LanguageCatalog languages;
  private final ModelRepository models;
  private final Listener listener;
  private final Context context;
  private final String provider;
  private final String recognitionId;
  private final String translationId;
  private final String backend;
  private final String cloudModel;
  private final long startup = System.nanoTime();
  private final AtomicBoolean fatal = new AtomicBoolean();
  private final Thread thread;
  private volatile Thread translationThread;
  private volatile boolean open = true;
  private volatile boolean ready;
  private volatile boolean recognitionReady;
  private volatile boolean translationReady;
  private volatile long session = gate.start();
  private volatile NativeEngine asr;
  private volatile NpuEngine npu;
  private volatile String key;

  public InferenceWorker(Context context, Listener listener) throws Exception {
    this.context = context.getApplicationContext();
    this.listener = listener;
    settings = new SettingsStore(context);
    languages = new LanguageCatalog(context);
    models = new ModelRepository(context);
    provider = settings.provider();
    recognitionId = settings.asr();
    translationId = settings.translation();
    backend = settings.backend();
    cloudModel = settings.cloudModel();
    thread = new Thread(this::run, "call-recognition");
    thread.start();
  }

  public boolean ready() {
    return ready;
  }

  public boolean streaming() {
    return provider.equals("elevenlabs");
  }

  public long revision() {
    return gate.revision();
  }

  public long session() {
    return session;
  }

  public void beginSession(boolean newConversation) {
    synchronized (gate) {
      clearStreams();
      session = gate.start();
      queue.clear();
      translations.clear();
      if (newConversation) conversationLanguages.clear();
    }
  }

  public void endSession() {
    synchronized (gate) {
      gate.stop();
      queue.clear();
      translations.clear();
      clearStreams();
    }
  }

  public void languagesChanged() {
    synchronized (gate) {
      gate.languageChanged();
      queue.clear();
      translations.clear();
      clearStreams();
      conversationLanguages.clear();
    }
  }

  private void clearStreams() {
    for (CloudInput input : streams.values()) input.stream.close();
    streams.clear();
    cloudCaptions.clear();
  }

  public boolean submit(float[] samples, String speaker, long revision) {
    return submit(samples, speaker, session, revision);
  }

  public boolean submit(float[] samples, String speaker, long captureSession, long revision) {
    if (samples.length == 0) return false;
    synchronized (gate) {
      if (!open || !gate.accepts(captureSession, revision)) return false;
      String source = speaker.equals("Я") ? "auto" : settings.source();
      if (streaming()) {
        if (!ready) return false;
        try {
          CloudInput input = streams.get(speaker);
          if (input == null) {
            input = new CloudInput(speaker, source, settings.target(), captureSession, revision);
            streams.put(speaker, input);
          }
          return input.stream.send(samples);
        } catch (Exception error) {
          failWorker(error);
          return false;
        }
      }
      double energy = 0;
      for (float sample : samples) energy += sample * sample;
      if (Math.sqrt(energy / samples.length) < 0.003) return false;
      Window window =
          new Window(
              samples,
              speaker,
              source,
              settings.target(),
              captureSession,
              revision,
              ids.incrementAndGet(),
              System.nanoTime());
      if (!queue.offer(window)) {
        queue.poll();
        queue.offer(window);
        status(window, "Распознавание отстает: старый блок пропущен");
      }
      return true;
    }
  }

  private boolean accepts(Window window) {
    return open && gate.accepts(window.session(), window.revision());
  }

  private void status(Window window, String value) {
    main.post(
        () -> {
          if (accepts(window)) listener.status(value);
        });
  }

  private void loading(String value) {
    main.post(
        () -> {
          if (open) listener.status(value);
        });
  }

  private void failWorker(Exception error) {
    if (!open || !fatal.compareAndSet(false, true)) return;
    String message = error.getMessage() == null ? error.toString() : error.getMessage();
    main.post(() -> listener.failed(message));
    close();
  }

  private void publish(
      Window window, String original, String translated, String detected, long recognition) {
    CaptionHistory.Entry entry =
        new CaptionHistory.Entry(
            window.id(),
            window.side(),
            original,
            translated,
            detected,
            recognition,
            (System.nanoTime() - window.submitted()) / 1000000);
    main.post(
        () -> {
          if (accepts(window)) listener.caption(entry);
        });
  }

  private boolean accepts(Translation task) {
    synchronized (gate) {
      if (!accepts(task.window())) return false;
      if (task.request() == null) return true;
      CloudCaption caption = cloudCaptions.get(task.window().id());
      return caption != null && caption.state.accepts(task.request());
    }
  }

  private void publishCloud(Window window) {
    main.post(
        () -> {
          CaptionHistory.Entry entry;
          synchronized (gate) {
            if (!accepts(window)) return;
            CloudCaption caption = cloudCaptions.get(window.id());
            if (caption == null) return;
            entry = cloudEntry(caption);
          }
          listener.caption(entry);
        });
  }

  private CaptionHistory.Entry cloudEntry(CloudCaption caption) {
    Window window = caption.window;
    DraftTranslation state = caption.state;
    return new CaptionHistory.Entry(
        window.id(),
        window.side(),
        state.original(),
        state.translation(),
        state.language(),
        caption.recognition,
        (System.nanoTime() - window.submitted()) / 1000000,
        state.draft());
  }

  private void publishTranslation(Translation task, String translated) {
    if (task.request() == null) {
      publish(task.window(), task.original(), translated, task.detected(), task.recognition());
      return;
    }
    main.post(
        () -> {
          CaptionHistory.Entry entry;
          synchronized (gate) {
            if (!accepts(task.window())) return;
            CloudCaption caption = cloudCaptions.get(task.window().id());
            if (caption == null || !caption.state.complete(task.request(), translated)) return;
            entry = cloudEntry(caption);
          }
          listener.caption(entry);
        });
  }

  private void enqueue(Translation item) {
    synchronized (gate) {
      if (!accepts(item)) return;
      if (item.request() != null)
        translations.removeIf(queued -> queued.window().id() == item.window().id());
      if (!translations.offer(item)) {
        Translation preview =
            translations.stream()
                .filter(queued -> queued.request() != null && !queued.request().committed())
                .findFirst()
                .orElse(null);
        if (preview != null) translations.remove(preview);
        else {
          if (item.request() != null && !item.request().committed()) return;
          Translation skipped = translations.poll();
          if (skipped != null) publishTranslation(skipped, "Перевод пропущен: очередь переполнена");
          status(item.window(), "Перевод отстает");
        }
        translations.offer(item);
      }
    }
  }

  private synchronized void modelsReady() {
    if (open && recognitionReady && translationReady && !ready) {
      ready = true;
      long elapsed = (System.nanoTime() - startup) / 1000000;
      main.post(
          () -> {
            if (open) listener.ready(elapsed);
          });
    }
  }

  private void translate(ModelRepository.Model model) {
    try (NllbEngine engine = new NllbEngine(models.directory(model), () -> !open)) {
      translationReady = true;
      modelsReady();
      while (open) {
        Translation task = translations.take();
        Window window = task.window();
        if (!accepts(task)) continue;
        LanguageCatalog.Language source = languages.sourceOrNull(task.detected());
        if (source == null) {
          publishTranslation(task, "Перевод недоступен для языка " + task.detected());
          continue;
        }
        try {
          LanguageCatalog.Language target = languages.target(window.target());
          String input =
              source.nllb.equals("srp_Cyrl")
                  ? SerbianText.toCyrillic(task.original())
                  : task.original();
          String output = engine.translate(input, source.token, target.token, () -> !accepts(task));
          publishTranslation(task, output);
        } catch (CancellationException canceled) {
        } catch (Exception error) {
          publishTranslation(task, "Ошибка перевода: " + error.getMessage());
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      failWorker(error);
    } finally {
      translationReady = false;
    }
  }

  private void run() {
    boolean leased = false;
    try {
      engines.acquire();
      leased = true;
      if (!open) return;
      var translationModel = models.model(translationId);
      if (!models.installed(translationModel))
        throw new IllegalStateException("Сначала установи модель перевода");
      loading("Проверка локальных моделей...");
      models.verify(translationModel, () -> !open);
      ModelRepository.Model recognitionModel = null;
      if (streaming()) {
        key = new CredentialStore(context).load();
        if (key.isBlank()) throw new IllegalStateException("Добавь и проверь ключ ElevenLabs");
      } else {
        if (!provider.equals("local")) throw new IllegalStateException("Неизвестный провайдер ASR");
        recognitionModel = models.model(recognitionId);
        if (!models.installed(recognitionModel))
          throw new IllegalStateException("Сначала установи модель распознавания");
        models.verify(recognitionModel, () -> !open);
      }
      if (!open) return;
      loading(streaming() ? "Загрузка локального перевода..." : "Загрузка локальных моделей...");
      translationThread = new Thread(() -> translate(translationModel), "call-translation");
      translationThread.start();
      if (!streaming()) {
        if (recognitionModel.json.optString("runtime").equals("qnn-htp"))
          npu = new NpuEngine(context, models.directory(recognitionModel));
        else
          asr =
              new NativeEngine(
                  models.asrFile(recognitionModel).getPath(), backend.equals("Vulkan"));
      }
      recognitionReady = true;
      modelsReady();
      while (open) {
        Window window = queue.take();
        String inputLanguage;
        synchronized (gate) {
          if (!accepts(window)) continue;
          inputLanguage = conversationLanguages.input(window.side(), window.source());
        }
        long start = System.nanoTime();
        String[] result;
        try {
          result =
              npu != null
                  ? npu.transcribe(window.samples(), inputLanguage)
                  : asr.transcribe(window.samples(), inputLanguage, 0);
        } catch (CancellationException canceled) {
          continue;
        } catch (Exception error) {
          if (accepts(window)) failWorker(error);
          continue;
        }
        long recognition = (System.nanoTime() - start) / 1000000;
        String original = result[0].trim();
        if (original.isBlank()) continue;
        String detected = result[1];
        synchronized (gate) {
          if (!accepts(window)) continue;
          if (window.source().equals("auto"))
            conversationLanguages.observe(window.side(), detected);
        }
        publish(window, original, null, detected, recognition);
        enqueue(new Translation(window, original, detected, recognition));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      failWorker(error);
    } finally {
      ready = false;
      try {
        if (npu != null) npu.close();
        if (asr != null) asr.close();
        Thread translator = translationThread;
        if (translator != null) {
          translator.interrupt();
          boolean interrupted = Thread.interrupted();
          while (translator.isAlive()) {
            try {
              translator.join();
            } catch (InterruptedException ignored) {
              interrupted = true;
            }
          }
          if (interrupted) Thread.currentThread().interrupt();
        }
      } finally {
        asr = null;
        npu = null;
        key = null;
        if (leased) engines.release();
      }
    }
  }

  private final class CloudInput {
    private final ElevenLabsStream stream;
    private final Map<Long, Window> utterances = new HashMap<>();
    private long segmentStart = System.nanoTime();

    CloudInput(String side, String source, String target, long captureSession, long revision)
        throws Exception {
      Window lifetime =
          new Window(new float[0], side, source, target, captureSession, revision, 0, segmentStart);
      stream =
          new ElevenLabsStream(
              key,
              cloudModel,
              source,
              new ElevenLabsStream.Listener() {
                @Override
                public void status(String value) {
                  InferenceWorker.this.status(lifetime, value);
                }

                @Override
                public void failed(String value) {
                  main.post(
                      () -> {
                        if (accepts(lifetime)) failWorker(new IllegalStateException(value));
                      });
                }

                @Override
                public void transcript(
                    long segmentId, String text, String language, boolean committed) {
                  main.post(
                      () -> {
                        synchronized (gate) {
                          if (!accepts(lifetime) || text.isBlank()) return;
                          Window window =
                              utterances.computeIfAbsent(
                                  segmentId,
                                  id ->
                                      new Window(
                                          new float[0],
                                          side,
                                          source,
                                          target,
                                          captureSession,
                                          revision,
                                          ids.incrementAndGet(),
                                          segmentStart));
                          String detected = language == null ? "" : language;
                          long elapsed = (System.nanoTime() - window.submitted()) / 1000000;
                          CloudCaption caption =
                              cloudCaptions.computeIfAbsent(
                                  window.id(), id -> new CloudCaption(window));
                          caption.state.update(text, detected, committed);
                          caption.recognition = elapsed;
                          publishCloud(window);
                          DraftTranslation.Request request =
                              caption.state.request(System.nanoTime() / 1000000);
                          if (request != null)
                            enqueue(
                                new Translation(
                                    window,
                                    request.original(),
                                    request.language(),
                                    elapsed,
                                    request));
                          if (committed) {
                            utterances.remove(segmentId);
                            segmentStart = System.nanoTime();
                          }
                        }
                      });
                }
              });
    }
  }

  @Override
  public void close() {
    open = false;
    ready = false;
    endSession();
    NativeEngine engine = asr;
    if (engine != null) engine.cancel();
    NpuEngine accelerator = npu;
    if (accelerator != null) accelerator.cancel();
    thread.interrupt();
    Thread translator = translationThread;
    if (translator != null) translator.interrupt();
  }
}
