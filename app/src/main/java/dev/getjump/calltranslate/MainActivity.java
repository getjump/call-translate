package dev.getjump.calltranslate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
  private SettingsStore settings;
  private LanguageCatalog languages;
  private ModelRepository models;
  private LinearLayout content;
  private TextView status;
  private CaptionFeed caption;
  private TextView modelStatus;
  private Spinner source;
  private Spinner target;
  private Spinner asr;
  private Spinner translation;
  private Spinner backend;
  private Spinner provider;
  private CloudSettingsView cloud;
  private CheckBox owner;
  private boolean registered;
  private final ExecutorService downloads = Executors.newSingleThreadExecutor();
  private final AtomicBoolean canceled = new AtomicBoolean();
  private boolean downloading;
  private volatile boolean destroyed;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refresh();
        }
      };
  private final Shizuku.OnBinderReceivedListener binderReceived =
      () -> runOnUiThread(this::refresh);
  private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(this::refresh);
  private final Shizuku.OnRequestPermissionResultListener permissionResult =
      (code, result) -> runOnUiThread(this::refresh);

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    try {
      settings = new SettingsStore(this);
      languages = new LanguageCatalog(this);
      models = new ModelRepository(this);
      build();
      Shizuku.addBinderReceivedListenerSticky(binderReceived);
      Shizuku.addBinderDeadListener(binderDead);
      Shizuku.addRequestPermissionResultListener(permissionResult);
    } catch (Exception error) {
      TextView message = new TextView(this);
      message.setText("Не удалось открыть приложение: " + error.toString());
      setContentView(message);
    }
  }

  private void build() {
    if (cloud != null) cloud.close();
    ScrollView scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int inset = dp(22);
    content.setPadding(inset, dp(48), inset, dp(32));
    scroll.addView(content);
    setContentView(scroll);
    title("Call Translate", 30);
    subtitle("Понимать звонок. Локальное распознавание или ElevenLabs, перевод на телефоне.");
    status = title(CallService.latestStatus, 15);
    status.setTextColor(0xFF0F766E);
    caption = new CaptionFeed(this, 320);
    content.addView(caption);
    subtitle("Входной язык - можно менять во время звонка");
    source =
        spinner(
            languages.sources().stream()
                .map(LanguageCatalog::sourceLabel)
                .collect(java.util.stream.Collectors.toList()));
    source.setId(R.id.source_language);
    source.setSelection(Math.max(0, languages.sources().indexOf(settings.source())));
    selection(
        source,
        position -> {
          String code = languages.sources().get(position);
          if (!settings.source().equals(code)) {
            settings.set("source", code);
            command(CallService.LANGUAGE);
          }
        });
    rowButtons(
        "SIM: ждать звонок",
        () -> start(CallService.SIM),
        "Микрофон",
        () -> start(CallService.MIC));
    rowButtons(
        "Пауза / продолжить",
        () -> command(CallService.PAUSE),
        "Остановить",
        () -> command(CallService.STOP));
    button(
        "Голосовой агент ElevenLabs", () -> startActivity(new Intent(this, AgentActivity.class)));
    title("Настройки", 22);
    subtitle("Переводить на");
    target = spinner(languages.targets());
    target.setId(R.id.target_language);
    for (int i = 0; i < languages.targets().size(); i++)
      if (languages.targets().get(i).nllb.equals(settings.target())) target.setSelection(i);
    selection(
        target,
        position -> {
          String code = languages.targets().get(position).nllb;
          if (!code.equals(settings.target())) {
            settings.set("target", code);
            command(CallService.LANGUAGE);
          }
        });
    subtitle("Режим распознавания");
    provider = spinner(List.of("Локально (без интернета)", "ElevenLabs (облако)"));
    provider.setId(R.id.asr_provider);
    provider.setSelection(settings.provider().equals("elevenlabs") ? 1 : 0);
    selection(
        provider,
        position -> {
          settings.set("provider", position == 1 ? "elevenlabs" : "local");
          if (cloud != null) cloud.selected(position == 1);
          refresh();
        });
    cloud = new CloudSettingsView(this, settings);
    content.addView(cloud);
    cloud.selected(settings.provider().equals("elevenlabs"));
    subtitle("Локальная модель распознавания");
    asr = spinner(models.list("asr"));
    asr.setId(R.id.asr_model);
    selectModel(asr, "asr", settings.asr());
    subtitle("Перевод");
    translation = spinner(models.list("translation"));
    selectModel(translation, "translation", settings.translation());
    subtitle("Ускорение распознавания");
    backend = spinner(List.of("CPU"));
    backend.setId(R.id.asr_backend);
    updateBackends();
    subtitle(
        "Turbo / NPU использует отдельный пакет Qualcomm для HTP81. CPU/Vulkan доступны для"
            + " GGML-моделей. Перевод NLLB работает на CPU параллельно распознаванию.");
    owner = new CheckBox(this);
    owner.setText("Пробовать мой голос через uplink");
    owner.setChecked(settings.owner());
    owner.setOnCheckedChangeListener((button, checked) -> settings.owner(checked));
    content.addView(owner);
    subtitle(
        "Метки 'Я' и 'Собеседник' определяются аудиоканалом. Разделение нескольких голосов внутри"
            + " одного канала пока не поддерживается.");
    title("Модели", 22);
    modelStatus = subtitle("");
    subtitle(
        "Повторная загрузка переустанавливает модель. Целостность также проверяется перед запуском"
            + " перевода.");
    rowButtons(
        "Скачать ASR",
        () -> downloadSelected("asr"),
        "Скачать перевод",
        () -> downloadSelected("translation"));
    rowButtons("Импорт Whisper", this::importModel, "Отменить загрузку", () -> canceled.set(true));
    subtitle(
        "Whisper: Hugging Face ggerganov/whisper.cpp, формат GGML .bin. NLLB: проверенный"
            + " ONNX-пакет RTranslator. Все файлы загрузки проверяются по SHA-256. Для NLLB"
            + " действует CC-BY-NC-4.0.");
    title("Доступ к звонку", 22);
    rowButtons("Разрешить Shizuku", this::requestShizuku, "Открыть Shizuku", this::openShizuku);
    button(
        "Субтитры поверх звонка",
        () ->
            startActivity(
                new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
    subtitle(
        "SIM-захват требует запущенного Shizuku. После перезагрузки его обычно нужно запустить"
            + " снова. Микрофон можно использовать без Shizuku вне звонка. При отсутствии overlay"
            + " перевод остается в приложении.");
    refresh();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView title(String value, int size) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(0xFF172554);
    text.setPadding(0, dp(12), 0, dp(6));
    content.addView(text);
    return text;
  }

  private TextView subtitle(String value) {
    TextView text = title(value, 13);
    text.setTextColor(0xFF475569);
    return text;
  }

  private <T> Spinner spinner(List<T> choices) {
    Spinner picker = new Spinner(this);
    picker.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
    content.addView(picker);
    return picker;
  }

  private void selection(Spinner picker, java.util.function.IntConsumer changed) {
    picker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            changed.accept(position);
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
  }

  private void selectModel(Spinner picker, String type, String selected) {
    List<ModelRepository.Model> choices = models.list(type);
    for (int i = 0; i < choices.size(); i++)
      if (choices.get(i).id.equals(selected)) picker.setSelection(i);
    selection(
        picker,
        position -> {
          settings.set(type, choices.get(position).id);
          if (type.equals("asr") && backend != null) updateBackends();
          refresh();
        });
  }

  private void updateBackends() {
    ModelRepository.Model selected = models.model(settings.asr());
    List<String> choices =
        selected.json.optString("runtime").equals("qnn-htp")
            ? List.of("NPU")
            : NativeEngine.gpuAvailable() ? List.of("CPU", "Vulkan") : List.of("CPU");
    backend.setOnItemSelectedListener(null);
    backend.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
    int position = Math.max(0, choices.indexOf(settings.backend()));
    settings.set("backend", choices.get(position));
    backend.setSelection(position);
    selection(backend, index -> settings.set("backend", choices.get(index)));
  }

  private void button(String label, Runnable clicked) {
    Button button = new Button(this);
    button.setText(label);
    button.setOnClickListener(view -> clicked.run());
    content.addView(button);
  }

  private void rowButtons(String first, Runnable one, String second, Runnable two) {
    LinearLayout row = new LinearLayout(this);
    for (int i = 0; i < 2; i++) {
      Button button = new Button(this);
      button.setText(i == 0 ? first : second);
      Runnable clicked = i == 0 ? one : two;
      button.setOnClickListener(view -> clicked.run());
      row.addView(
          button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }
    content.addView(row);
  }

  private void start(String mode) {
    if (cloud != null && cloud.busy()) {
      message("Дождись проверки или удаления ключа ElevenLabs");
      return;
    }
    if (downloading) {
      message("Дождись установки модели");
      return;
    }
    if ((!settings.provider().equals("elevenlabs")
            && !models.installed(models.model(settings.asr())))
        || !models.installed(models.model(settings.translation()))) {
      message("Сначала скачай выбранные локальные модели");
      return;
    }
    if (settings.provider().equals("elevenlabs")) {
      try {
        if (new dev.getjump.calltranslate.cloud.CredentialStore(this).load().isBlank()) {
          message("Добавь и проверь ключ ElevenLabs в настройках");
          return;
        }
      } catch (Exception error) {
        message(error.getMessage());
        return;
      }
    }
    List<String> permissions = new ArrayList<>();
    if (mode.equals(CallService.SIM)
        && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.READ_PHONE_STATE);
    if (mode.equals(CallService.MIC)
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED)
      permissions.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!permissions.isEmpty()) {
      requestPermissions(
          permissions.toArray(new String[0]), mode.equals(CallService.SIM) ? 101 : 102);
      return;
    }
    if (mode.equals(CallService.SIM)
        && !dev.getjump.calltranslate.capture.ShizukuConnection.permitted()) {
      message("Запусти Shizuku и нажми 'Разрешить Shizuku'");
      return;
    }
    startForegroundService(new Intent(this, CallService.class).setAction(mode));
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    for (int result : results)
      if (result != PackageManager.PERMISSION_GRANTED) {
        message("Разрешение не предоставлено. Режим не запущен");
        return;
      }
    if (code == 101 || code == 102) start(code == 101 ? CallService.SIM : CallService.MIC);
  }

  private void command(String action) {
    if (CallService.running) startService(new Intent(this, CallService.class).setAction(action));
  }

  private void requestShizuku() {
    try {
      if (!Shizuku.pingBinder()) {
        message("Сначала запусти Shizuku");
        return;
      }
      Shizuku.requestPermission(200);
    } catch (Exception error) {
      message(error.toString());
    }
  }

  private void openShizuku() {
    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
    if (intent == null) message("Shizuku не установлен");
    else startActivity(intent);
  }

  private void importModel() {
    if (CallService.running || downloading) {
      message("Останови перевод и текущую загрузку перед импортом");
      return;
    }
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("application/octet-stream")
            .addCategory(Intent.CATEGORY_OPENABLE),
        300);
  }

  @Override
  public void onActivityResult(int request, int result, Intent intent) {
    super.onActivityResult(request, result, intent);
    if (request != 300 || result != RESULT_OK || intent == null || intent.getData() == null) return;
    downloading = true;
    canceled.set(false);
    modelStatus.setText("Проверка импортируемой модели...");
    downloads.execute(
        () -> {
          try {
            ModelRepository.Model imported = models.importWhisper(intent.getData(), canceled::get);
            runOnUiThread(
                () -> {
                  if (destroyed || canceled.get()) return;
                  downloading = false;
                  settings.set("asr", imported.id);
                  rebuild();
                });
          } catch (Exception error) {
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  downloading = false;
                  message(error.getMessage());
                  refresh();
                });
          }
        });
  }

  private void rebuild() {
    build();
  }

  private void downloadSelected(String type) {
    if (CallService.running || downloading) {
      message("Останови перевод перед установкой модели");
      return;
    }
    ModelRepository.Model selected =
        models.model(type.equals("asr") ? settings.asr() : settings.translation());
    downloading = true;
    canceled.set(false);
    downloads.execute(
        () -> {
          try {
            models.download(
                selected,
                canceled::get,
                value ->
                    runOnUiThread(
                        () -> {
                          if (!destroyed) modelStatus.setText(value);
                        }));
          } catch (Exception error) {
            runOnUiThread(() -> message(error.getMessage()));
          } finally {
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  downloading = false;
                  refresh();
                });
          }
        });
  }

  private void message(String text) {
    if (!isFinishing() && !isDestroyed())
      new AlertDialog.Builder(this)
          .setMessage(text == null ? "Ошибка" : text)
          .setPositiveButton("OK", null)
          .show();
  }

  private void refresh() {
    if (status == null) return;
    if (source != null)
      source.setSelection(Math.max(0, languages.sources().indexOf(settings.source())));
    if (target != null) {
      for (int i = 0; i < languages.targets().size(); i++)
        if (languages.targets().get(i).nllb.equals(settings.target())) target.setSelection(i);
    }
    status.setText(
        CallService.latestStatus
            + "\nShizuku: "
            + (dev.getjump.calltranslate.capture.ShizukuConnection.permitted()
                ? "доступ разрешен"
                : "не готов"));
    caption.render(CallService.history.snapshot());
    if (!downloading) {
      ModelRepository.Model recognition = models.model(settings.asr());
      ModelRepository.Model translator = models.model(settings.translation());
      modelStatus.setText(
          recognition.name
              + ": "
              + (models.installed(recognition) ? "установлена" : "нужно скачать")
              + "\n"
              + translator.name
              + ": "
              + (models.installed(translator) ? "установлена" : "нужно скачать"));
    }
    for (View view : new View[] {provider, translation, owner})
      if (view != null) view.setEnabled(!CallService.running && !downloading);
    boolean local = settings.provider().equals("local");
    for (View view : new View[] {asr, backend})
      if (view != null) view.setEnabled(local && !CallService.running && !downloading);
    if (cloud != null) cloud.active(CallService.running || downloading);
  }

  @Override
  public void onResume() {
    super.onResume();
    registerReceiver(receiver, new IntentFilter(CallService.UPDATE), Context.RECEIVER_NOT_EXPORTED);
    registered = true;
    refresh();
  }

  @Override
  public void onPause() {
    if (registered) {
      unregisterReceiver(receiver);
      registered = false;
    }
    super.onPause();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    canceled.set(true);
    downloads.shutdownNow();
    if (cloud != null) cloud.close();
    Shizuku.removeBinderReceivedListener(binderReceived);
    Shizuku.removeBinderDeadListener(binderDead);
    Shizuku.removeRequestPermissionResultListener(permissionResult);
    super.onDestroy();
  }
}
