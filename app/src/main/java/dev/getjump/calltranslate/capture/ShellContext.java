package dev.getjump.calltranslate.capture;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

final class ShellContext extends ContextWrapper {
  ShellContext(Context context) {
    super(context);
  }

  @Override
  public String getPackageName() {
    return "com.android.shell";
  }

  @Override
  public String getOpPackageName() {
    return "com.android.shell";
  }

  @Override
  public AttributionSource getAttributionSource() {
    return new AttributionSource.Builder(Process.myUid())
        .setPackageName("com.android.shell")
        .build();
  }

  @Override
  public Context getApplicationContext() {
    return this;
  }
}
