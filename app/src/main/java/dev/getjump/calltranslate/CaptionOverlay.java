package dev.getjump.calltranslate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.util.List;

public final class CaptionOverlay implements AutoCloseable {
  private final WindowManager windows;
  private LinearLayout panel;
  private TextView status;
  private CaptionFeed feed;
  private final Context context;

  public CaptionOverlay(Context context) {
    this.context = context;
    windows = context.getSystemService(WindowManager.class);
  }

  public void showAgent(String initialStatus, Runnable open, Runnable stop) {
    if (!begin(initialStatus, "Реплики собеседника и агента появятся здесь.")) return;
    LinearLayout buttons = new LinearLayout(context);
    Button openButton = new Button(context);
    openButton.setText("Открыть диалог");
    openButton.setOnClickListener(view -> open.run());
    Button stopButton = new Button(context);
    stopButton.setText("Остановить агента");
    stopButton.setOnClickListener(view -> stop.run());
    buttons.addView(
        openButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    buttons.addView(
        stopButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    panel.addView(buttons);
    attach("Call Translate - Голосовой агент");
  }

  public void show(
      LanguageCatalog languages,
      Runnable pause,
      Runnable stop,
      java.util.function.Consumer<String> changed) {
    if (!begin(
        CallService.latestStatus, "Здесь появится разговор. Оригинал - сразу, перевод - следом."))
      return;
    Spinner languagesPicker = new Spinner(context);
    List<String> codes = languages.sources();
    languagesPicker.setAdapter(
        new ArrayAdapter<>(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            codes.stream()
                .map(LanguageCatalog::sourceLabel)
                .collect(java.util.stream.Collectors.toList())));
    languagesPicker.setSelection(Math.max(0, codes.indexOf(new SettingsStore(context).source())));
    languagesPicker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(
              AdapterView<?> parent, android.view.View view, int position, long id) {
            String value = codes.get(position);
            if (!value.equals(new SettingsStore(context).source())) changed.accept(value);
          }

          @Override
          public void onNothingSelected(AdapterView<?> parent) {}
        });
    panel.addView(languagesPicker);
    LinearLayout buttons = new LinearLayout(context);
    Button pauseButton = new Button(context);
    pauseButton.setText("Пауза / продолжить");
    pauseButton.setOnClickListener(view -> pause.run());
    Button stopButton = new Button(context);
    stopButton.setText("Закрыть");
    stopButton.setOnClickListener(view -> stop.run());
    buttons.addView(pauseButton);
    buttons.addView(stopButton);
    panel.addView(buttons);
    attach("Call Translate");
  }

  private boolean begin(String initialStatus, String emptyText) {
    if (panel != null || !Settings.canDrawOverlays(context)) return false;
    panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(Color.rgb(241, 245, 249));
    panel.setPadding(dp(12), dp(8), dp(12), dp(8));
    status = text(12, 0xFF0F766E);
    status.setText(initialStatus);
    feed = new CaptionFeed(context, 210, emptyText);
    panel.addView(status);
    panel.addView(feed);
    return true;
  }

  private void attach(String title) {
    WindowManager.LayoutParams layout =
        new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP;
    layout.y = dp(32);
    layout.setTitle(title);
    try {
      windows.addView(panel, layout);
    } catch (RuntimeException error) {
      android.util.Log.e("CallTranslate", "Overlay unavailable", error);
      panel = null;
      feed = null;
      status = null;
    }
  }

  private TextView text(int size, int color) {
    TextView view = new TextView(context);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  public void status(String text) {
    if (status != null) status.setText(text);
  }

  public void render(List<CaptionHistory.Entry> entries) {
    if (feed != null) feed.render(entries);
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  @Override
  public void close() {
    if (panel != null) {
      windows.removeView(panel);
      panel = null;
    }
    feed = null;
    status = null;
  }
}
