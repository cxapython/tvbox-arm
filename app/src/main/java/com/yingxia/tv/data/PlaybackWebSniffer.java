package com.yingxia.tv.data;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.yingxia.tv.core.PlaybackResolver;
import com.yingxia.tv.core.SniffRules;
import com.yingxia.tv.ui.MainActivity;
import com.yingxia.tv.ui.PlayerActivity;
import com.yingxia.tv.ui.TvStyle;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Interactive WebView host for TVBox web parsers and pages protected by a human challenge. */
@SuppressWarnings("SetJavaScriptEnabled")
public final class PlaybackWebSniffer implements PlaybackResolver.Sniffer {
  private static final long TIMEOUT_SECONDS = 90;
  private final Activity owner;

  public PlaybackWebSniffer(Activity owner) {
    this.owner = owner;
  }

  /** Uses the foreground TV activity so a parser can ask the viewer to finish a captcha. */
  public static PlaybackWebSniffer foreground() {
    Activity activity = PlayerActivity.current();
    if (activity == null) activity = MainActivity.current();
    return activity == null ? null : new PlaybackWebSniffer(activity);
  }

  @Override
  public PlaybackResolver.Resolved sniff(PlaybackResolver.SniffRequest request) throws Exception {
    if (owner == null || owner.isFinishing()) throw new IOException("没有可显示网页解析器的前台页面");
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean complete = new AtomicBoolean();
    AtomicReference<PlaybackResolver.Resolved> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Dialog> dialog = new AtomicReference<>();
    owner.runOnUiThread(() -> show(request, finished, complete, result, failure, dialog));
    if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      complete.set(true);
      owner.runOnUiThread(() -> {
        Dialog window = dialog.get();
        if (window != null && window.isShowing()) window.dismiss();
      });
      throw new IOException("网页解析超时；如果页面需要验证码，请在网页窗口内完成验证后重试");
    }
    Throwable error = failure.get();
    if (error != null) throw error instanceof Exception ? (Exception) error : new IOException(error);
    PlaybackResolver.Resolved found = result.get();
    if (found == null) throw new IOException("网页未发现可播放地址");
    return found;
  }

  private void show(
      PlaybackResolver.SniffRequest request,
      CountDownLatch finished,
      AtomicBoolean complete,
      AtomicReference<PlaybackResolver.Resolved> result,
      AtomicReference<Throwable> failure,
      AtomicReference<Dialog> dialogRef) {
    Dialog dialog = new Dialog(owner);
    dialogRef.set(dialog);
    FrameLayout box = new FrameLayout(owner);
    box.setBackgroundColor(Color.BLACK);
    WebView web = new WebView(owner);
    TextView status = TvStyle.text(owner, "正在打开网页解析器；如遇验证码，请直接在此页面完成。", 14, TvStyle.TEXT);
    status.setBackgroundColor(0xcc050917);
    status.setGravity(Gravity.CENTER_VERTICAL);
    TvStyle.pad(status, 16, 8);
    FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(-1, -1);
    FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, TvStyle.dp(owner, 48), Gravity.TOP);
    box.addView(web, webParams);
    box.addView(status, statusParams);
    dialog.setTitle("网页解析");
    dialog.setContentView(box);
    dialog.setOnCancelListener(ignored -> finish(finished, complete, failure, new IOException("已取消网页解析")));
    dialog.setOnDismissListener(ignored -> {
      web.stopLoading();
      web.destroy();
    });
    configure(web, request, dialog, status, finished, complete, result, failure);
    dialog.setOnShowListener(ignored -> {
      if (dialog.getWindow() != null) {
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.getWindow().setLayout(-1, -1);
      }
    });
    dialog.show();
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
    Map<String, String> headers = new LinkedHashMap<>(request.headers);
    web.loadUrl(request.url, headers);
  }

  private void configure(
      WebView web,
      PlaybackResolver.SniffRequest request,
      Dialog dialog,
      TextView status,
      CountDownLatch finished,
      AtomicBoolean complete,
      AtomicReference<PlaybackResolver.Resolved> result,
      AtomicReference<Throwable> failure) {
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setUserAgentString(Http.UA);
    if (Build.VERSION.SDK_INT >= 21)
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    CookieManager.getInstance().setAcceptCookie(true);
    if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient() {
      private int followed;
      private String scriptsURL = "";

      @Override
      public void onPageFinished(WebView view, String url) {
        if (!url.equals(scriptsURL)) {
          scriptsURL = url;
          for (String script : SniffRules.pageScripts(url, request.click, request.rules))
            execute(view, script);
          for (String click : SniffRules.pageClicks(url, request.rules)) execute(view, click);
        }
        status.setText("网页解析中；遇到验证码请完成验证，检测到视频后会自动继续播放。\n" + url);
      }

      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest resource) {
        return inspect(resource.getUrl().toString(), resource.getRequestHeaders(), request, dialog,
            status, finished, complete, result, failure, followed++);
      }

      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return inspect(url, Collections.emptyMap(), request, dialog, status, finished, complete,
            result, failure, followed++);
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest errorRequest, android.webkit.WebResourceError error) {
        if (errorRequest.isForMainFrame()) {
          String detail = "网页加载失败，可返回后重试或检查验证码页面。";
          if (Build.VERSION.SDK_INT >= 23) detail += "\n" + error.getDescription();
          status.setText(detail);
        }
      }
    });
  }

  private WebResourceResponse inspect(
      String url,
      Map<String, String> requestHeaders,
      PlaybackResolver.SniffRequest request,
      Dialog dialog,
      TextView status,
      CountDownLatch finished,
      AtomicBoolean complete,
      AtomicReference<PlaybackResolver.Resolved> result,
      AtomicReference<Throwable> failure,
      int followed) {
    SniffRules.Decision decision = SniffRules.decision(url, request.url, request.detect, followed,
        request.ads, request.rules);
    if (decision != SniffRules.Decision.VIDEO || complete.get()) return null;
    Map<String, String> headers = new LinkedHashMap<>(request.headers);
    if (requestHeaders != null) headers.putAll(requestHeaders);
    if (!headers.containsKey("Referer")) headers.put("Referer", request.url);
    if (!headers.containsKey("User-Agent")) headers.put("User-Agent", Http.UA);
    String cookie = CookieManager.getInstance().getCookie(url);
    if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
    PlaybackResolver.Resolved found = new PlaybackResolver.Resolved(url, headers);
    owner.runOnUiThread(() -> finish(finished, complete, result, failure, found, dialog));
    return null;
  }

  private static void execute(WebView web, String script) {
    String code = script == null ? "" : script.trim();
    if (code.isEmpty()) return;
    if (code.startsWith("javascript:")) web.loadUrl(code);
    else web.evaluateJavascript("(function(){" + code + "})()", null);
  }

  private static void finish(
      CountDownLatch finished,
      AtomicBoolean complete,
      AtomicReference<PlaybackResolver.Resolved> result,
      AtomicReference<Throwable> failure,
      PlaybackResolver.Resolved found,
      Dialog dialog) {
    if (!complete.compareAndSet(false, true)) return;
    result.set(found);
    if (dialog.isShowing()) dialog.dismiss();
    finished.countDown();
  }

  private static void finish(
      CountDownLatch finished,
      AtomicBoolean complete,
      AtomicReference<Throwable> failure,
      Throwable error) {
    if (!complete.compareAndSet(false, true)) return;
    failure.set(error);
    finished.countDown();
  }
}
