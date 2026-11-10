package com.yingxia.tv.data;

import android.content.*;
import android.util.AtomicFile;
import com.yingxia.tv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Local, atomic snapshots. An unsuccessful refresh never replaces a working subscription. */
public final class Repository {
  public static final String DEFAULT_SUBSCRIPTION =
      "https://raw.githubusercontent.com/qist/tvbox/master/fty.json";
  private final Context context;
  private final AtomicFile file;
  private JSONObject state;
  private final SourceSupport sourceSupport;
  public final SharedPreferences settings;

  public Repository(Context c) {
    context = c;
    file = new AtomicFile(new File(c.getFilesDir(), "library.json"));
    boolean firstRun = !file.getBaseFile().exists()
        && !new File(c.getFilesDir(), "library.json.bak").exists();
    settings = c.getSharedPreferences("settings", 0);
    try (InputStream index = c.getAssets().open("scripts/sites/index.json");
        InputStream policy = c.getAssets().open("source-support.json")) {
      sourceSupport = new SourceSupport(
          new JSONObject(new String(read(index), StandardCharsets.UTF_8)),
          new JSONObject(new String(read(policy), StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("无法读取源适配清单", error);
    }
    try (InputStream in = file.openRead()) {
      state = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
    } catch (Exception e) {
      state = Json.obj();
    }
    if (firstRun) {
      // A bundled snapshot makes first launch usable without downloading a config file.
      // Never re-add it after deletion, or overwrite an existing user's subscriptions.
      try (InputStream defaults = c.getAssets().open("default-subscription.json")) {
        importConfig(read(defaults), DEFAULT_SUBSCRIPTION, "饭太硬 · 默认订阅");
      } catch (Exception error) {
        throw new IllegalStateException("无法初始化默认订阅", error);
      }
    }
  }

  private static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int n;
    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
    return b.toByteArray();
  }

  public synchronized JSONArray list(String key) {
    return Json.array(state.opt(key));
  }

  public synchronized void save(String key, JSONArray value) {
    try {
      state.put(key, value);
      persist();
    } catch (Exception e) {
      throw new IllegalStateException("无法保存本地资料", e);
    }
  }

  private void persist() throws IOException {
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(state.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(out);
    } catch (IOException e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  public synchronized List<JSONObject> subscriptions() {
    return Json.objects(list("subscriptions"));
  }

  public synchronized void importConfig(byte[] data, String origin, String name) throws Exception {
    JSONObject config = ConfigCodec.subscription(data, origin);
    JSONObject item =
        Json.obj(
            "id",
            ConfigCodec.hash(origin),
            "origin",
            origin,
            "name",
            name == null || name.trim().isEmpty()
                ? new java.net.URI(origin).getHost()
                : name.trim(),
            "config",
            config,
            "updated",
            System.currentTimeMillis(),
            "enabled",
            true);
    upsert("subscriptions", item, "id", false, 100);
  }

  public synchronized List<JSONObject> sites() {
    List<JSONObject> result = SubscriptionMerge.sites(enabledSubscriptions());
    result.removeIf(site -> !isSourceVisible(site));
    result.sort(
        Comparator.comparing(
            (JSONObject x) -> !settings.getBoolean("pin." + x.optString("id"), false)));
    return result;
  }

  public synchronized List<JSONObject> lives() {
    return SubscriptionMerge.lives(enabledSubscriptions());
  }

  public boolean isSourceVisible(JSONObject site) {
    return sourceSupport.reason(site) == null
        && !settings.getBoolean("hidden." + site.optString("id"), false);
  }

  /** Read-only backlog diagnostics; never deletes imported configuration. */
  public synchronized List<JSONObject> deferredSites() {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject site : SubscriptionMerge.sites(enabledSubscriptions())) {
      String reason = sourceSupport.reason(site);
      if (reason != null) result.add(Json.obj("key", site.optString("key"),
          "name", site.optString("name"), "api", site.optString("api"), "reason", reason));
    }
    return result;
  }

  /** Unsupported history/favorites remain intact in the stored library. */
  public synchronized List<JSONObject> visibleLibrary(String key) {
    List<JSONObject> result = Json.objects(list(key));
    result.removeIf(item -> {
      if (item.has("cloudServer") || item.has("cloudFile")) return true;
      JSONObject site = item.optJSONObject("site");
      return site != null && site.length() > 0 && !isSourceVisible(site);
    });
    return result;
  }

  private List<JSONObject> enabledSubscriptions() {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject sub : subscriptions()) if (sub.optBoolean("enabled", true)) out.add(sub);
    return out;
  }

  public synchronized JSONObject site(String id) {
    for (JSONObject s : sites()) if (s.optString("id").equals(id)) return s;
    return new JSONObject();
  }

  public synchronized void upsert(
      String section, JSONObject item, String field, boolean first, int limit) {
    JSONArray out = new JSONArray();
    if (first) out.put(item);
    for (JSONObject o : Json.objects(list(section)))
      if (!o.optString(field).equals(item.optString(field))
          && out.length() < limit - (first ? 0 : 1)) out.put(o);
    if (!first) out.put(item);
    save(section, out);
  }

  public synchronized void remove(String section, String id) {
    JSONArray out = new JSONArray();
    for (JSONObject o : Json.objects(list(section))) if (!o.optString("id").equals(id)) out.put(o);
    save(section, out);
  }

  public synchronized boolean contains(String section, String id) {
    for (JSONObject o : Json.objects(list(section))) if (o.optString("id").equals(id)) return true;
    return false;
  }

  public synchronized boolean favorite(JSONObject item) {
    String id = item.optString("id");
    if (contains("favorites", id)) {
      remove("favorites", id);
      return false;
    }
    upsert("favorites", item, "id", true, 1000);
    return true;
  }

  public synchronized JSONObject history(String id) {
    for (JSONObject item : Json.objects(list("history")))
      if (item.optString("id").equals(id)) return item;
    return new JSONObject();
  }

  public synchronized void progress(JSONObject play, long position, long duration) {
    if (position < 1000) return;
    JSONObject item = PlaybackHistory.entry(play, position, duration, System.currentTimeMillis());
    save("history", PlaybackHistory.upsert(list("history"), item));
  }

  public synchronized String export() {
    return Json.obj("format", "yingxia-android-library-v1", "library", Json.copy(state)).toString();
  }

  /** Account credentials stay on the device when sharing a library backup. */
  public synchronized String exportWithoutAccounts() {
    return Json.obj(
            "format",
            "yingxia-android-library-v1",
            "library",
            LibraryBackup.withoutAccounts(state))
        .toString();
  }

  public synchronized void restore(String text) throws Exception {
    JSONObject o = new JSONObject(text);
    if (!"yingxia-android-library-v1".equals(o.optString("format")))
      throw new IllegalArgumentException("不是映匣 Android 备份文件");
    JSONObject next = o.getJSONObject("library");
    JSONObject old = state;
    state = next;
    try {
      persist();
    } catch (Exception e) {
      state = old;
      throw e;
    }
  }
}
