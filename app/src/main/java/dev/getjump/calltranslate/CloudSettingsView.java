package dev.getjump.calltranslate;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.ElevenLabsApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CloudSettingsView extends LinearLayout implements AutoCloseable {
  interface ModelVerifier {
    List<ElevenLabsApi.Model> verify(String key) throws Exception;
  }

  private final SettingsStore settings;
  private final CredentialStore credentials;
  private final EditText key;
  private final TextView status;
  private final Spinner models;
  private final Button verify;
  private final Button remove;
  private final ExecutorService requests;
  private final ModelVerifier verifier;
  private final AtomicLong generation = new AtomicLong();
  private final Object lifecycle = new Object();
  private volatile boolean closed;
  private boolean working;
  private boolean active;
  private boolean loaded;

  public CloudSettingsView(Context context, SettingsStore settings) {
    this(
        context,
        settings,
        new CredentialStore(context),
        ElevenLabsApi::verifyAndList,
        Executors.newSingleThreadExecutor());
  }

  CloudSettingsView(
      Context context,
      SettingsStore settings,
      CredentialStore credentials,
      ModelVerifier verifier,
      ExecutorService requests) {
    super(context);
    this.settings = settings;
    this.credentials = credentials;
    this.verifier = verifier;
    this.requests = requests;
    setOrientation(VERTICAL);
    text(
        "ElevenLabs: распознавание в облаке, перевод NLLB на телефоне. Нужен интернет;"
            + " аудио звонка отправляется в ElevenLabs и использует API-квоту аккаунта.");
    key = new EditText(context);
    key.setId(R.id.eleven_api_key);
    key.setHint("API key ElevenLabs");
    key.setSingleLine(true);
    key.setSaveEnabled(false);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    addView(key);
    status = text("Добавь ключ с доступом к Speech to Text, User: Read и Models: Read.");
    verify = new Button(context);
    verify.setText("Проверить ключ / обновить модели");
    verify.setOnClickListener(view -> refreshModels());
    addView(verify);
    models = new Spinner(context);
    models.setId(R.id.eleven_model);
    addView(models);
    remove = new Button(context);
    remove.setText("Удалить ключ");
    remove.setOnClickListener(view -> clearKey());
    addView(remove);
    try {
      render(new JSONArray(settings.cloudModels()));
    } catch (Exception error) {
      render(new JSONArray());
    }
    controls();
  }

  private TextView text(String value) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(13);
    view.setTextColor(0xFF475569);
    addView(view);
    return view;
  }

  public void selected(boolean selected) {
    setVisibility(selected ? View.VISIBLE : View.GONE);
    if (selected && !loaded) {
      loaded = true;
      requests.execute(
          () -> {
            try {
              boolean saved = !credentials.load().isBlank();
              post(
                  () -> {
                    if (!closed && saved) refreshModels();
                  });
            } catch (Exception error) {
              post(
                  () -> {
                    if (!closed) status.setText(error.getMessage());
                  });
            }
          });
    }
  }

  public void active(boolean active) {
    synchronized (lifecycle) {
      if (active && !this.active && working) {
        generation.incrementAndGet();
        working = false;
        status.setText("Проверка ключа прервана: перевод запущен");
      }
      this.active = active;
    }
    controls();
  }

  public boolean busy() {
    return working;
  }

  private void controls() {
    boolean enabled = !active && !working && !closed;
    key.setEnabled(enabled);
    verify.setEnabled(enabled);
    remove.setEnabled(enabled);
    models.setEnabled(enabled && models.getAdapter() != null && models.getAdapter().getCount() > 0);
  }

  private void refreshModels() {
    if (closed || active || working) return;
    String entered = key.getText().toString().trim();
    long request = generation.incrementAndGet();
    working = true;
    controls();
    status.setText("Проверка ключа и загрузка моделей...");
    requests.execute(
        () -> {
          try {
            String value = entered.isBlank() ? credentials.load() : entered;
            List<ElevenLabsApi.Model> available = verifier.verify(value);
            JSONArray cache = new JSONArray();
            for (ElevenLabsApi.Model model : available)
              cache.put(
                  new JSONObject()
                      .put("id", model.id())
                      .put("name", model.name())
                      .put("realtime", model.realtime()));
            synchronized (lifecycle) {
              if (closed || active || CallService.running || request != generation.get()) return;
              credentials.save(value);
              settings.set("cloudModels", cache.toString());
            }
            post(
                () -> {
                  if (closed || request != generation.get()) return;
                  key.setText("");
                  working = false;
                  render(cache);
                  status.setText(
                      "Ключ проверен и сохранен. Модели обновлены."
                          + " В переключателе только модели для realtime.");
                  controls();
                });
          } catch (Exception error) {
            post(
                () -> {
                  if (closed || request != generation.get()) return;
                  working = false;
                  status.setText("ElevenLabs: " + error.getMessage());
                  controls();
                });
          }
        });
  }

  private void render(JSONArray entries) {
    List<ElevenLabsApi.Model> choices = new ArrayList<>();
    List<String> batch = new ArrayList<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject item = entries.optJSONObject(i);
      if (item == null) continue;
      if (item.optBoolean("realtime"))
        choices.add(new ElevenLabsApi.Model(item.optString("id"), item.optString("name"), true));
      else batch.add(item.optString("name"));
    }
    models.setOnItemSelectedListener(null);
    models.setAdapter(
        new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, choices));
    for (int i = 0; i < choices.size(); i++)
      if (choices.get(i).id().equals(settings.cloudModel())) models.setSelection(i);
    models.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(
              android.widget.AdapterView<?> parent, View view, int position, long id) {
            settings.set("cloudModel", choices.get(position).id());
          }

          public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    if (!batch.isEmpty())
      status.setText("Доступны также пакетные ASR: " + String.join(", ", batch));
  }

  private void clearKey() {
    if (closed || active) return;
    long request = generation.incrementAndGet();
    working = true;
    controls();
    requests.execute(
        () -> {
          try {
            synchronized (lifecycle) {
              if (closed || active || CallService.running || request != generation.get()) return;
              credentials.clear();
              settings.set("cloudModels", "[]");
            }
            post(
                () -> {
                  if (closed || request != generation.get()) return;
                  key.setText("");
                  working = false;
                  render(new JSONArray());
                  status.setText("Ключ удален");
                  controls();
                });
          } catch (Exception error) {
            post(
                () -> {
                  if (closed || request != generation.get()) return;
                  working = false;
                  status.setText(error.getMessage());
                  controls();
                });
          }
        });
  }

  @Override
  public void close() {
    synchronized (lifecycle) {
      closed = true;
      generation.incrementAndGet();
    }
    requests.shutdownNow();
  }
}
