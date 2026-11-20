package com.yingxia.tv.data;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import com.yingxia.tv.BuildConfig;
import com.yingxia.tv.core.AppUpdate;
import com.yingxia.tv.core.Json;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;
import org.json.JSONObject;

/** System-owned background downloads; every APK is copied privately and verified before sharing. */
public final class AppUpdates {
  public static final String AUTO_CHECK = "updates.autoCheck";
  private static final String INSTALL_VERSION = "installVersion";
  private static final String INSTALL_STARTED = "installStarted";
  private static final long INSTALL_WINDOW = 30L * 60 * 1000;
  private static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
  private final Context context;
  private final SharedPreferences prefs;
  private final DownloadManager downloads;
  private final OkHttpClient http = new OkHttpClient.Builder()
      .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
      .callTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
      .followSslRedirects(false).build();
  public final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "yingxia-updates"));

  public AppUpdates(Context context) {
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
    downloads = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
  }

  public synchronized boolean shouldAutoCheck() {
    long now = System.currentTimeMillis(), last = prefs.getLong("lastCheck", 0);
    if (!prefs.getBoolean(AUTO_CHECK, true) || (now >= last && now - last < CHECK_INTERVAL)) return false;
    // Reserve the interval before starting network work; failure must not loop on every onResume.
    prefs.edit().putLong("lastCheck", now).apply();
    return true;
  }
  public boolean autoCheckEnabled() { return prefs.getBoolean(AUTO_CHECK, true); }
  public void setAutoCheck(boolean enabled) { prefs.edit().putBoolean(AUTO_CHECK, enabled).apply(); }
  public void defer(AppUpdate update) {
    prefs.edit().putInt("deferredVersion", update.versionCode)
        .putLong("deferredUntil", System.currentTimeMillis() + CHECK_INTERVAL).apply();
  }
  public boolean deferred(AppUpdate update) {
    return prefs.getInt("deferredVersion", 0) == update.versionCode
        && System.currentTimeMillis() < prefs.getLong("deferredUntil", 0);
  }

  private AppUpdate saved(String key) {
    try { return new AppUpdate(new JSONObject(prefs.getString(key, ""))); }
    catch (Exception invalid) { return null; }
  }
  public AppUpdate latest() { return saved("latest"); }
  public AppUpdate pending() { return saved("pending"); }
  public AppUpdate ready() {
    AppUpdate update = saved("ready");
    return update != null && update.newerThan(BuildConfig.VERSION_CODE) && readyFile().isFile() ? update : null;
  }
  public File readyFile() { return new File(context.getCacheDir(), "updates/app-update.apk"); }
  public long downloadId() { return prefs.getLong("downloadId", -1); }

  /** Persist before handing the update to Android, since the installer kills this process on success. */
  public void markInstallerOpened(AppUpdate update) {
    if (!prefs.edit().putInt(INSTALL_VERSION, update.versionCode)
        .putLong(INSTALL_STARTED, System.currentTimeMillis()).commit())
      android.util.Log.w("Yingxia.Update", "Unable to persist install handoff");
  }

  public boolean completedInstall() {
    long elapsed = System.currentTimeMillis() - prefs.getLong(INSTALL_STARTED, 0);
    return prefs.getInt(INSTALL_VERSION, 0) == BuildConfig.VERSION_CODE && elapsed >= 0 && elapsed < INSTALL_WINDOW;
  }

  public void clearInstallHandoff() {
    prefs.edit().remove(INSTALL_VERSION).remove(INSTALL_STARTED).apply();
  }

  public synchronized void clearInstalledApk() {
    AppUpdate update = saved("ready");
    if (update != null && !update.newerThan(BuildConfig.VERSION_CODE)) discardReady();
  }

  public synchronized void discardReady() {
    prefs.edit().remove("ready").apply();
    File file = readyFile();
    if (file.exists() && !file.delete()) android.util.Log.w("Yingxia.Update", "Cannot delete cached update APK");
  }

  /** Called on worker only. Fetch the official release asset, not the rate-limited REST API. */
  public AppUpdate check() throws Exception {
    prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
    AppUpdate update = UpdateFeed.fetch(http, BuildConfig.VERSION_NAME);
    if (update == null) { prefs.edit().remove("latest").apply(); return null; }
    if (update.newerThan(BuildConfig.VERSION_CODE)) {
      try { compatible(update); }
      catch (Exception error) { prefs.edit().remove("latest").apply(); throw error; }
    }
    prefs.edit().putString("latest", update.json().toString()).apply();
    return update;
  }

  private void compatibleIdentity(AppUpdate update) throws Exception {
    if (Build.VERSION.SDK_INT < update.minSdk) throw new IOException("新版本不支持此电视的 Android 版本");
    if (!update.certificateSha256.equals(certificate(installed())))
      throw new IOException("线上安装包与当前应用签名不同，无法覆盖安装；请维护者使用原签名发布");
  }
  private void compatible(AppUpdate update) throws Exception {
    compatibleIdentity(update);
    if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("armeabi-v7a"))
      throw new IOException("此更新仅适用于支持 ARM32 的电视");
  }

  public synchronized void download(AppUpdate update) throws Exception {
    if (downloads == null) throw new IOException("此电视没有系统下载服务");
    if (!update.newerThan(BuildConfig.VERSION_CODE)) throw new IOException("当前已经是此版本或更新版本");
    compatible(update);
    if (downloadId() >= 0) throw new IOException("已有更新正在下载，请先完成或取消");
    File directory = context.getExternalFilesDir("updates");
    if (directory == null || (!directory.isDirectory() && !directory.mkdirs()))
      throw new IOException("无法创建更新下载目录");
    File file = new File(directory, "download.apk");
    if (file.exists() && !file.delete()) throw new IOException("无法清理旧的下载文件");
    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
        .setTitle("映匣 TV " + update.versionName).setDescription("电视应用更新，下载后需确认安装")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationInExternalFilesDir(context, "updates", "download.apk")
        .addRequestHeader("User-Agent", "Yingxia-TV/" + BuildConfig.VERSION_NAME);
    long id = downloads.enqueue(request);
    prefs.edit().putLong("downloadId", id).putString("pending", update.json().toString()).apply();
  }

  public static final class Progress {
    public final int state; public final long bytes, total; public final int reason;
    Progress(int state, long bytes, long total, int reason) {
      this.state = state; this.bytes = bytes; this.total = total; this.reason = reason;
    }
  }
  public Progress progress() {
    long id = downloadId();
    if (id < 0 || downloads == null) return null;
    try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
      if (cursor == null || !cursor.moveToFirst()) return null;
      return new Progress(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
          cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
          cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
          cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
    }
  }

  public synchronized void cancelDownload() {
    long id = downloadId();
    if (id >= 0 && downloads != null) downloads.remove(id);
    prefs.edit().remove("downloadId").remove("pending").apply();
  }

  /** Copy first, verify the private copy, then expose only that fixed private file to the installer. */
  public synchronized AppUpdate finishDownload() throws Exception {
    AppUpdate update = pending(); Progress progress = progress();
    if (update == null || progress == null || progress.state != DownloadManager.STATUS_SUCCESSFUL)
      throw new IOException("下载尚未完成");
    File directory = context.getExternalFilesDir("updates");
    if (directory == null) throw new IOException("下载目录不可用");
    File destination = readyFile();
    if (!destination.getParentFile().isDirectory() && !destination.getParentFile().mkdirs())
      throw new IOException("无法创建校验目录");
    prefs.edit().remove("ready").apply();
    try {
      try (InputStream in = new FileInputStream(new File(directory, "download.apk"));
          FileOutputStream out = new FileOutputStream(destination)) {
        byte[] buffer = new byte[32768]; int count; long total = 0;
        while ((count = in.read(buffer)) != -1) {
          total += count;
          if (total > update.size) throw new IOException("安装包大小与更新描述不一致");
          out.write(buffer, 0, count);
        }
        out.getFD().sync();
      }
      verify(update);
      prefs.edit().putString("ready", update.json().toString()).apply();
      cancelDownload();
      return update;
    } catch (Exception error) {
      if (destination.exists() && !destination.delete()) android.util.Log.w("Yingxia.Update", "Cannot delete invalid APK");
      cancelDownload();
      throw error;
    }
  }

  @SuppressWarnings("deprecation")
  private PackageInfo installed() throws PackageManager.NameNotFoundException {
    return context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags());
  }
  private static int signatureFlags() {
    return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
  }
  @SuppressWarnings("deprecation")
  private static String certificate(PackageInfo info) throws Exception {
    Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
        ? info.signingInfo.getApkContentsSigners() : info.signatures;
    if (signatures == null || signatures.length != 1) throw new IOException("无法确认安装包签名");
    return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
  }

  /** Re-run immediately before installation, including after process restart and permission screens. */
  @SuppressWarnings("deprecation")
  public void verify(AppUpdate update) throws Exception {
    compatible(update);
    verifyArchive(update);
  }

  /** Kept separate so real archive checks can also be exercised on ARM64-only test emulators. */
  @SuppressWarnings("deprecation")
  private void verifyArchive(AppUpdate update) throws Exception {
    compatibleIdentity(update);
    File file = readyFile();
    if (!file.isFile() || file.length() != update.size) throw new IOException("安装包大小校验失败，请重新下载");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[32768]; int count;
      while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    if (!update.sha256.equals(hex(digest.digest()))) throw new IOException("安装包 SHA-256 校验失败，请重新下载");
    PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), signatureFlags());
    if (archive == null || !context.getPackageName().equals(archive.packageName)
        || archive.versionCode != update.versionCode || !update.versionName.equals(archive.versionName)
        || !update.newerThan(BuildConfig.VERSION_CODE)) throw new IOException("安装包身份或版本不匹配");
    if (Build.VERSION.SDK_INT >= 24 && archive.applicationInfo != null
        && archive.applicationInfo.minSdkVersion != update.minSdk) throw new IOException("安装包最低系统版本不匹配");
    if (!update.certificateSha256.equals(certificate(archive))) throw new IOException("安装包签名校验失败");
    boolean arm32 = false;
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
      java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (name.startsWith("lib/armeabi-v7a/") && name.endsWith(".so")) arm32 = true;
      }
    }
    if (!arm32) throw new IOException("安装包没有 ARM32 原生库");
  }

  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }
}
