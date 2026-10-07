package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.agent.ElevenAgentsApi;
import dev.getjump.calltranslate.core.CaptionHistory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

public final class AgentIntegrationDeviceTest {
  @Test
  public void agentTranscriptIsVisibleAtTheTopAndSurvivesReopening() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Assume.assumeFalse(VoiceAgentService.running);
    var previous = VoiceAgentService.history.snapshot();
    VoiceAgentService.history.clear();
    VoiceAgentService.history.update(
        new CaptionHistory.Entry(9001, "Собеседник", "Dobar dan", "", "sr", 0, 0));
    VoiceAgentService.history.update(
        new CaptionHistory.Entry(9002, "Агент", "Kako mogu da pomognem?", "", "sr", 0, 0));
    try {
      for (int open = 0; open < 2; open++) {
        var activity =
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), AgentActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        try {
          instrumentation.runOnMainSync(
              () -> {
                View feed = activity.findViewById(R.id.caption_scroll);
                Rect visible = new Rect();
                assertTrue(
                    "Agent transcript is below the screen", feed.getGlobalVisibleRect(visible));
                assertTrue("Agent transcript is too small to read", visible.height() >= 150);
                List<TextView> labels = new ArrayList<>();
                collect(activity.getWindow().getDecorView(), labels);
                TextView configuration =
                    labels.stream()
                        .filter(view -> view.getText().toString().equals("Обновить агентов"))
                        .findFirst()
                        .orElseThrow();
                int[] feedPosition = new int[2];
                int[] configurationPosition = new int[2];
                feed.getLocationInWindow(feedPosition);
                configuration.getLocationInWindow(configurationPosition);
                assertTrue(
                    "Transcript follows setup: transcriptY="
                        + feedPosition[1]
                        + ", setupY="
                        + configurationPosition[1],
                    feedPosition[1] < configurationPosition[1]);
                labels.clear();
                collect(feed, labels);
                assertTrue(
                    labels.stream()
                        .anyMatch(view -> view.getText().toString().equals("Dobar dan")));
                assertTrue(
                    labels.stream()
                        .anyMatch(
                            view -> view.getText().toString().equals("Kako mogu da pomognem?")));
              });
        } finally {
          instrumentation.runOnMainSync(activity::finish);
          instrumentation.waitForIdleSync();
        }
      }
    } finally {
      VoiceAgentService.history.clear();
      for (var entry : previous) VoiceAgentService.history.update(entry);
    }
  }

  @Test
  public void agentPageStartsWithoutRemoteWritesOrAnAudioSession() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Assume.assumeFalse(VoiceAgentService.running);
    var activity =
        instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), AgentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    try {
      instrumentation.runOnMainSync(
          () -> {
            List<TextView> labels = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), labels);
            assertTrue(
                labels.stream()
                    .anyMatch(view -> view.getText().toString().equals("Обновить агентов")));
            assertTrue(
                labels.stream()
                    .anyMatch(
                        view ->
                            view.getText().toString().equals("Передать приватную инструкцию")
                                && !view.isEnabled()));
            assertTrue(
                labels.stream().anyMatch(view -> view instanceof CheckBox && !view.isEnabled()));
            assertFalse(VoiceAgentService.running);
            assertFalse(VoiceAgentService.connected);
          });
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  @Test
  public void readAccountCapabilitiesWhenExplicitlyRequested() throws Exception {
    Assume.assumeTrue(
        InstrumentationRegistry.getArguments()
            .getString("verify_agent_api", "false")
            .equals("true"));
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    String key = new CredentialStore(context).load();
    assertFalse("ElevenLabs key is missing", key.isBlank());
    JSONObject results = new JSONObject().put("externalWrites", false).put("audioUploaded", false);
    try {
      results.put("ownedAgents", ElevenAgentsApi.listOwned(key).size());
    } catch (Exception error) {
      results.put("agentReadError", error.getMessage());
    }
    try {
      results.put("voices", ElevenAgentsApi.listVoices(key).size());
    } catch (Exception error) {
      results.put("voiceReadError", error.getMessage());
    }
    String report = results.toString();
    assertFalse("A credential appeared in diagnostics", report.contains(key));
    Files.write(
        context.getNoBackupFilesDir().toPath().resolve("agent-api-check.json"),
        report.getBytes(StandardCharsets.UTF_8));
    Bundle status = new Bundle();
    status.putString("agent_api_check", report);
    instrumentation.sendStatus(0, status);
    assertTrue(
        "No account capability response",
        results.has("ownedAgents") || results.has("agentReadError"));
  }

  private static void collect(View view, List<TextView> output) {
    if (view instanceof TextView text) output.add(text);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), output);
  }
}
