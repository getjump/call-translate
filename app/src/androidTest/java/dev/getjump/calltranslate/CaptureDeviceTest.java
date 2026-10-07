package dev.getjump.calltranslate;

import static org.junit.Assert.*;

import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.getjump.calltranslate.capture.ShizukuConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CaptureDeviceTest {
  @Test
  public void appCanBindShellCaptureAndCloseDownlinkPipe() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue("Grant Shizuku access in Call Translate first", ShizukuConnection.permitted());
    CountDownLatch connected = new CountDownLatch(1);
    AtomicReference<ICaptureService> capture = new AtomicReference<>();
    AtomicReference<String> failure = new AtomicReference<>();
    var connection =
        new ShizukuConnection(
            context,
            new ShizukuConnection.Listener() {
              @Override
              public void connected(ICaptureService service) {
                capture.set(service);
                connected.countDown();
              }

              @Override
              public void failed(String reason) {
                failure.set(reason);
                connected.countDown();
              }
            });
    Handler main = new Handler(Looper.getMainLooper());
    main.post(connection::connect);
    try {
      assertTrue("Capture binding timed out", connected.await(15, TimeUnit.SECONDS));
      assertNull(failure.get(), failure.get());
      assertNotNull(capture.get());
      assertTrue(capture.get().diagnostics(), capture.get().diagnostics().contains("uid=2000"));
      try (var input =
          new ParcelFileDescriptor.AutoCloseInputStream(
              capture.get().start(MediaRecorder.AudioSource.VOICE_DOWNLINK))) {
        assertTrue("Downlink PCM pipe did not deliver data", input.read(new byte[3200]) > 0);
      }
      capture.get().stop();
    } finally {
      main.post(connection::close);
    }
  }
}
