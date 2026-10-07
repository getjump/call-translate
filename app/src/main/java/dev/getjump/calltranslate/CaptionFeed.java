package dev.getjump.calltranslate;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CaptionFeed extends LinearLayout {
  private record Bubble(
      LinearLayout panel, TextView label, TextView original, TextView translated) {}

  private final ScrollView scroll;
  private final LinearLayout rows;
  private final Button newest;
  private final TextView placeholder;
  private final Map<Long, Bubble> bubbles = new LinkedHashMap<>();
  private final Map<Long, CaptionHistory.Entry> rendered = new LinkedHashMap<>();
  private boolean touching;
  private long touchGeneration;

  public CaptionFeed(Context context, int heightDp) {
    this(context, heightDp, "Здесь появится разговор. Оригинал - сразу, перевод - следом.");
  }

  public CaptionFeed(Context context, int heightDp, String emptyText) {
    super(context);
    setOrientation(VERTICAL);
    scroll = new ScrollView(context);
    scroll.setId(R.id.caption_scroll);
    scroll.setFillViewport(true);
    rows = new LinearLayout(context);
    rows.setOrientation(VERTICAL);
    rows.setPadding(dp(4), dp(8), dp(4), dp(8));
    scroll.addView(rows);
    addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp)));
    placeholder = text(17, 0xFF475569);
    placeholder.setText(emptyText);
    rows.addView(placeholder);
    newest = new Button(context);
    newest.setId(R.id.caption_latest);
    newest.setText("К последним репликам");
    newest.setVisibility(GONE);
    newest.setOnClickListener(view -> scroll.fullScroll(View.FOCUS_DOWN));
    addView(newest);
    scroll.setOnScrollChangeListener(
        (view, x, y, oldX, oldY) -> {
          newest.setVisibility(nearBottom() ? GONE : VISIBLE);
        });
    scroll.setOnTouchListener(
        (view, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchGeneration++;
          touching =
              event.getActionMasked() != MotionEvent.ACTION_UP
                  && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
          if (view.getParent() != null)
            view.getParent().requestDisallowInterceptTouchEvent(touching);
          return false;
        });
  }

  private boolean nearBottom() {
    return rows.getHeight() - scroll.getHeight() - scroll.getScrollY() <= dp(32);
  }

  public void render(List<CaptionHistory.Entry> entries) {
    boolean follow = nearBottom() && !touching;
    var incoming =
        entries.stream().map(CaptionHistory.Entry::id).collect(java.util.stream.Collectors.toSet());
    boolean reset = !incoming.containsAll(bubbles.keySet());
    if (reset) {
      rows.removeAllViews();
      bubbles.clear();
      rendered.clear();
      follow = true;
    }
    if (entries.isEmpty()) {
      if (placeholder.getParent() == null) rows.addView(placeholder);
      newest.setVisibility(GONE);
      return;
    }
    rows.removeView(placeholder);
    boolean added = false;
    for (CaptionHistory.Entry entry : entries) {
      if (entry.equals(rendered.get(entry.id()))) continue;
      Bubble bubble = bubbles.get(entry.id());
      if (bubble == null) {
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackgroundColor(entry.speaker().equals("Я") ? 0xFFE0F2FE : Color.WHITE);
        TextView label = text(12, 0xFF0F766E);
        TextView original = text(19, 0xFF172554);
        TextView translated = text(18, 0xFF475569);
        original.setTextIsSelectable(true);
        translated.setTextIsSelectable(true);
        panel.addView(label);
        panel.addView(original);
        panel.addView(translated);
        LayoutParams layout =
            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = dp(8);
        rows.addView(panel, layout);
        bubble = new Bubble(panel, label, original, translated);
        bubbles.put(entry.id(), bubble);
        added = true;
      }
      bubble
          .label()
          .setText(
              entry.speaker()
                  + (entry.language() == null || entry.language().isBlank()
                      ? ""
                      : " [" + entry.language() + "]")
                  + (entry.draft() ? " | Черновик" : ""));
      bubble.original().setText(entry.original());
      bubble
          .translated()
          .setText(entry.translated() == null ? "Переводится..." : entry.translated());
      bubble
          .translated()
          .setVisibility(
              entry.translated() != null && entry.translated().isBlank() ? GONE : VISIBLE);
      rendered.put(entry.id(), entry);
    }
    if (follow) {
      long generation = touchGeneration;
      scroll.post(
          () -> {
            if (!touching && generation == touchGeneration) scroll.fullScroll(View.FOCUS_DOWN);
          });
    } else if (added) {
      newest.setVisibility(VISIBLE);
    }
  }

  private TextView text(int size, int color) {
    TextView view = new TextView(getContext());
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, dp(3), 0, dp(3));
    return view;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
