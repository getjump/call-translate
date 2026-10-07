# OxygenOS 16 Shizuku authorization evidence

Device: OnePlus 15 CPH2747, build CPH2747_16.0.10.500(EX01). Exact /system_ext/priv-app/Settings/Settings.apk was read through USB ADB on 2026-10-07. No developer permission setting was changed by a tool.

Shizuku 13.6 RequestPermissionActivity checks the server's GRANT_RUNTIME_PERMISSIONS via Shizuku.checkRemotePermission. A failure shows the "permission of adb is limited" dialog and closes authorization.

The official guide https://shizuku.rikka.app/guide/setup/ names "Permission monitoring" for OnePlus. This firmware's res/xml/development_settings_oplus.xml instead has key safecenter_system_optimization, title resource safecenter_system_optimization_title, under debug_applications_category, immediately before debug_autofill_category. Its English title is "Disable system optimization" and Russian title is "Выключить оптимизацию системы".

SystemOptimizationPreferenceController.isAvailable() is equivalent to:

```java
return Locale.getDefault().getLanguage().equalsIgnoreCase("en")
    || Settings.Secure.getInt(resolver, "system_opt_enable", 0) == 1
    || !AppPermissionJumpPreferenceController.isPermissionMonitorEnable();
```

Thus the row is initially hidden on Russian and other non-English locales while monitoring is enabled. Switch the phone's system language to English, open Developer options -> Apps, enable "Disable system optimization" using the standard confirmation, then restore the original language. The switch reads the inverse of OplusActivityManager.isPermissionInterceptEnabled(). Its displayed summary warns that background battery usage may increase.

A static dumpsys package grant=true does not by itself prove that the remote check succeeds, because the OEM permission-intercept layer can affect the actual Binder permission check. App authorization and shell capture still need a live test after the user changes the setting.
