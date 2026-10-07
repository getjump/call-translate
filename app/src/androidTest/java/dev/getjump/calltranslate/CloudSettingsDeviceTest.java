package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.cloud.CredentialStore;
import dev.getjump.calltranslate.cloud.ElevenLabsApi;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CloudSettingsDeviceTest {
  @Test
  public void validKeyPopulatesOnlyRealtimePicker() throws Exception {
    try (Fixture data = new Fixture();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      attach(
          scenario,
          data,
          key ->
              List.of(
                  new ElevenLabsApi.Model("scribe_v2", "Batch", false),
                  new ElevenLabsApi.Model("scribe_v2_realtime", "Realtime", true)));
      enterAndVerify(scenario, data, "test-new-key");
      data.idle();
      assertEquals("test-new-key", data.credentials.load());
      scenario.onActivity(
          activity -> {
            Spinner picker = data.view.get().findViewById(R.id.eleven_model);
            assertEquals(1, picker.getCount());
            assertEquals(
                "scribe_v2_realtime", ((ElevenLabsApi.Model) picker.getSelectedItem()).id());
            assertEquals(
                "",
                ((EditText) data.view.get().findViewById(R.id.eleven_api_key))
                    .getText()
                    .toString());
          });
    }
  }

  @Test
  public void invalidReplacementPreservesKeyAndCatalog() throws Exception {
    try (Fixture data = new Fixture();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      data.credentials.save("test-old-key");
      String cache = "[{\"id\":\"scribe_v2_realtime\",\"name\":\"Saved\",\"realtime\":true}]";
      data.settings.set("cloudModels", cache);
      attach(
          scenario,
          data,
          key -> {
            throw new IllegalStateException("Key rejected");
          });
      enterAndVerify(scenario, data, "test-rejected-key");
      data.idle();
      assertEquals("test-old-key", data.credentials.load());
      assertEquals(cache, data.settings.cloudModels());
      scenario.onActivity(
          activity ->
              assertEquals(
                  1, ((Spinner) data.view.get().findViewById(R.id.eleven_model)).getCount()));
    }
  }

  @Test
  public void captureStartInvalidatesDelayedKeySave() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Fixture data = new Fixture();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      data.credentials.save("test-old-key");
      attach(
          scenario,
          data,
          key -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return List.of(new ElevenLabsApi.Model("scribe_v2_realtime", "Realtime", true));
          });
      enterAndVerify(scenario, data, "test-new-key");
      assertTrue("Verification did not start", entered.await(5, TimeUnit.SECONDS));
      scenario.onActivity(activity -> data.view.get().active(true));
      release.countDown();
      data.idle();
      assertEquals("test-old-key", data.credentials.load());
      assertEquals("[]", data.settings.cloudModels());
      scenario.onActivity(
          activity -> assertFalse(data.view.get().findViewById(R.id.eleven_api_key).isEnabled()));
    } finally {
      release.countDown();
    }
  }

  @Test
  public void captureStartInvalidatesQueuedKeyRemoval() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Fixture data = new Fixture();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      data.credentials.save("test-old-key");
      data.requests.submit(
          () -> {
            entered.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      attach(scenario, data, key -> List.of());
      scenario.onActivity(
          activity -> {
            button(data.view.get(), "Удалить ключ").performClick();
            data.view.get().active(true);
          });
      release.countDown();
      data.idle();
      assertEquals("test-old-key", data.credentials.load());
    } finally {
      release.countDown();
    }
  }

  private static void attach(
      ActivityScenario<MainActivity> scenario,
      Fixture data,
      CloudSettingsView.ModelVerifier verifier) {
    scenario.onActivity(
        activity -> {
          CloudSettingsView view =
              new CloudSettingsView(
                  activity, data.settings, data.credentials, verifier, data.requests);
          data.view.set(view);
          activity.setContentView(view);
        });
  }

  private static void enterAndVerify(
      ActivityScenario<MainActivity> scenario, Fixture data, String key) {
    scenario.onActivity(
        activity -> {
          ((EditText) data.view.get().findViewById(R.id.eleven_api_key)).setText(key);
          button(data.view.get(), "Проверить ключ / обновить модели").performClick();
        });
  }

  private static Button button(View root, String label) {
    if (root instanceof Button button && button.getText().toString().equals(label)) return button;
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = button(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static final class Fixture implements AutoCloseable {
    final ExecutorService requests = Executors.newSingleThreadExecutor();
    final AtomicReference<CloudSettingsView> view = new AtomicReference<>();
    final CredentialStore credentials;
    final SettingsStore settings;
    final SettingsStore realSettings;
    final String previousProvider;

    Fixture() {
      Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
      String suffix = UUID.randomUUID().toString();
      Context isolated =
          new ContextWrapper(context) {
            @Override
            public Context getApplicationContext() {
              return this;
            }

            @Override
            public String getPackageName() {
              return context.getPackageName() + ".uitest." + suffix;
            }

            @Override
            public File getNoBackupFilesDir() {
              return new File(context.getCacheDir(), "cloud-ui-" + suffix);
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
              return context.getSharedPreferences("uitest-" + suffix + "-" + name, mode);
            }
          };
      credentials = new CredentialStore(isolated);
      settings = new SettingsStore(isolated);
      realSettings = new SettingsStore(context);
      previousProvider = realSettings.provider();
      realSettings.set("provider", "local");
    }

    void idle() throws Exception {
      requests.submit(() -> {}).get(10, TimeUnit.SECONDS);
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Override
    public void close() throws Exception {
      if (view.get() != null) view.get().close();
      requests.shutdownNow();
      assertTrue("UI request worker did not stop", requests.awaitTermination(10, TimeUnit.SECONDS));
      credentials.clear();
      realSettings.set("provider", previousProvider);
    }
  }
}
