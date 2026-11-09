package com.github.catvod.crawler;

import android.content.Context;
import java.util.*;
import okhttp3.*;

/** CatVod's stable Android Spider ABI. The subscription supplies its implementation. */
public abstract class Spider {
  public String siteKey;
  public com.github.catvod.net.Net net;
  public com.github.catvod.utils.Local local;
  public final com.github.catvod.utils.Js js = new com.github.catvod.utils.Js();
  private final com.github.catvod.utils.Dialog dialog = new com.github.catvod.utils.Dialog();

  public org.json.JSONObject dialog(org.json.JSONObject options) throws Exception {
    return dialog.show(options);
  }

  public org.json.JSONObject dialog(
      org.json.JSONObject options, com.github.catvod.utils.Dialog.Callback callback)
      throws Exception {
    return dialog.show(options, callback);
  }

  public void closeDialog() {
    dialog.close();
  }

  public void closeHostResources() {
    dialog.close();
    js.close();
    if (android.os.Build.VERSION.SDK_INT >= 24 && net != null) net.close();
  }

  public static Dns safeDns() {
    return Dns.SYSTEM;
  }

  public static OkHttpClient client() {
    return com.yingxia.tv.YingxiaApp.get().http.client;
  }

  public android.app.Activity getActivity() {
    return com.yingxia.tv.ui.MainActivity.current();
  }

  public String proxyUrl() {
    return proxyUrl(Collections.emptyMap());
  }

  public String proxyUrl(Map<String, String> params) {
    return proxyUrl(params, true);
  }

  public String proxyUrl(Map<String, String> params, boolean local) {
    android.net.Uri.Builder b =
        android.net.Uri.parse(com.github.catvod.Proxy.getUrl(local))
            .buildUpon()
            .appendQueryParameter("do", "jar")
            .appendQueryParameter("siteKey", siteKey);
    params.forEach(b::appendQueryParameter);
    return b.build().toString();
  }

  public String danmakuUrl(String id) {
    return danmakuUrl(siteKey, id);
  }

  public static String danmakuUrl(String site, String id) {
    return new android.net.Uri.Builder().scheme("spider").authority(site).appendPath(id).toString();
  }

  public AutoCloseable danmaku(String id, java.util.function.Consumer<org.json.JSONObject> emit)
      throws Exception {
    throw new UnsupportedOperationException("No live chat");
  }

  public void init(Context context) throws Exception {}

  public void init(Context context, String extension) throws Exception {
    init(context);
  }

  public String homeContent(boolean filter) throws Exception {
    return "{\"class\":[]}";
  }

  public String homeVideoContent() throws Exception {
    return "{\"list\":[]}";
  }

  public String categoryContent(
      String tid, String page, boolean filter, HashMap<String, String> extend) throws Exception {
    return "{\"list\":[]}";
  }

  public String detailContent(List<String> ids) throws Exception {
    return "{\"list\":[]}";
  }

  public String searchContent(String key, boolean quick) throws Exception {
    return "{\"list\":[]}";
  }

  public String searchContent(String key, boolean quick, String pg) throws Exception {
    return searchContent(key, quick);
  }

  public String playerContent(String flag, String id, List<String> flags) throws Exception {
    return "{}";
  }

  public String liveContent(String url) throws Exception {
    return "";
  }

  public String action(String action) throws Exception {
    return "{}";
  }

  public Object[] proxy(Map<String, String> params) throws Exception {
    return new Object[] {404, "text/plain", new java.io.ByteArrayInputStream(new byte[0])};
  }

  public boolean manualVideoCheck() {
    return false;
  }

  public boolean isVideoFormat(String url) {
    return false;
  }

  public void destroy() {}
}
