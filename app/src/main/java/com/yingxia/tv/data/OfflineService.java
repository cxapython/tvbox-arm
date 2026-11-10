package com.yingxia.tv.data;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.*;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.*;
import androidx.media3.database.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.*;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.*;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.scheduler.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.json.*;

/** Persistent Media3 downloads, including HLS and DASH manifests and their media segments. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class OfflineService extends DownloadService {
  private static final String TAG = "Yingxia.Download";
  private static final Set<String> renewing = ConcurrentHashMap.newKeySet();
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

  private static DataSource.Factory network(Context context) {
    return new ResolvingDataSource.Factory(
        PlaybackHttp.factory(java.util.Collections.emptyMap()),
        spec -> {
          Map<String, String> headers = new LinkedHashMap<>();
          for (JSONObject item : Json.objects(YingxiaApp.get().repository.list("downloads"))) {
            Uri uri = Uri.parse(item.optString("url"));
            if (uri.getHost() != null && uri.getHost().equals(spec.uri.getHost()))
              headers.putAll(Json.headers(item.optJSONObject("header")));
          }
          Map<String, String> combined = new LinkedHashMap<>(headers);
          combined.putAll(spec.httpRequestHeaders);
          return spec.buildUpon().setHttpRequestHeaders(PlaybackHttp.headers(combined)).build();
        });
  }

  public static synchronized DownloadManager manager(Context c) {
    if (manager == null) {
      Context appContext = c.getApplicationContext();
      manager =
          new DownloadManager(
              appContext,
              new StandaloneDatabaseProvider(appContext),
              cache(appContext),
              network(appContext),
              Executors.newFixedThreadPool(DownloadPolicy.MAX_SIMULTANEOUS));
      manager.setMaxParallelDownloads(parallelDownloads());
      manager.setRequirements(new Requirements(Requirements.NETWORK));
      manager.addListener(
          new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager dm, Download d, Exception error) {
              if (d.state == Download.STATE_REMOVING) {
                renewing.remove(d.request.id);
                YingxiaApp.get().repository.remove("downloads", d.request.id);
                return;
              }
              JSONObject item = Json.object(new String(d.request.data, StandardCharsets.UTF_8));
              if (item.length() == 0) {
                if (d.request.data.length > 0)
                  android.util.Log.w(TAG, "Download metadata is not valid JSON: " + d.request.id);
                return;
              }
              try {
                item.put("complete", d.state == Download.STATE_COMPLETED)
                    .put("downloadState", d.state)
                    .put("bytesDownloaded", d.getBytesDownloaded());
                if (d.state == Download.STATE_COMPLETED) item.put("downloadRenewals", 0);
                if (error == null) item.remove("downloadError");
                else item.put("downloadError", Json.message(error));
                YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
              } catch (Exception saveError) {
                android.util.Log.e(TAG, "Cannot persist download state: " + d.request.id, saveError);
                toast(appContext, "无法保存下载状态：" + Json.message(saveError));
              }
              if (d.state == Download.STATE_FAILED && error != null)
                maybeRenew(appContext, item, error);
            }

            @Override
            public void onDownloadRemoved(DownloadManager dm, Download d) {
              renewing.remove(d.request.id);
              YingxiaApp.get().repository.remove("downloads", d.request.id);
            }
          });
    }
    return manager;
  }

  /** Reuse the persisted cache key and selected streams when opening a completed download. */
  public static MediaItem playbackItem(Context context, JSONObject item) throws IOException {
    if (!item.optBoolean("complete")) return MediaItem.fromUri(item.optString("url"));
    Download download = manager(context).getDownloadIndex().getDownload(item.optString("id"));
    if (download == null || download.state != Download.STATE_COMPLETED)
      throw new IOException("下载记录已失效，请重新下载");
    return download.request.toMediaItem();
  }

  public static int parallelDownloads() {
    return DownloadPolicy.simultaneous(
        YingxiaApp.get()
            .repository
            .settings
            .getInt(DownloadPolicy.SIMULTANEOUS_SETTING, 0));
  }

  /** Settings UI uses this so an existing manager immediately observes the new limit. */
  public static synchronized void setParallelDownloads(int value) {
    int clamped = DownloadPolicy.clampSimultaneous(value);
    YingxiaApp.get()
        .repository
        .settings
        .edit()
        .putInt(DownloadPolicy.SIMULTANEOUS_SETTING, clamped)
        .apply();
    if (manager != null) manager.setMaxParallelDownloads(clamped);
  }

  private interface RequestCallback {
    void onReady(DownloadRequest request);

    void onError(Exception error);
  }

  public static void add(Context context, JSONObject play) {
    if (play.optBoolean("live")) {
      toast(context, "直播频道无法离线下载");
      return;
    }
    JSONObject item = Json.copy(play);
    try {
      if (item.optString("id").isEmpty() || item.optString("url").isEmpty())
        throw new IOException("下载地址为空，请重新解析后重试");
      item.put("complete", false)
          .put("downloadState", Download.STATE_QUEUED)
          .put("downloadRenewals", 0);
      item.remove("downloadError");
      YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
    } catch (Exception error) {
      preparationFailed(context, item, "无法准备下载：", error);
      return;
    }
    prepareRequest(
        context,
        item,
        new RequestCallback() {
          @Override
          public void onReady(DownloadRequest request) {
            try {
              enqueue(context, request);
            } catch (Exception error) {
              preparationFailed(context, item, "无法加入下载队列：", error);
            }
          }

          @Override
          public void onError(Exception error) {
            preparationFailedOrRenew(context, item, "无法准备下载：", error);
          }
        });
  }

  /** Adaptive downloads must contain Media3's selected stream keys, not just a manifest URL. */
  private static void prepareRequest(Context context, JSONObject item, RequestCallback callback) {
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
      new android.os.Handler(android.os.Looper.getMainLooper())
          .post(() -> prepareRequest(context, item, callback));
      return;
    }
    Uri uri;
    try {
      uri = Uri.parse(item.optString("url"));
    } catch (Exception error) {
      callback.onError(error);
      return;
    }
    byte[] data = item.toString().getBytes(StandardCharsets.UTF_8);
    DownloadPolicy.MediaKind kind = DownloadPolicy.mediaKindForItem(item);
    if (kind == DownloadPolicy.MediaKind.PROGRESSIVE) {
      try {
        callback.onReady(
            new DownloadRequest.Builder(item.optString("id"), uri)
                .setCustomCacheKey(item.optString("id"))
                .setData(data)
                .build());
      } catch (Exception error) {
        callback.onError(error);
      }
      return;
    }

    String mimeType =
        kind == DownloadPolicy.MediaKind.HLS ? MimeTypes.APPLICATION_M3U8 : MimeTypes.APPLICATION_MPD;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setCustomCacheKey(item.optString("id"))
            .build();
    final DownloadHelper helper;
    try {
      helper =
          DownloadHelper.forMediaItem(
              mediaItem,
              DownloadHelper.getDefaultTrackSelectorParameters(context),
              new DefaultRenderersFactory(context),
              network(context));
    } catch (Exception error) {
      callback.onError(error);
      return;
    }
    try {
      helper.prepare(
          new DownloadHelper.Callback() {
            @Override
            public void onPrepared(DownloadHelper prepared) {
              try {
                callback.onReady(
                    prepared.getDownloadRequest(item.optString("id"), data));
              } catch (Exception error) {
                callback.onError(error);
              } finally {
                prepared.release();
              }
            }

            @Override
            public void onPrepareError(DownloadHelper prepared, IOException error) {
              try {
                callback.onError(error);
              } finally {
                prepared.release();
              }
            }
          });
    } catch (Exception error) {
      helper.release();
      callback.onError(error);
    }
  }

  private static void enqueue(Context context, DownloadRequest request) {
    DownloadService.sendAddDownload(context, OfflineService.class, request, false);
    DownloadService.sendResumeDownloads(context, OfflineService.class, false);
  }

  public static void remove(Context context, JSONObject item) {
    DownloadService.sendRemoveDownload(context, OfflineService.class, item.optString("id"), false);
    YingxiaApp.get().repository.remove("downloads", item.optString("id"));
  }

  private static JSONObject resolve(JSONObject item) throws Exception {
    if (item.optBoolean("music")) return new MusicClient().play(item);
    if (Json.child(item, "site").length() > 0) return YingxiaApp.get().engine.resume(item);
    throw new IOException("此下载没有可用于刷新地址的来源信息");
  }

  private static void maybeRenew(Context context, JSONObject item, Exception failure) {
    int status = expiredStatus(failure);
    int used = item.optInt("downloadRenewals", 0);
    if (!DownloadPolicy.shouldRenew(status, used, DownloadPolicy.canResolve(item))) return;
    String id = item.optString("id");
    if (id.isEmpty() || !renewing.add(id)) return;
    int attempt = used + 1;
    try {
      item.put("downloadRenewals", attempt)
          .put("downloadState", Download.STATE_QUEUED)
          .put("complete", false);
      item.remove("downloadError");
      YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
    } catch (Exception error) {
      renewing.remove(id);
      preparationFailed(context, item, "无法保存续链状态：", error);
      return;
    }
    android.util.Log.i(
        TAG, "Renewing expired download id=" + id + " HTTP=" + status + " attempt=" + attempt);
    toast(context, "下载地址已过期（HTTP " + status + "），正在重新解析（" + attempt + "/2）");
    YingxiaApp.get()
        .io
        .execute(
            () -> {
              JSONObject refreshed;
              try {
                refreshed = resolve(Json.copy(item));
                refreshed.put("id", id)
                    .put("complete", false)
                    .put("downloadState", Download.STATE_QUEUED)
                    .put("downloadRenewals", attempt)
                    .put("bytesDownloaded", item.optLong("bytesDownloaded"));
                refreshed.remove("downloadError");
                if (!YingxiaApp.get().repository.contains("downloads", id)) {
                  renewing.remove(id);
                  return;
                }
                YingxiaApp.get().repository.upsert("downloads", refreshed, "id", false, 500);
              } catch (Exception error) {
                renewing.remove(id);
                preparationFailed(context, item, "下载地址刷新失败：", error);
                return;
              }
              prepareRequest(
                  context,
                  refreshed,
                  new RequestCallback() {
                    @Override
                    public void onReady(DownloadRequest request) {
                      renewing.remove(id);
                      if (!YingxiaApp.get().repository.contains("downloads", id)) return;
                      try {
                        enqueue(context, request);
                        toast(context, "下载地址已更新，继续下载");
                      } catch (Exception error) {
                        preparationFailed(context, refreshed, "无法继续下载：", error);
                      }
                    }

                    @Override
                    public void onError(Exception error) {
                      renewing.remove(id);
                      preparationFailedOrRenew(context, refreshed, "续链后无法准备下载：", error);
                    }
                  });
            });
  }

  private static int expiredStatus(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    while (current != null && seen.add(current)) {
      if (current instanceof HttpDataSource.InvalidResponseCodeException) {
        int status = ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
        if (DownloadPolicy.isExpiredStatus(status)) return status;
      }
      current = current.getCause();
    }
    return 0;
  }

  private static void preparationFailedOrRenew(
      Context context, JSONObject item, String prefix, Exception error) {
    int status = expiredStatus(error);
    if (DownloadPolicy.shouldRenew(
        status, item.optInt("downloadRenewals", 0), DownloadPolicy.canResolve(item))) {
      maybeRenew(context, item, error);
      return;
    }
    preparationFailed(context, item, prefix, error);
  }

  private static void preparationFailed(
      Context context, JSONObject item, String prefix, Exception error) {
    android.util.Log.e(TAG, prefix + item.optString("id"), error);
    try {
      item.put("complete", false)
          .put("downloadState", Download.STATE_FAILED)
          .put("downloadError", prefix + Json.message(error));
      YingxiaApp.get().repository.upsert("downloads", item, "id", false, 500);
    } catch (Exception saveError) {
      error.addSuppressed(saveError);
      android.util.Log.e(TAG, "Cannot persist preparation failure: " + item.optString("id"), error);
    }
    toast(context, prefix + Json.message(error));
  }

  private static void toast(Context context, String message) {
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(() -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show());
  }

  public static String status(Context context, JSONObject item) {
    try {
      Download d = manager(context).getDownloadIndex().getDownload(item.optString("id"));
      if (d != null) {
        switch (d.state) {
          case Download.STATE_COMPLETED:
            return "已下载 · 可离线播放";
          case Download.STATE_FAILED:
            String failure = item.optString("downloadError");
            return "下载失败"
                + (failure.isEmpty() ? "" : " · " + failure)
                + " · 长按可移除后重试";
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
    } catch (IOException error) {
      android.util.Log.e(TAG, "Cannot read download status: " + item.optString("id"), error);
      return "下载状态读取失败：" + Json.message(error);
    }
    if (item.optBoolean("complete")) return "已下载";
    if (item.optInt("downloadState") == Download.STATE_FAILED)
      return "下载失败 · " + item.optString("downloadError", "请移除后重试");
    return "等待下载";
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
