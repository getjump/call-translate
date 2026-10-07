package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LanguageMappingTest {
  @Test
  public void whisperLanguagesResolveToCorrectTranslationScripts() throws Exception {
    LanguageCatalog catalog =
        new LanguageCatalog(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertEquals(256164, catalog.source("sr").token);
    assertEquals("ind_Latn", catalog.source("id").nllb);
    assertEquals("slv_Latn", catalog.source("sl").nllb);
    assertEquals("mal_Mlym", catalog.source("ml").nllb);
    assertEquals("azj_Latn", catalog.source("az").nllb);
    assertEquals("pes_Arab", catalog.source("fa").nllb);
    assertEquals("zsm_Latn", catalog.source("ms").nllb);
    assertEquals("rus_Cyrl", catalog.source("ru").nllb);
    assertNull(catalog.sourceOrNull("la"));
    assertEquals("yue_Hant", catalog.source("yue").nllb);
    for (String code : catalog.sources()) {
      if (java.util.Set.of("auto", "br", "haw", "la").contains(code)) continue;
      assertNotNull("Missing translation mapping for " + code, catalog.sourceOrNull(code));
    }
  }
}
