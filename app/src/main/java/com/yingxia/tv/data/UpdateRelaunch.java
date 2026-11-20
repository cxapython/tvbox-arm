package com.yingxia.tv.data;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.yingxia.tv.BuildConfig;
import com.yingxia.tv.R;
import com.yingxia.tv.YingxiaApp;
import com.yingxia.tv.ui.MainActivity;

/** Runs in the new process only after Android successfully replaces this package. */
public final class UpdateRelaunch extends BroadcastReceiver {
  private static final String CHANNEL = "app_updates";
  private static final int NOTIFICATION_ID = 733;

  public static Intent openApp(Context context) {
    return new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
  }

  @Override public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()) || !YingxiaApp.get().updates.completedInstall()) return;
    if (Build.VERSION.SDK_INT < 29) {
      try { context.startActivity(openApp(context)); return; }
      catch (RuntimeException error) { Log.d("Yingxia.Update", "Could not reopen after installation", error); }
    }
    // Android 10+ limits background activity launches. Leave "Open" to the installer,
    // and offer a launcher notification as a second route when notifications are enabled.
    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null || (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled())
        || (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return;
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(new NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT));
    }
    PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, openApp(context), PendingIntent.FLAG_IMMUTABLE);
    manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.tv_ic_update_done).setContentTitle("映匣已更新")
        .setContentText("点击打开新版 " + BuildConfig.VERSION_NAME).setContentIntent(open).setAutoCancel(true).build());
  }

  public static void clearNotice(Context context) {
    NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) manager.cancel(NOTIFICATION_ID);
  }
}
