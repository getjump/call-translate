package dev.getjump.calltranslate.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SerbianTextTest {
  @Test
  public void digraphsAndSerbianLettersAreConvertedWithoutChangingNumbers() {
    assertEquals(
        "Љиљана Његош Ђорђе, 15:30", SerbianText.toCyrillic("Ljiljana Njegoš Đorđe, 15:30"));
  }

  @Test
  public void cyrillicAndPunctuationStayIntact() {
    assertEquals("Не долазите у 15 часова.", SerbianText.toCyrillic("Не долазите у 15 часова."));
  }
}
