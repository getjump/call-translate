package dev.getjump.calltranslate.capture;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;

public final class CaptureShellProbe {
  public static void main(String[] arguments) throws Exception {
    android.os.Looper.prepareMainLooper();
    Class<?> activityThread = Class.forName("android.app.ActivityThread");
    Object thread = activityThread.getMethod("systemMain").invoke(null);
    Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    CaptureUserService service = new CaptureUserService(context);
    System.out.println(service.diagnostics());
    if (arguments.length > 0 && arguments[0].equals("idle-injection-check")) {
      android.media.AudioManager manager =
          context.getSystemService(android.media.AudioManager.class);
      if (manager.getMode() != android.media.AudioManager.MODE_NORMAL)
        throw new IllegalStateException("Idle probe requires normal audio mode");
      android.media.AudioManager.class.getMethod(
          "getCallUplinkInjectionAudioTrack", android.media.AudioFormat.class);
      System.out.println("uplink injection framework method available");
      try {
        service.openInjection(16000);
        throw new IllegalStateException("Injection unexpectedly opened outside a SIM call");
      } catch (android.os.RemoteException expected) {
        if (!expected.getMessage().contains("SIM call is not active")) throw expected;
        System.out.println("idle injection rejected before audio track creation");
      } finally {
        service.stop();
      }
      System.exit(0);
      return;
    }
    try (ParcelFileDescriptor descriptor = service.start(3);
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      byte[] block = new byte[3200];
      int count = input.read(block);
      if (count <= 0) throw new IllegalStateException("No downlink PCM delivered");
      System.out.println("downlink PCM bytes=" + count);
    } finally {
      service.stop();
    }
  }
}
