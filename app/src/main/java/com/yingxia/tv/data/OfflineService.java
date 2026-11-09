package com.yingxia.tv.data;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.*;
import android.net.Uri;
import androidx.media3.common.util.*;
import androidx.media3.database.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.*;
import androidx.media3.exoplayer.offline.*;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.scheduler.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.*;

/** Persistent Media3 downloads, including HLS and DASH manifests and their media segments. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class OfflineService extends DownloadService {
  private static SimpleCache cache;
  private static DownloadManager manager;

  public OfflineService() {
    super(41, 1000, "yingxia-downloads", com.yingxia.tv.R.string.download_channel, 0);
  }

  public static synchronized SimpleCache cache(Context c) {
    if (cache == null)
      cache =
          new SimpleCache(
              new File(c.getFilesDir(), "offline-media"),
              new NoOpCacheEvictor(),
              new StandaloneDatabaseProvider(c));
    return cache;
  }

  public static synchronized DownloadManager manager(Context c) {
    if (manager == null) {
      DataSource.Factory network =
          new ResolvingDataSource.Factory(
              new DefaultHttpDataSource.Factory().setUserAgent(Http.UA),
              spec -> {
                Map<String, String> headers = new LinkedHashMap<>();
                for (JSONObject item :
                    Json.objects(YingxiaApp.get().repository.list("downloads"))) {
                  if (Uri.parse(item.optString("url")).getHost() != null
                      && Uri.parse(item.optString("url")).getHost().equals(spec.uri.getHost()))
                    headers.putAll(Json.headers(item.optJSONObject("header")));
                }
                return spec.withAdditionalHeaders(headers);
              });
      manager =
          new DownloadManager(
              c.getApplicationContext(),
              new StandaloneDatabaseProvider(c),
              cache(c),
              network,
              Executors.newFixedThreadPool(2));
      manager.setMaxParallelDownloads(1);
      manager.setRequirements(new Requirements(Requirements.NETWORK));
      manager.addListener(
          new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager dm, Download d, Exception error) {
              if (d.state == Download.STATE_REMOVING) {
                YingxiaApp.get().repository.remove("downloads", d.request.id);
                return;
              }
              JSONObject item = Json.object(new String(d.request.data, StandardCharsets.UTF_8));
              if (item.length() == 0) return;
              try {
                item.put("complete", d.state == Download.STATE_COMPLETED)
                    .put("downloadState", d.state)
                    .put("bytesDownloaded", d.getBytesDownloaded());
              } catch (Exception ignored) {
              }
              YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
            }

            @Override
            public void onDownloadRemoved(DownloadManager dm, Download d) {
              YingxiaApp.get().repository.remove("downloads", d.request.id);
            }
          });
    }
    return manager;
  }

  public static void add(Context context, JSONObject play) {
    if (play.optBoolean("live")) {
      android.widget.Toast.makeText(context, "直播频道无法离线下载", android.widget.Toast.LENGTH_SHORT)
          .show();
      return;
    }
    JSONObject item = Json.copy(play);
    try {
      item.put("complete", false).put("downloadState", Download.STATE_QUEUED);
    } catch (Exception ignored) {
    }
    YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
    DownloadRequest request =
        new DownloadRequest.Builder(item.optString("id"), Uri.parse(item.optString("url")))
            .setData(item.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    DownloadService.sendAddDownload(context, OfflineService.class, request, false);
    DownloadService.sendResumeDownloads(context, OfflineService.class, false);
  }

  public static void remove(Context context, JSONObject item) {
    DownloadService.sendRemoveDownload(context, OfflineService.class, item.optString("id"), false);
    YingxiaApp.get().repository.remove("downloads", item.optString("id"));
  }

  public static String status(Context context, JSONObject item) {
    try {
      Download d = manager(context).getDownloadIndex().getDownload(item.optString("id"));
      if (d != null) {
        switch (d.state) {
          case Download.STATE_COMPLETED:
            return "已下载 · 可离线播放";
          case Download.STATE_FAILED:
            return "下载失败 · 长按可移除后重试";
          case Download.STATE_STOPPED:
            return "已暂停";
          case Download.STATE_DOWNLOADING:
            return "正在下载 "
                + (d.getPercentDownloaded() < 0
                    ? String.format(Locale.ROOT, "%.1f MB", d.getBytesDownloaded() / 1048576.0)
                    : String.format(Locale.ROOT, "%.0f%%", d.getPercentDownloaded()));
          default:
            return "等待下载";
        }
      }
    } catch (IOException ignored) {
    }
    return item.optBoolean("complete") ? "已下载" : "等待下载";
  }

  @Override
  protected DownloadManager getDownloadManager() {
    return manager(this);
  }

  @Override
  protected Scheduler getScheduler() {
    return null;
  }

  @Override
  protected Notification getForegroundNotification(List<Download> downloads, int notMet) {
    return new DownloadNotificationHelper(this, "yingxia-downloads")
        .buildProgressNotification(
            this, android.R.drawable.stat_sys_download, null, "映匣离线下载", downloads, notMet);
  }

  @Override
  public void onCreate() {
    if (android.os.Build.VERSION.SDK_INT >= 26) {
      NotificationChannel channel =
          new NotificationChannel("yingxia-downloads", "离线下载", NotificationManager.IMPORTANCE_LOW);
      ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
          .createNotificationChannel(channel);
    }
    super.onCreate();
  }
}
