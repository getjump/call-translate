package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class DraftTranslationTest {
  @Test
  public void translationOfPrefixKeepsTheNewestOriginal() {
    DraftTranslation state = new DraftTranslation();
    state.update("Za informacije o mobilnoj", "sr", false);
    var request = state.request(0);
    assertNotNull(request);
    state.update("Za informacije o mobilnoj telefoniji", "sr", false);
    assertTrue(state.complete(request, "Для информации о"));
    assertEquals("Za informacije o mobilnoj telefoniji", state.original());
    assertEquals("Для информации о", state.translation());
    assertTrue(state.draft());
  }

  @Test
  public void correctedNegationRejectsOldResultAndClearsPreview() {
    DraftTranslation state = new DraftTranslation();
    state.update("Ne dolazite u petak.", "sr", false);
    var request = state.request(0);
    assertTrue(state.complete(request, "Не приходите в пятницу."));
    state.update("Dođite u petak.", "sr", false);
    assertNull(state.translation());
    assertFalse(state.complete(request, "Не приходите в пятницу."));
  }

  @Test
  public void numericWordExtensionIsNotAnUnchangedPrefix() {
    DraftTranslation state = new DraftTranslation();
    state.update("Broj je 15", "sr", false);
    var request = new DraftTranslation.Request("Broj je 15", "sr", 1, false);
    state.update("Broj je 150", "sr", false);
    assertFalse(state.accepts(request));
  }

  @Test
  public void finalBypassesThrottleAndRejectsRunningDraft() {
    DraftTranslation state = new DraftTranslation();
    state.update("Za informacije o mobilnoj", "sr", false);
    var draft = state.request(0);
    assertTrue(state.complete(draft, "Для информации о"));
    state.update("Za informacije o mobilnoj telefoniji", "sr", false);
    assertNull(state.request(200));
    state.update("Za informacije o mobilnoj telefoniji.", "sr", true);
    var finalRequest = state.request(201);
    assertNotNull(finalRequest);
    assertTrue(finalRequest.committed());
    assertFalse(state.complete(draft, "Старый перевод"));
    assertTrue(state.complete(finalRequest, "Для информации о мобильной телефонии."));
    assertFalse(state.draft());
    assertNull(state.request(202));
  }

  @Test
  public void changedLanguageRejectsPreviousTranslation() {
    DraftTranslation state = new DraftTranslation();
    state.update("Dobar dan svima.", "sr", false);
    var request = state.request(0);
    state.update("Dobar dan svima.", "hr", false);
    assertFalse(state.complete(request, "Добрый день всем."));
  }

  @Test
  public void missingLanguageWaitsAndFinalUsesCompleteText() {
    DraftTranslation state = new DraftTranslation();
    state.update("Dobar dan svima", "", false);
    assertNull(state.request(0));
    state.update("Dobar dan svima", "sr", true);
    var request = state.request(1);
    assertEquals("Dobar dan svima", request.original());
  }

  @Test
  public void rapidUpdatesAreCoalescedWithoutRepeatingSameRequest() {
    DraftTranslation state = new DraftTranslation();
    state.update("Za informacije o mobilnoj", "sr", false);
    assertNotNull(state.request(0));
    state.update("Za informacije o mobilnoj telefoniji", "sr", false);
    assertNull(state.request(100));
    assertNotNull(state.request(1000));
    assertNull(state.request(2000));
  }
}
