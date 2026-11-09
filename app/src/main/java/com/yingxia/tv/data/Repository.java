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
  private final Context context;
  private final AtomicFile file;
  private JSONObject state;
  public final SharedPreferences settings;

  public Repository(Context c) {
    context = c;
    file = new AtomicFile(new File(c.getFilesDir(), "library.json"));
    settings = c.getSharedPreferences("settings", 0);
    try (InputStream in = file.openRead()) {
      state = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
    } catch (Exception e) {
      state = Json.obj();
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
    List<JSONObject> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JSONObject sub : subscriptions())
      if (sub.optBoolean("enabled", true)) {
        JSONObject config = Json.child(sub, "config");
        for (JSONObject raw : Json.objects(Json.array(config.opt("sites")))) {
          String key = raw.optString("key");
          if (key.isEmpty() || raw.optString("api").trim().isEmpty()) continue;
          String id = sub.optString("id") + "@" + key;
          if (!seen.add(id)) continue;
          JSONObject site = Json.copy(raw);
          try {
            site.put("id", id)
                .put("origin", sub.optString("origin"))
                .put("subscription", sub.optString("name"))
                .put("spider", config.optString("spider"))
                .put("parses", Json.array(config.opt("parses")));
          } catch (Exception ignored) {
          }
          if (!settings.getBoolean("hidden." + id, false)) result.add(site);
        }
      }
    result.sort(
        Comparator.comparing(
            (JSONObject x) -> !settings.getBoolean("pin." + x.optString("id"), false)));
    return result;
  }

  public synchronized List<JSONObject> lives() {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject sub : subscriptions())
      if (sub.optBoolean("enabled", true)) {
        JSONObject config = Json.child(sub, "config");
        for (JSONObject raw : Json.objects(Json.array(config.opt("lives")))) {
          JSONObject live = Json.copy(raw);
          try {
            live.put("origin", sub.optString("origin"))
                .put("id", sub.optString("id") + "@" + raw.optString("name"));
          } catch (Exception ignored) {
          }
          result.add(live);
        }
      }
    return result;
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
    JSONObject item = Json.copy(play);
    try {
      item.put("id", play.optString("historyId", play.optString("id")))
          .put("position", position)
          .put("duration", duration)
          .put("updated", System.currentTimeMillis())
          .put("completed", duration > 0 && position > duration * .95);
    } catch (Exception ignored) {
    }
    upsert("history", item, "id", true, 300);
  }

  public synchronized String export() {
    return Json.obj("format", "yingxia-android-library-v1", "library", Json.copy(state)).toString();
  }

  /** Account credentials stay on the device when sharing a library backup. */
  public synchronized String exportWithoutAccounts() {
    JSONObject library = Json.copy(state);
    library.remove("servers");
    for (String section : Arrays.asList("history", "favorites", "downloads")) {
      JSONArray items = Json.array(library.opt(section));
      for (JSONObject item : Json.objects(items)) {
        item.remove("cloudServer");
        item.remove("header");
        if (item.has("cloudFile")) item.remove("url");
      }
    }
    return Json.obj("format", "yingxia-android-library-v1", "library", library).toString();
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
