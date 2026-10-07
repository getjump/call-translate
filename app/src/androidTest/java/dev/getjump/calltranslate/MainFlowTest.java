package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainFlowTest {
  @Test
  public void sourceAndOutputSelectionSurviveRecreation() {
    var instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var settings = new SettingsStore(context);
    String previousTarget = settings.target();
    String previousSource = settings.source();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          activity -> {
            activity
                .getWindow()
                .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Spinner source =
                activity.findViewById(
                    activity
                        .getResources()
                        .getIdentifier("source_language", "id", activity.getPackageName()));
            Spinner target =
                activity.findViewById(
                    activity
                        .getResources()
                        .getIdentifier("target_language", "id", activity.getPackageName()));
            assertNotNull("Source language control is missing", source);
            assertNotNull("Output language setting is missing", target);
            assertTrue(
                "Recognition languages were restricted to a fixed pair", source.getCount() > 90);
            assertTrue("Translation language list is missing", target.getCount() >= 200);
            source.setSelection(0);
            for (int i = 0; i < target.getCount(); i++) {
              if (target.getItemAtPosition(i).toString().endsWith("[eng_Latn]")) {
                target.setSelection(i);
                break;
              }
            }
          });
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.recreate();
      scenario.onActivity(
          activity -> {
            Spinner target =
                activity.findViewById(
                    activity
                        .getResources()
                        .getIdentifier("target_language", "id", activity.getPackageName()));
            assertTrue(
                "Output language was not persisted",
                target.getSelectedItem().toString().endsWith("[eng_Latn]"));
          });
    } finally {
      instrumentation.waitForIdleSync();
      instrumentation.runOnMainSync(
          () ->
              assertTrue(
                  "Could not restore persisted user languages",
                  context
                      .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                      .edit()
                      .putString("target", previousTarget)
                      .putString("source", previousSource)
                      .commit()));
    }
  }
}
