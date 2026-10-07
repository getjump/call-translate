package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConversationTest {
  @Test
  public void originalAppearsBeforeTranslationAndUpdateKeepsConversationOrder() {
    CaptionHistory history = new CaptionHistory();
    history.update(new CaptionHistory.Entry(1, "Собеседник", "Dobar dan", null, "sr", 900, 900));
    assertEquals("Dobar dan", history.snapshot().get(0).original());
    assertNull(history.snapshot().get(0).translated());
    history.update(new CaptionHistory.Entry(2, "Я", "Здравствуйте", null, "ru", 800, 800));
    history.update(
        new CaptionHistory.Entry(1, "Собеседник", "Dobar dan", "Добрый день", "sr", 900, 1500));
    assertEquals(2, history.snapshot().size());
    assertEquals(1, history.snapshot().get(0).id());
    assertEquals("Добрый день", history.snapshot().get(0).translated());
    assertEquals(2, history.snapshot().get(1).id());
    var previous = history.snapshot();
    history.clear();
    history.update(new CaptionHistory.Entry(3, "Собеседник", "Hello", null, "en", 700, 700));
    assertEquals(2, previous.size());
    assertEquals(1, history.snapshot().size());
    assertEquals(3, history.snapshot().get(0).id());
  }

  @Test
  public void interruptedTranslationDoesNotLeavePermanentLoadingPlaceholder() {
    CaptionHistory history = new CaptionHistory();
    history.update(new CaptionHistory.Entry(1, "Собеседник", "Dobar dan", null, "sr", 900, 900));
    history.finishPending("Перевод прерван");
    assertEquals("Dobar dan", history.snapshot().get(0).original());
    assertEquals("Перевод прерван", history.snapshot().get(0).translated());
  }

  @Test
  public void hangupKeepsAlreadyDisplayedDraftTranslation() {
    CaptionHistory history = new CaptionHistory();
    var draft =
        new CaptionHistory.Entry(
            1, "Собеседник", "Za informacije o", "Для информации", "sr", 100, 200, true);
    history.update(draft);
    history.finishPending("Перевод прерван");
    assertEquals(draft, history.snapshot().get(0));
  }

  @Test
  public void automaticLanguageNeedsRepeatedEvidenceAndStaysFixedPerSpeaker() {
    ConversationLanguage languages = new ConversationLanguage();
    assertEquals("auto", languages.input("Собеседник", "auto"));
    languages.observe("Собеседник", "sr");
    assertEquals("auto", languages.input("Собеседник", "auto"));
    languages.observe("Собеседник", "hr");
    languages.observe("Собеседник", "sr");
    assertEquals("sr", languages.input("Собеседник", "auto"));
    languages.observe("Собеседник", "en");
    assertEquals("sr", languages.input("Собеседник", "auto"));
    languages.observe("Я", "ru");
    languages.observe("Я", "ru");
    assertEquals("ru", languages.input("Я", "auto"));
    assertEquals("en", languages.input("Собеседник", "en"));
    languages.clear();
    assertEquals("auto", languages.input("Собеседник", "auto"));
  }
}
