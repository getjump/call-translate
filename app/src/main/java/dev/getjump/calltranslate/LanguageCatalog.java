package dev.getjump.calltranslate;

import android.content.Context;
import dev.getjump.calltranslate.ml.NativeEngine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LanguageCatalog {
  public static final class Language {
    public final String code;
    public final String nllb;
    public final int token;

    Language(String code, String nllb, int token) {
      this.code = code;
      this.nllb = nllb;
      this.token = token;
    }

    @Override
    public String toString() {
      String display =
          Locale.forLanguageTag(code.replace('_', '-')).getDisplayName(Locale.forLanguageTag("ru"));
      return display + " [" + nllb + "]";
    }
  }

  private final List<Language> targets = new ArrayList<>();
  private final Map<String, Language> byCode = new HashMap<>();
  private final Map<String, Language> byNllb = new HashMap<>();
  private final List<String> sources = new ArrayList<>();

  public LanguageCatalog(Context context) throws Exception {
    try (var input = context.getAssets().open("languages.json")) {
      JSONArray array = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.getJSONObject(i);
        Language language =
            new Language(item.getString("code"), item.getString("nllb"), item.getInt("token"));
        if (!byNllb.containsKey(language.nllb)) targets.add(language);
        byCode.put(language.code, language);
        byNllb.put(language.nllb, language);
      }
    }
    for (String code : NativeEngine.asrLanguages()) {
      sources.add(code);
      try {
        String iso3 = Locale.forLanguageTag(code).getISO3Language();
        for (Language language : targets)
          if (language.nllb.startsWith(iso3 + "_")) byCode.putIfAbsent(code, language);
      } catch (java.util.MissingResourceException ignored) {
      }
    }
    sources.sort(Comparator.comparing(LanguageCatalog::sourceLabel));
    sources.add(0, "auto");
    targets.sort(Comparator.comparing(Language::toString));
    alias("sr", "srp_Cyrl");
    alias("zh", "zho_Hans");
    alias("he", "heb_Hebr");
    alias("jw", "jav_Latn");
    alias("tl", "tgl_Latn");
    alias("no", "nob_Latn");
    alias("ar", "arb_Arab");
    alias("az", "azj_Latn");
    alias("lv", "lvs_Latn");
    alias("mg", "plt_Latn");
    alias("mn", "khk_Cyrl");
    alias("ms", "zsm_Latn");
    alias("ne", "npi_Deva");
    alias("ps", "pbt_Arab");
    alias("sq", "als_Latn");
    alias("sw", "swh_Latn");
    alias("uz", "uzn_Latn");
    alias("yi", "ydd_Hebr");
    alias("yue", "yue_Hant");
    alias("cy", "cym_Latn");
    alias("ht", "hat_Latn");
    alias("hy", "hye_Armn");
    alias("id", "ind_Latn");
    alias("is", "isl_Latn");
    alias("mi", "mri_Latn");
    alias("ml", "mal_Mlym");
    alias("mr", "mar_Deva");
    alias("mt", "mlt_Latn");
    alias("my", "mya_Mymr");
    alias("nn", "nno_Latn");
    alias("oc", "oci_Latn");
    alias("pa", "pan_Guru");
    alias("sa", "san_Deva");
    alias("sd", "snd_Arab");
    alias("si", "sin_Sinh");
    alias("sl", "slv_Latn");
    alias("sn", "sna_Latn");
    alias("so", "som_Latn");
    alias("su", "sun_Latn");
    alias("te", "tel_Telu");
    alias("tg", "tgk_Cyrl");
    alias("tk", "tuk_Latn");
    alias("tt", "tat_Cyrl");
    alias("yo", "yor_Latn");
  }

  private void alias(String code, String nllb) {
    if (byNllb.containsKey(nllb)) byCode.put(code, byNllb.get(nllb));
  }

  public List<String> sources() {
    return List.copyOf(sources);
  }

  public List<Language> targets() {
    return List.copyOf(targets);
  }

  public Language sourceOrNull(String code) {
    return byCode.get(code);
  }

  public Language source(String code) {
    Language value = byCode.get(code);
    if (value == null)
      throw new IllegalArgumentException(
          "Модель перевода не поддерживает распознанный язык: " + code);
    return value;
  }

  public Language target(String code) {
    Language value = byNllb.get(code);
    if (value == null) throw new IllegalArgumentException("Неизвестный выходной язык: " + code);
    return value;
  }

  public static String sourceLabel(String code) {
    return code.equals("auto")
        ? "Auto - определить язык"
        : Locale.forLanguageTag(code.replace('_', '-')).getDisplayName(Locale.forLanguageTag("ru"))
            + " ["
            + code
            + "]";
  }
}
