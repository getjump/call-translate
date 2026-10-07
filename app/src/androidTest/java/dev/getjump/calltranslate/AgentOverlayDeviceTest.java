package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

public final class AgentOverlayDeviceTest {
  @Test
  public void agentOverlayShowsConversationOverAnotherAppAndClosesCleanly() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    Assume.assumeFalse(VoiceAgentService.running || CallService.running);
    assertTrue(
        "Overlay permission must be granted for this device test",
        Settings.canDrawOverlays(context));
    var automation = instrumentation.getUiAutomation();
    var info = automation.getServiceInfo();
    int previousFlags = info.flags;
    info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    automation.setServiceInfo(info);
    CaptionOverlay overlay = new CaptionOverlay(context);
    AtomicInteger opened = new AtomicInteger();
    AtomicInteger stopped = new AtomicInteger();
    try {
      instrumentation.runOnMainSync(
          () -> {
            overlay.showAgent(
                "Подключение голосового агента...",
                opened::incrementAndGet,
                stopped::incrementAndGet);
            overlay.render(
                List.of(
                    new CaptionHistory.Entry(1, "Собеседник", "Dobar dan", "", "", 0, 0),
                    new CaptionHistory.Entry(2, "Агент", "Kako mogu da pomognem?", "", "", 0, 0)));
            overlay.status("Агент слушает SIM");
          });
      instrumentation.waitForIdleSync();
      AccessibilityNodeInfo root = awaitWindow(true);
      assertNotNull(
          "Agent overlay did not appear above the foreground app; windows=" + windowTitles(), root);
      assertTrue("Caller transcript missing", texts(root).contains("Dobar dan"));
      assertTrue("Agent transcript missing", texts(root).contains("Kako mogu da pomognem?"));
      assertTrue("Current status missing", texts(root).contains("Агент слушает SIM"));
      assertFalse(
          "Raw agent transcript has a pending translation", texts(root).contains("Переводится..."));
      assertFalse(
          "Translation mode controls leaked into agent mode",
          texts(root).contains("Пауза / продолжить"));
      click(root, "Открыть диалог");
      click(root, "Остановить агента");
      instrumentation.waitForIdleSync();
      assertEquals("Open dialog callback", 1, opened.get());
      assertEquals("Stop callback", 1, stopped.get());
      instrumentation.runOnMainSync(overlay::close);
      instrumentation.waitForIdleSync();
      assertNull("Agent overlay remains after close", awaitWindow(false));
    } finally {
      instrumentation.runOnMainSync(overlay::close);
      info.flags = previousFlags;
      automation.setServiceInfo(info);
    }
  }

  private static AccessibilityNodeInfo awaitWindow(boolean present) {
    long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
    AccessibilityNodeInfo root;
    do {
      root = windowRoot();
      if ((root != null) == present) return root;
      android.os.SystemClock.sleep(16);
    } while (android.os.SystemClock.elapsedRealtime() < deadline);
    return root;
  }

  private static List<String> windowTitles() {
    return InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows().stream()
        .map(window -> String.valueOf(window.getTitle()))
        .toList();
  }

  private static AccessibilityNodeInfo windowRoot() {
    for (var window : InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()) {
      var root = window.getRoot();
      if (root != null
          && "dev.getjump.calltranslate"
              .contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
          && texts(root).contains("Открыть диалог")
          && texts(root).contains("Остановить агента")) return root;
    }
    return null;
  }

  private static List<String> texts(AccessibilityNodeInfo node) {
    List<String> values = new ArrayList<>();
    if (node.getText() != null) values.add(node.getText().toString());
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child != null) values.addAll(texts(child));
    }
    return values;
  }

  private static void click(AccessibilityNodeInfo root, String text) {
    var matches = root.findAccessibilityNodeInfosByText(text);
    assertFalse("Missing action: " + text, matches.isEmpty());
    assertTrue(
        "Cannot click action: " + text,
        matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
  }
}
