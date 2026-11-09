package com.yingxia.tv.data;

import android.content.Context;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.runtime.ScriptRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class MangaEngine {
  private final Context context;
  private final Map<String, ScriptRuntime> sessions = new LinkedHashMap<>(3, .75f, true);

  public MangaEngine(Context c) {
    context = c;
  }

  private String asset(String path) throws IOException {
    try (InputStream in = context.getAssets().open("scripts/" + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }

  public synchronized JSONObject install(String address) throws Exception {
    Http.Result fetched = YingxiaApp.get().http.get(address);
    String code = fetched.text();
    ScriptRuntime runtime =
        new ScriptRuntime(context, "manga:" + ConfigCodec.hash(address), address);
    try {
      runtime.load("assets://venera/prelude.js");
      runtime.call(asset("venera/init.js") + "\n;true");
      JSONObject info = unwrap(runtime.object("__venera.load(" + JSONObject.quote(code) + ")"));
      String key = info.optString("key");
      if (key.isEmpty()) throw new IOException("漫画源缺少 key");
      File file = new File(context.getFilesDir(), "manga-" + ConfigCodec.hash(key) + ".js");
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(code.getBytes(StandardCharsets.UTF_8));
      }
      JSONObject saved =
          Json.obj("id", key, "name", info.optString("name"), "url", address, "info", info);
      YingxiaApp.get().repository.upsert("mangaSources", saved, "id", false, 100);
      ScriptRuntime old = sessions.put(key, runtime);
      if (old != null) old.close();
      evict();
      return info;
    } catch (Exception e) {
      runtime.close();
      throw e;
    }
  }

  private void evict() {
    while (sessions.size() > 2) {
      String old = sessions.keySet().iterator().next();
      sessions.remove(old).close();
    }
  }

  private static JSONObject unwrap(JSONObject outer) throws Exception {
    Object v = outer.opt("value");
    if (v instanceof JSONObject) return (JSONObject) v;
    throw new IOException("漫画源没有返回数据");
  }

  private synchronized ScriptRuntime session(String key) throws Exception {
    if (sessions.containsKey(key)) return sessions.get(key);
    JSONObject installed = null;
    for (JSONObject s : Json.objects(YingxiaApp.get().repository.list("mangaSources")))
      if (s.optString("id").equals(key)) installed = s;
    if (installed == null) throw new IOException("漫画源已移除");
    File file = new File(context.getFilesDir(), "manga-" + ConfigCodec.hash(key) + ".js");
    byte[] data;
    try (InputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      data = out.toByteArray();
    }
    ScriptRuntime s =
        new ScriptRuntime(
            context,
            "manga:" + ConfigCodec.hash(installed.optString("url")),
            installed.optString("url"));
    try {
      s.load("assets://venera/prelude.js");
      s.call(asset("venera/init.js") + "\n;true");
      s.object("__venera.load(" + JSONObject.quote(new String(data, StandardCharsets.UTF_8)) + ")");
      sessions.put(key, s);
      evict();
      return s;
    } catch (Exception e) {
      s.close();
      throw e;
    }
  }

  public Object call(String key, String method, JSONArray args) throws Exception {
    ScriptRuntime s = session(key);
    JSONObject answer = s.object("__venera.call(" + JSONObject.quote(method) + "," + args + ")");
    return answer.opt("value");
  }

  public JSONObject details(String key, String id) throws Exception {
    Object v = call(key, "comic.loadInfo", new JSONArray().put(id));
    if (!(v instanceof JSONObject)) throw new IOException("漫画详情为空");
    return (JSONObject) v;
  }

  public JSONArray pages(String key, String id, String chapter) throws Exception {
    Object v =
        call(
            key,
            "comic.loadEp",
            new JSONArray().put(id).put(chapter == null ? JSONObject.NULL : chapter));
    JSONArray pages =
        v instanceof JSONObject ? Json.array(((JSONObject) v).opt("images")) : new JSONArray();
    if (pages.length() == 0) throw new IOException("此章节没有页面");
    return pages;
  }

  public byte[] image(String key, String url, String comic, String chapter) throws Exception {
    ScriptRuntime s = session(key);
    JSONObject config =
        unwrap(
            s.object(
                "__venera.imageConfig('page',"
                    + JSONObject.quote(url)
                    + ","
                    + JSONObject.quote(comic)
                    + ","
                    + JSONObject.quote(chapter == null ? "" : chapter)
                    + ")"));
    Http.Result r =
        YingxiaApp.get()
            .http
            .request(
                config.optString("url", url),
                config.optString("method", "GET"),
                Json.headers(config.optJSONObject("headers")),
                config.optString("body").getBytes(StandardCharsets.UTF_8),
                20_000_000,
                true,
                30000);
    Http.check(r);
    byte[] data = r.bytes;
    if (config.optBoolean("onResponse")) {
      JSONObject out =
          s.object(
              "__venera.onResponse("
                  + config.optInt("id")
                  + ","
                  + JSONObject.quote(Base64.getEncoder().encodeToString(data))
                  + ")");
      data = Base64.getDecoder().decode(out.optString("value"));
    }
    s.object("__venera.release(" + config.optInt("id") + ")");
    return data;
  }
}
