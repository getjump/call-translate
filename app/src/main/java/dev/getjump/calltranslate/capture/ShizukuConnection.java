package dev.getjump.calltranslate.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import dev.getjump.calltranslate.BuildConfig;
import dev.getjump.calltranslate.ICaptureService;
import rikka.shizuku.Shizuku;

public final class ShizukuConnection implements AutoCloseable {
  public interface Listener {
    void connected(ICaptureService service);

    void failed(String reason);
  }

  private final Listener listener;
  private final Shizuku.UserServiceArgs arguments;
  private boolean bound;
  private boolean closed;
  private final Shizuku.OnBinderDeadListener dead =
      () -> fail("Shizuku остановлен. Звонок продолжается без субтитров");
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          if (!closed) listener.connected(ICaptureService.Stub.asInterface(binder));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          fail("Захват звонка отключился");
        }
      };

  public ShizukuConnection(Context context, Listener listener) {
    this.listener = listener;
    arguments =
        new Shizuku.UserServiceArgs(new ComponentName(context, CaptureUserService.class))
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);
  }

  private void fail(String reason) {
    if (!closed) listener.failed(reason);
  }

  public static boolean permitted() {
    try {
      return Shizuku.pingBinder()
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Exception error) {
      return false;
    }
  }

  public void connect() {
    if (!permitted()) {
      fail("Запусти Shizuku и разреши доступ приложению");
      return;
    }
    try {
      Shizuku.addBinderDeadListener(dead);
      bound = true;
      Shizuku.bindUserService(arguments, connection);
    } catch (Exception error) {
      fail(error.toString());
    }
  }

  @Override
  public void close() {
    closed = true;
    Shizuku.removeBinderDeadListener(dead);
    if (bound) {
      try {
        Shizuku.unbindUserService(arguments, connection, true);
      } catch (Exception error) {
        android.util.Log.e("CallTranslate", "Shizuku cleanup failed", error);
      }
      bound = false;
    }
  }
}
