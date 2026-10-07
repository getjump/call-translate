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
import android.text.InputFilter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.agent.ElevenAgentsApi;
import dev.getjump.calltranslate.cloud.agent.PrivateAgentDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class AgentActivity extends Activity {
  private static final int micPermission = 810;
  private static final int phonePermission = 811;
  private final ExecutorService requests = Executors.newSingleThreadExecutor();
  private SettingsStore settings;
  private LanguageCatalog languages;
  private LinearLayout content;
  private Spinner agentPicker;
  private Spinner speechPicker;
  private EditText brief;
  private EditText instruction;
  private TextView status;
  private TextView accountStatus;
  private TextView agentInfo;
  private CaptionFeed caption;
  private ScrollView page;
  private TextView transcriptTitle;
  private TextView transcriptInfo;
  private boolean showingTranscript;
  private Button refreshAgents;
  private Button createAgent;
  private Button mic;
  private Button sim;
  private Button stop;
  private Button instruct;
  private CheckBox mute;
  private List<ElevenAgentsApi.Agent> agents = List.of();
  private List<String> speechLanguages = List.of();
  private ElevenAgentsApi.AgentDetails details;
  private String requestedDetailsId = "";
  private String pendingStart = "";
  private boolean busy;
  private boolean registered;
  private boolean renderingMute;
  private boolean destroyed;
  private long generation;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refresh();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      settings = new SettingsStore(this);
      languages = new LanguageCatalog(this);
      if (state != null) pendingStart = state.getString("pendingStart", "");
      build();
      if (state != null) {
        brief.setText(state.getString("brief", brief.getText().toString()));
        instruction.setText(state.getString("instruction", ""));
      }
      refresh();
    } catch (Exception error) {
      TextView message = new TextView(this);
      message.setText("Не удалось открыть голосового агента. Вернись в настройки приложения.");
      setContentView(message);
    }
  }

  private void build() {
    page = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(22), dp(48), dp(22), dp(32));
    page.addView(content);
    setContentView(page);
    text("Голосовой агент", 29);
    text(
        "ElevenLabs говорит с собеседником по твоему поручению. Аудио и инструкции передаются в"
            + " облако.",
        15);
    status = text(VoiceAgentService.latestStatus, 16);
    status.setTextColor(0xFF0F766E);
    stop =
        button(
            "Остановить агента",
            () -> {
              if (VoiceAgentService.running)
                startService(
                    new Intent(this, VoiceAgentService.class).setAction(VoiceAgentService.STOP));
            });
    transcriptTitle = text("Транскрипт разговора", 20);
    transcriptInfo =
        text("Собеседник и агент. Прокрути вверх, чтобы перечитать прошлые реплики.", 14);
    caption = new CaptionFeed(this, 320, "Реплики собеседника и агента появятся здесь.");
    content.addView(caption);
    text("Агент из твоего ElevenLabs-аккаунта", 16);
    agentPicker = spinner(List.of());
    agentInfo = text("Нажми 'Обновить агентов'. Нужен ключ из облачных настроек приложения.", 14);
    refreshAgents = button("Обновить агентов", this::loadAgents);
    accountStatus =
        text("Нужны разрешения ElevenAgents Read для ключа и доступ к выбранному агенту.", 14);
    button("Открыть ElevenLabs Agents", () -> open("https://elevenlabs.io/app/agents"));
    createAgent = button("Создать приватного агента...", this::chooseVoice);
    text("Язык собеседника и речи агента", 16);
    speechLanguages = languages.sources().stream().filter(code -> !code.equals("auto")).toList();
    speechPicker = spinner(speechLanguages.stream().map(LanguageCatalog::sourceLabel).toList());
    String speech =
        getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentLanguage", "sr");
    speechPicker.setSelection(Math.max(0, speechLanguages.indexOf(speech)));
    selection(
        speechPicker,
        position -> {
          if (!VoiceAgentService.running)
            settings.set("voiceAgentLanguage", speechLanguages.get(position));
          refresh();
        });
    String ownerLanguage = languages.target(settings.target()).code;
    text(
        "Язык владельца: "
            + LanguageCatalog.sourceLabel(ownerLanguage)
            + ". Это язык твоих инструкций. Агент говорит на языке собеседника.",
        14);
    text("Дополнительные пожелания (необязательно)", 16);
    brief = new EditText(this);
    brief.setHint(
        "Агент сначала выяснит, кто звонит и зачем. Пожелания можно передать здесь или приватно"
            + " во время разговора.");
    brief.setMinLines(3);
    brief.setGravity(android.view.Gravity.TOP);
    brief.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8000)});
    brief.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentBrief", ""));
    content.addView(brief);
    mic = button("Микрофон: проверить", () -> start(VoiceAgentService.START_MIC));
    sim = button("SIM: входящие и исходящие", () -> start(VoiceAgentService.START_SIM));
    button(
        "Разрешить окно поверх звонка",
        () ->
            startActivity(
                new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
    text(
        "SIM требует Shizuku. Голос агента идет в звонок и на текущий аудиовыход телефона."
            + " Транскрипт появляется поверх звонилки, если разрешено окно поверх приложений."
            + " Экспериментальный режим: сначала проверь, слышит ли собеседник агента.",
        14);
    mute = new CheckBox(this);
    mute.setText("Выключить мой микрофон для собеседника (SIM)");
    mute.setOnCheckedChangeListener(
        (view, checked) -> {
          if (renderingMute || !VoiceAgentService.running || !VoiceAgentService.simMode) return;
          startService(
              new Intent(this, VoiceAgentService.class)
                  .setAction(VoiceAgentService.MUTE)
                  .putExtra("muted", checked));
        });
    content.addView(mute);
    text("Приватные поручения пока текстом. Голосовой канал для тебя еще не подключен.", 14);
    text("Инструкция агенту во время разговора", 16);
    instruction = new EditText(this);
    instruction.setHint("Приватно: попроси другое время, уточни цену...");
    instruction.setMinLines(2);
    instruction.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
    content.addView(instruction);
    instruct =
        button(
            "Передать приватную инструкцию",
            () -> {
              String value = instruction.getText().toString().trim();
              if (value.isEmpty()) {
                instruction.setError("Введи инструкцию");
                return;
              }
              if (!VoiceAgentService.connected) {
                status.setText("Сначала дождись подключения агента");
                return;
              }
              startService(
                  new Intent(this, VoiceAgentService.class)
                      .setAction(VoiceAgentService.INSTRUCT)
                      .putExtra("instruction", value));
              instruction.setText("");
            });
    button("Вернуться к переводу звонка", this::finish);
  }

  private void loadAgents() {
    if (busy || VoiceAgentService.running) return;
    busy = true;
    long request = ++generation;
    accountStatus.setText("Загрузка агентов...");
    refresh();
    requests.execute(
        () -> {
          try {
            List<ElevenAgentsApi.Agent> available =
                ElevenAgentsApi.listOwned(new CredentialStore(this).load());
            post(
                request,
                () -> {
                  busy = false;
                  renderAgents(available);
                  accountStatus.setText(
                      available.isEmpty()
                          ? "Своих активных агентов нет. Создай приватного агента здесь или в"
                              + " ElevenLabs, затем обнови список."
                          : "Агенты загружены: " + available.size());
                  refresh();
                });
          } catch (Exception error) {
            post(
                request,
                () -> {
                  busy = false;
                  accountStatus.setText(error.getMessage());
                  refresh();
                });
          }
        });
  }

  private void renderAgents(List<ElevenAgentsApi.Agent> values) {
    agents = values;
    details = null;
    requestedDetailsId = "";
    agentPicker.setOnItemSelectedListener(null);
    agentPicker.setAdapter(adapter(values));
    String saved = getSharedPreferences("settings", MODE_PRIVATE).getString("voiceAgentId", "");
    for (int i = 0; i < values.size(); i++)
      if (values.get(i).id().equals(saved)) agentPicker.setSelection(i);
    selection(
        agentPicker,
        position -> {
          if (position < agents.size()) loadDetails(agents.get(position));
        });
    if (!values.isEmpty())
      loadDetails(values.get(Math.max(0, agentPicker.getSelectedItemPosition())));
    else agentInfo.setText("Выбери или создай голосового агента");
  }

  private void loadDetails(ElevenAgentsApi.Agent agent) {
    if (agent.id().equals(requestedDetailsId) || VoiceAgentService.running) return;
    requestedDetailsId = agent.id();
    settings.set("voiceAgentId", agent.id());
    details = null;
    busy = true;
    long request = ++generation;
    agentInfo.setText("Проверка голосовой конфигурации...");
    refresh();
    requests.execute(
        () -> {
          try {
            var selected = ElevenAgentsApi.getAgent(new CredentialStore(this).load(), agent.id());
            post(
                request,
                () -> {
                  details = selected;
                  busy = false;
                  agentInfo.setText(
                      "Язык в ElevenLabs: "
                          + LanguageCatalog.sourceLabel(selected.configuredLanguage())
                          + (selected.textOnly() || !selected.clientEvents().contains("audio")
                              ? " Включи голос и событие audio в настройках агента."
                              : " Язык в приложении должен совпадать. Настройки агента в ElevenLabs"
                                  + " определяют его поведение."));
                  refresh();
                });
          } catch (Exception error) {
            post(
                request,
                () -> {
                  busy = false;
                  agentInfo.setText(error.getMessage());
                  refresh();
                });
          }
        });
  }

  private void start(String action) {
    if (busy || VoiceAgentService.running) return;
    if (details == null || details.textOnly() || !details.clientEvents().contains("audio")) {
      accountStatus.setText("Обнови агентов и выбери агента с голосом и событием audio");
      return;
    }
    String speech = speechLanguages.get(speechPicker.getSelectedItemPosition());
    if (!details.configuredLanguage().equals(speech)) {
      accountStatus.setText(
          "Язык агента в ElevenLabs должен совпадать с языком собеседника. Сейчас: "
              + LanguageCatalog.sourceLabel(details.configuredLanguage()));
      return;
    }
    String task = brief.getText().toString().trim();
    settings.set("voiceAgentBrief", task);
    settings.set("voiceAgentLanguage", speech);
    pendingStart = action;
    String permission =
        action.equals(VoiceAgentService.START_MIC)
            ? Manifest.permission.RECORD_AUDIO
            : Manifest.permission.READ_PHONE_STATE;
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {permission},
          action.equals(VoiceAgentService.START_MIC) ? micPermission : phonePermission);
      return;
    }
    pendingStart = "";
    stopService(new Intent(this, CallService.class));
    try {
      startForegroundService(new Intent(this, VoiceAgentService.class).setAction(action));
      status.setText("Запуск агента...");
    } catch (RuntimeException error) {
      status.setText("Не удалось запустить агента. Проверь разрешения и попробуй снова.");
    }
  }

  private void chooseVoice() {
    if (busy || VoiceAgentService.running) return;
    busy = true;
    long request = ++generation;
    accountStatus.setText("Загрузка голосов по именам...");
    refresh();
    requests.execute(
        () -> {
          try {
            var voices = ElevenAgentsApi.listVoices(new CredentialStore(this).load());
            post(
                request,
                () -> {
                  busy = false;
                  refresh();
                  if (voices.isEmpty()) {
                    accountStatus.setText(
                        "В аккаунте нет доступных голосов. Добавь голос в ElevenLabs и повтори.");
                    return;
                  }
                  voiceDialog(voices);
                });
          } catch (Exception error) {
            post(
                request,
                () -> {
                  busy = false;
                  accountStatus.setText(error.getMessage());
                  refresh();
                });
          }
        });
  }

  private void voiceDialog(List<ElevenAgentsApi.Voice> voices) {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), dp(12), dp(20), dp(12));
    EditText name = new EditText(this);
    name.setSingleLine(true);
    name.setText("Call Translate");
    name.setHint("Название агента");
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(128)});
    form.addView(name);
    TextView label = new TextView(this);
    label.setText("Голос из твоего аккаунта. Выбери подходящий голос по имени.");
    form.addView(label);
    Spinner voice = new Spinner(this);
    voice.setAdapter(adapter(voices));
    form.addView(voice);
    var dialog =
        new AlertDialog.Builder(this)
            .setTitle("Новый приватный агент")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить настройки", null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      try {
                        var selected = voices.get(voice.getSelectedItemPosition());
                        String language =
                            speechLanguages.get(speechPicker.getSelectedItemPosition());
                        var draft =
                            PrivateAgentDraft.build(name.getText().toString(), language, selected);
                        dialog.dismiss();
                        reviewDraft(draft, selected, language);
                      } catch (Exception error) {
                        name.setError(error.getMessage());
                      }
                    }));
    dialog.show();
  }

  private void reviewDraft(JSONObject draft, ElevenAgentsApi.Voice voice, String language)
      throws Exception {
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(20), dp(12), dp(20), dp(12));
    TextView summary = new TextView(this);
    summary.setText(
        "Будет создан новый агент в твоем ElevenLabs-аккаунте.\n\nНазвание: "
            + draft.getString("name")
            + "\nГолос: "
            + voice.name()
            + "\nЯзык речи: "
            + LanguageCatalog.sourceLabel(language)
            + "\n"
            + "Модель: Eleven v3 Conversational\n"
            + "Доступ: приватный, только по подписанной ссылке\n"
            + "Лимит разговора: 10 минут\n"
            + "Внешние инструменты: не добавляются\n"
            + "Первое сообщение: ждать реплику собеседника\n"
            + "Сначала агент выясняет, кто звонит и зачем. Пожелания необязательны, их можно"
            + " передать во время разговора. Создание не запускает разговор."
            + " Использование агента расходует кредиты ElevenLabs.");
    panel.addView(summary);
    TextView json = new TextView(this);
    json.setText(draft.toString(2));
    json.setTextIsSelectable(true);
    json.setVisibility(View.GONE);
    Button show = new Button(this);
    show.setText("Посмотреть конфигурацию JSON");
    show.setOnClickListener(
        view -> {
          json.setVisibility(json.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
        });
    panel.addView(show);
    panel.addView(json);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(panel);
    new AlertDialog.Builder(this)
        .setTitle("Проверка перед созданием")
        .setView(scroll)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Создать приватного агента", (dialog, which) -> createReviewed(draft))
        .show();
  }

  private void createReviewed(JSONObject draft) {
    if (busy || VoiceAgentService.running) return;
    busy = true;
    long request = ++generation;
    accountStatus.setText("Создание приватного агента...");
    refresh();
    requests.execute(
        () -> {
          try {
            String id = ElevenAgentsApi.createAgent(new CredentialStore(this).load(), draft);
            settings.set("voiceAgentId", id);
            post(
                request,
                () -> {
                  busy = false;
                  accountStatus.setText("Приватный агент создан. Загружаю список...");
                  loadAgents();
                });
          } catch (Exception error) {
            post(
                request,
                () -> {
                  busy = false;
                  accountStatus.setText(error.getMessage());
                  refresh();
                });
          }
        });
  }

  private void refresh() {
    if (destroyed || mic == null) return;
    boolean active = VoiceAgentService.running;
    boolean editable = !active && !busy;
    status.setText(VoiceAgentService.latestStatus);
    agentPicker.setEnabled(editable && !agents.isEmpty());
    speechPicker.setEnabled(editable);
    brief.setEnabled(editable);
    refreshAgents.setEnabled(editable);
    createAgent.setEnabled(editable);
    boolean configured =
        details != null && !details.textOnly() && details.clientEvents().contains("audio");
    mic.setEnabled(editable && configured);
    sim.setEnabled(editable && configured);
    stop.setEnabled(active);
    stop.setVisibility(active ? View.VISIBLE : View.GONE);
    boolean transcriptVisible = active || !VoiceAgentService.history.snapshot().isEmpty();
    transcriptTitle.setVisibility(transcriptVisible ? View.VISIBLE : View.GONE);
    transcriptInfo.setVisibility(transcriptVisible ? View.VISIBLE : View.GONE);
    caption.setVisibility(transcriptVisible ? View.VISIBLE : View.GONE);
    if (transcriptVisible && !showingTranscript) page.post(() -> page.scrollTo(0, 0));
    showingTranscript = transcriptVisible;
    instruction.setEnabled(active && VoiceAgentService.connected);
    instruct.setEnabled(active && VoiceAgentService.connected);
    mute.setEnabled(active && VoiceAgentService.simMode);
    if (mute.isChecked() != VoiceAgentService.microphoneMuted) {
      renderingMute = true;
      mute.setChecked(VoiceAgentService.microphoneMuted);
      renderingMute = false;
    }
    caption.render(
        VoiceAgentService.history.snapshot().stream()
            .map(
                entry ->
                    entry.translated() == null
                        ? new dev.getjump.calltranslate.core.CaptionHistory.Entry(
                            entry.id(),
                            entry.speaker(),
                            entry.original(),
                            "",
                            entry.language(),
                            entry.recognitionMilliseconds(),
                            entry.totalMilliseconds(),
                            entry.draft())
                        : entry)
            .toList());
  }

  private void post(long request, Runnable action) {
    runOnUiThread(
        () -> {
          if (!destroyed && request == generation) action.run();
        });
  }

  private void open(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException error) {
      accountStatus.setText("Открой ElevenLabs Agents в браузере");
    }
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(0xFF172554);
    view.setPadding(0, dp(7), 0, dp(7));
    content.addView(view);
    return view;
  }

  private Button button(String value, Runnable action) {
    Button view = new Button(this);
    view.setText(value);
    view.setOnClickListener(ignored -> action.run());
    content.addView(view);
    return view;
  }

  private Spinner spinner(List<?> values) {
    Spinner view = new Spinner(this);
    view.setAdapter(adapter(values));
    content.addView(view);
    return view;
  }

  private ArrayAdapter<Object> adapter(List<?> values) {
    return new ArrayAdapter<>(
        this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(values));
  }

  private void selection(Spinner view, java.util.function.IntConsumer selected) {
    view.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
            selected.accept(position);
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (status == null) return;
    registerReceiver(
        receiver, new IntentFilter(VoiceAgentService.UPDATE), Context.RECEIVER_NOT_EXPORTED);
    registered = true;
    refresh();
  }

  @Override
  protected void onPause() {
    if (registered) {
      unregisterReceiver(receiver);
      registered = false;
    }
    if (brief != null && !VoiceAgentService.running)
      settings.set("voiceAgentBrief", brief.getText().toString());
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("pendingStart", pendingStart);
    if (brief != null) state.putString("brief", brief.getText().toString());
    if (instruction != null) state.putString("instruction", instruction.getText().toString());
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code != micPermission && code != phonePermission) return;
    String action = pendingStart;
    pendingStart = "";
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && !action.isEmpty())
      start(action);
    else if (status != null) status.setText("Разрешение не выдано. Агент не запущен.");
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    generation++;
    requests.shutdownNow();
    super.onDestroy();
  }
}
