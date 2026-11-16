package com.yingxia.tv.ui;

import static com.yingxia.tv.ui.TvStyle.*;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.yingxia.tv.BuildConfig;
import com.yingxia.tv.YingxiaApp;
import com.yingxia.tv.core.AppUpdate;
import com.yingxia.tv.core.Json;
import com.yingxia.tv.data.AppUpdates;

/** Remote-friendly updater. Installation is always delegated to Android's confirmation UI. */
public final class UpdateActivity extends Activity {
  private AppUpdates updates;
  private AppUpdate offered;
  private TextView status, version, action, cancel, notes;
  private ProgressBar progress;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean resumed, busy, waitingPermission, installWhenReady;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    updates = YingxiaApp.get().updates;
    waitingPermission = saved != null && saved.getBoolean("waitingPermission");
    installWhenReady = saved != null && saved.getBoolean("installWhenReady");
    LinearLayout page = vertical(this); pad(page, 40, 32); page.setBackgroundColor(BACKGROUND);
    ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
    page.addView(text(this, "应用更新", 30, TEXT)); gap(page, 16);
    page.addView(text(this, "当前版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）", 16, SECONDARY));
    version = text(this, "检查本项目的正式电视版本", 18, TEXT); gap(page, 16); page.addView(version);
    status = text(this, "", 16, SECONDARY); page.addView(status); gap(page, 20);
    notes = text(this, "", 15, SECONDARY);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(1000); progress.setVisibility(android.view.View.GONE);
    page.addView(progress, new LinearLayout.LayoutParams(-1, dp(this, 12))); gap(page, 20);
    LinearLayout controls = horizontal(this);
    action = button(this, "检查更新", this::act); spaced(controls, action, 12);
    cancel = button(this, "取消下载", () -> { updates.cancelDownload(); installWhenReady = false; busy = false; render(); });
    spaced(controls, cancel, 12);
    spaced(controls, button(this, "返回", this::finish), 12); page.addView(controls); gap(page, 20);
    page.addView(notes); gap(page, 16);
    page.addView(text(this, "从本项目 GitHub Release 下载 ARM32 安装包；校验大小、SHA-256、包名、版本和签名。\n"
        + "下载可在后台继续，完成后回到这里安装。普通应用不能静默更新：系统会要求确认，首次可能需要允许安装未知应用。\n"
        + "更新保留订阅、收藏和历史；签名不一致会拒绝安装，不会引导卸载清除数据。", 14, SECONDARY));
    render();
    if (updates.downloadId() < 0 && updates.ready() == null && !waitingPermission) check();
    action.requestFocus();
  }

  @Override protected void onResume() {
    super.onResume(); resumed = true;
    handler.post(poll);
    if (!waitingPermission && installWhenReady && !busy && updates.ready() != null) {
      installWhenReady = false; install();
    }
    if (waitingPermission) {
      waitingPermission = false;
      if (canInstall()) install();
      else status.setText("尚未允许安装，请允许后再选择安装更新。");
    }
  }
  @Override protected void onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause(); }
  @Override protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("waitingPermission", waitingPermission);
    state.putBoolean("installWhenReady", installWhenReady); super.onSaveInstanceState(state);
  }
  private boolean alive() { return !isFinishing() && !isDestroyed(); }

  private void render() {
    AppUpdate ready = updates.ready(), pending = updates.pending();
    offered = ready != null ? ready : pending != null ? pending : updates.latest();
    version.setText(offered == null ? "检查本项目的正式电视版本" : "线上版本 " + offered.versionName);
    notes.setText(offered == null ? "" : offered.notes);
    boolean downloading = updates.downloadId() >= 0;
    action.setText(ready != null ? "安装更新" : downloading ? "正在下载…"
        : offered != null && offered.newerThan(BuildConfig.VERSION_CODE) ? "下载并更新" : "重新检查");
    action.setEnabled(!busy && !downloading);
    cancel.setVisibility(downloading ? android.view.View.VISIBLE : android.view.View.GONE);
    cancel.setEnabled(!busy);
    progress.setVisibility(downloading ? android.view.View.VISIBLE : android.view.View.GONE);
    if (ready != null) status.setText("安装包已校验，选择安装更新后交由系统确认。");
  }
  private void act() {
    if (busy) return;
    if (updates.ready() != null) { install(); return; }
    if (offered == null || !offered.newerThan(BuildConfig.VERSION_CODE)) { check(); return; }
    try {
      updates.download(offered); installWhenReady = true; status.setText("正在启动下载…"); render();
      handler.removeCallbacks(poll); handler.post(poll);
    } catch (Exception error) { status.setText(Json.message(error)); }
  }
  private void check() {
    busy = true; render(); status.setText("正在检查更新…");
    updates.worker.execute(() -> {
      try {
        AppUpdate update = updates.check();
        runOnUiThread(() -> {
          if (!alive()) return;
          busy = false; render();
          status.setText(update == null ? "线上暂未提供新的应用内更新包。"
              : update.newerThan(BuildConfig.VERSION_CODE) ? "发现新版本，可在应用内下载安装。"
              : "当前已经是最新版本。");
        });
      } catch (Exception error) { fail("检查失败：", error); }
    });
  }
  private final Runnable poll = new Runnable() {
    @Override public void run() {
      if (!resumed || !alive()) return;
      if (!busy && updates.downloadId() >= 0) {
        try {
          AppUpdates.Progress current = updates.progress();
          if (current == null || current.state == DownloadManager.STATUS_FAILED) {
            int reason = current == null ? 0 : current.reason;
            updates.cancelDownload(); render();
            status.setText("下载失败（" + reason + "），请检查网络和存储后重试。");
          } else if (current.state == DownloadManager.STATUS_SUCCESSFUL) {
            busy = true; render(); status.setText("下载完成，正在校验安装包…");
            updates.worker.execute(() -> {
              try {
                updates.finishDownload();
                runOnUiThread(() -> {
                  if (!alive()) return;
                  busy = false; render(); action.requestFocus();
                  if (installWhenReady && resumed) { installWhenReady = false; install(); }
                });
              } catch (Exception error) { fail("校验失败：", error); }
            });
          } else {
            if (offered != null && current.bytes > offered.size) {
              updates.cancelDownload(); render(); status.setText("下载超过预期大小，已停止。");
            } else {
              progress.setIndeterminate(current.total <= 0);
              if (current.total > 0) progress.setProgress((int) Math.min(1000, current.bytes * 1000 / current.total));
              status.setText((current.state == DownloadManager.STATUS_PAUSED ? "等待网络或存储… " : "正在下载 ")
                  + String.format(java.util.Locale.ROOT, "%.1f MB", current.bytes / 1048576.0)
                  + (current.total > 0 ? String.format(java.util.Locale.ROOT, " / %.1f MB", current.total / 1048576.0) : ""));
            }
          }
        } catch (Exception error) {
          updates.cancelDownload(); render(); status.setText("无法读取下载状态：" + Json.message(error));
        }
      }
      if (resumed) handler.postDelayed(this, 1000);
    }
  };

  private boolean canInstall() {
    return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
  }
  private void install() {
    AppUpdate update = updates.ready();
    if (update == null) { render(); status.setText("安装包不存在，请重新下载。"); return; }
    if (!canInstall()) {
      try {
        waitingPermission = true;
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
      } catch (Exception error) {
        waitingPermission = false;
        status.setText("请在电视系统设置中允许映匣安装未知应用，然后返回安装。");
      }
      return;
    }
    busy = true; render(); status.setText("正在确认安装包安全性…");
    updates.worker.execute(() -> {
      try {
        updates.verify(update);
        runOnUiThread(() -> {
          if (!alive()) return;
          busy = false; render();
          if (!resumed) { status.setText("校验完成，返回页面后选择安装更新。"); return; }
          try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", updates.readyFile());
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("应用更新", uri));
            startActivity(intent);
            status.setText("请在系统安装界面确认；如果取消，可再次选择安装更新。");
          } catch (Exception error) { status.setText("无法打开系统安装器：" + Json.message(error)); }
        });
      } catch (Exception error) { fail("无法安装：", error); }
    });
  }
  private void fail(String prefix, Exception error) {
    runOnUiThread(() -> {
      if (!alive()) return;
      busy = false; render(); status.setText(prefix + Json.message(error));
    });
  }
}
