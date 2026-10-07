package dev.getjump.calltranslate;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
  private final SharedPreferences data;

  public SettingsStore(Context context) {
    data = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
  }

  public String source() {
    return data.getString("source", "auto");
  }

  public String target() {
    return data.getString("target", "rus_Cyrl");
  }

  public String asr() {
    return data.getString("asr", "whisper-base");
  }

  public String translation() {
    return data.getString("translation", "nllb-600m");
  }

  public String backend() {
    return data.getString("backend", "CPU");
  }

  public String provider() {
    return data.getString("provider", "local");
  }

  public String cloudModel() {
    return data.getString("cloudModel", "scribe_v2_realtime");
  }

  public String cloudModels() {
    return data.getString("cloudModels", "[]");
  }

  public boolean owner() {
    return data.getBoolean("owner", false);
  }

  public void set(String name, String value) {
    data.edit().putString(name, value).apply();
  }

  public void owner(boolean value) {
    data.edit().putBoolean("owner", value).apply();
  }
}
