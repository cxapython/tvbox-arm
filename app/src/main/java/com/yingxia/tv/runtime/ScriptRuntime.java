package com.yingxia.tv.runtime;

import android.content.*;
import android.util.Log;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.data.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import okhttp3.*;
import org.json.*;

/**
 * One bounded QuickJS instance. Serialized callers may use any worker thread; never the UI thread.
 */
public final class ScriptRuntime implements AutoCloseable {
  static {
    System.loadLibrary("yingxia-script");
  }

  private final Context context;
  private final SharedPreferences preferences;
  private final Http http;
  private final Map<String, String> supplied = new HashMap<>();
  private long handle;
  private final String origin;
  private String entryModule = "";
  private final Map<String, String> pending = new HashMap<>();
  private int nextRequest = 1;

  private native long create();

  private native byte[] module(long ptr, byte[] name, byte[] code);

  private native byte[] evaluate(long ptr, byte[] expression, int seconds);

  private native void dispose(long ptr);

  public ScriptRuntime(Context c, String id, String origin) throws Exception {
    this.context = c;
    this.origin = origin;
    http = YingxiaApp.get().http;
    preferences = c.getSharedPreferences("script." + ConfigCodec.hash(id), 0);
    handle = create();
    if (handle == 0) throw new IOException("无法创建 JavaScript 引擎");
    try {
      load("assets://tvbox/prelude.js");
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  public synchronized void supply(String name, String code) {
    supplied.put(name, code);
  }

  public synchronized void load(String address) throws Exception {
    ensureOpen();
    byte[] error = module(handle, utf8(address), utf8(source(address)));
    if (error != null) throw new IOException(new String(error, StandardCharsets.UTF_8));
  }

  public synchronized Object call(String expression) throws Exception {
    ensureOpen();
    byte[] result = evaluate(handle, utf8(expression), 40);
    if (result == null) throw new IOException("脚本没有返回结果");
    JSONObject envelope = new JSONObject(new String(result, StandardCharsets.UTF_8));
    if (envelope.has("error")) throw new IOException(envelope.optString("error"));
    return envelope.opt("value");
  }

  public synchronized JSONObject object(String expression) throws Exception {
    Object value = call(expression);
    JSONObject o =
        value instanceof JSONObject
            ? (JSONObject) value
            : value instanceof String ? new JSONObject((String) value) : new JSONObject();
    if (o.has("error")) throw new IOException(o.optString("error"));
    return o;
  }

  public synchronized void initSpider(JSONObject site) throws Exception {
    String api = site.optString("api");
    entryModule = api;
    Object ext = site.opt("ext");
    if (ext instanceof String
        && (((String) ext).startsWith("./") || ((String) ext).startsWith("../")))
      ext = ConfigCodec.address((String) ext, origin);
    object("__tvbox.init(" + Json.obj("api", api, "key", site.optString("key"), "ext", ext) + ")");
  }

  public synchronized JSONObject spider(JSONObject params) throws Exception {
    JSONObject result = object("__tvbox.call(" + params + ")");
    return new JSONObject(result.optString("result", "{}"));
  }

  private void ensureOpen() {
    if (handle == 0) throw new IllegalStateException("脚本引擎已关闭");
  }

  @Override
  public synchronized void close() {
    if (handle != 0) {
      dispose(handle);
      handle = 0;
    }
    supplied.clear();
  }

  private String source(String address) throws Exception {
    if (supplied.containsKey(address)) return supplied.get(address);
    String code;
    if (address.startsWith("assets://")) {
      try (InputStream in = context.getAssets().open("scripts/" + address.substring(9))) {
        code = new String(read(in, 4_000_000), StandardCharsets.UTF_8);
      }
    } else code = http.get(address).text();
    // Android jsEvalReturn family returns a spider from an expression, rather than an ES module.
    if (address.equals(entryModule)
        && !address.startsWith("assets://")
        && !code.contains("export ")
        && !code.contains("__JS_SPIDER__"))
      code =
          "export function __jsEvalReturn() { return (0,eval)(" + JSONObject.quote(code) + "); }";
    return code;
  }

  private static byte[] read(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > limit) throw new IOException("资源过大");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  /** Called synchronously by JNI. No secret credentials or source payloads are logged. */
  @SuppressWarnings("unused")
  public byte[] respond(byte[] operation, byte[] argument) {
    String op = new String(operation, StandardCharsets.UTF_8),
        arg = argument == null ? "" : new String(argument, StandardCharsets.UTF_8);
    try {
      String result = respond(op, arg);
      return result == null ? null : utf8(result);
    } catch (Exception e) {
      return utf8("\u0001" + Json.message(e));
    }
  }

  private String respond(String op, String arg) throws Exception {
    switch (op) {
      case "log":
        Log.d("Yingxia.Script", "script message (" + arg.length() + " chars)");
        return null;
      case "resolve":
        String[] p = arg.split("\n", 2);
        String base = p[0], name = p.length > 1 ? p[1] : "";
        if (name.equals("cat")) name = "assets://js/lib/cat.js";
        if (name.startsWith("assets://")
            || name.startsWith("https://")
            || name.startsWith("http://")) return name;
        return ConfigCodec.address(name, base);
      case "module":
        return source(arg);
      case "encode":
        return Base64.getEncoder().encodeToString(utf8(arg));
      case "decode":
        JSONObject decode = new JSONObject(arg);
        return new String(
            Base64.getDecoder().decode(decode.optString("base64")),
            Charset.forName(decode.optString("encoding", "utf-8")));
      case "md5":
        return ConfigCodec.hex(MessageDigest.getInstance("MD5").digest(utf8(arg)));
      case "crypto":
        return Crypto.run(arg);
      case "url":
        return url(new JSONObject(arg)).toString();
      case "local":
        JSONArray a = new JSONArray(arg);
        String key = a.optString(1) + "@" + a.optString(2);
        switch (a.optString(0)) {
          case "get":
            return preferences.getString(key, "");
          case "set":
            preferences.edit().putString(key, a.optString(3)).apply();
            break;
          case "delete":
            preferences.edit().remove(key).apply();
            break;
        }
        return null;
      case "http":
        return network(new JSONObject(arg)).toString();
      case "httpStart":
        {
          String id = "" + (nextRequest++);
          pending.put(id, network(new JSONObject(arg)).toString());
          return id;
        }
      case "httpPoll":
        return pending.remove(arg);
      case "httpCancel":
        pending.remove(arg);
        return null;
      default:
        if (op.startsWith("manga.")) return manga(op.substring(6), arg);
        throw new IllegalArgumentException("不支持的脚本操作: " + op);
    }
  }

  private JSONObject network(JSONObject o) throws Exception {
    String url = o.optString("url");
    if (!url.startsWith("http://") && !url.startsWith("https://"))
      url = ConfigCodec.address(url, origin);
    byte[] body = o.isNull("body") ? null : utf8(o.optString("body"));
    if (!o.isNull("bodyBase64")) body = Base64.getDecoder().decode(o.optString("bodyBase64"));
    Http.Result result =
        http.request(
            url,
            o.optString("method", "GET"),
            Json.headers(o.optJSONObject("headers")),
            body,
            20_000_000,
            o.optBoolean("redirect", true),
            o.optInt("timeout", 20000));
    JSONObject out =
        Json.obj(
            "code",
            result.status,
            "status",
            result.status,
            "headers",
            new JSONObject(result.headers),
            "url",
            result.url);
    if (o.optBoolean("binary")) out.put("base64", Base64.getEncoder().encodeToString(result.bytes));
    else
      out.put(
          "content", new String(result.bytes, Charset.forName(o.optString("encoding", "utf-8"))));
    return out;
  }

  private JSONObject url(JSONObject o) throws Exception {
    String address = o.optString("href"), base = o.optString("base");
    URI u = base.isEmpty() ? new URI(address) : new URI(base).resolve(address);
    String authority = u.getRawAuthority();
    int port = u.getPort();
    String host = u.getHost() == null ? "" : u.getHost();
    String scheme = u.getScheme() == null ? "" : u.getScheme();
    return Json.obj(
        "href",
        u.toString(),
        "origin",
        scheme + "://" + authority,
        "protocol",
        scheme + ":",
        "host",
        host + (port < 0 ? "" : ":" + port),
        "hostname",
        host,
        "port",
        port < 0 ? "" : "" + port,
        "pathname",
        u.getRawPath() == null ? "/" : u.getRawPath(),
        "search",
        u.getRawQuery() == null ? "" : "?" + u.getRawQuery(),
        "hash",
        u.getRawFragment() == null ? "" : "#" + u.getRawFragment(),
        "username",
        "",
        "password",
        "");
  }

  private String manga(String op, String arg) throws Exception {
    JSONObject o = arg.equals("null") ? new JSONObject() : Json.object(arg);
    String key;
    switch (op) {
      case "httpStart":
        {
          JSONObject req = new JSONObject(arg);
          Http.Result r =
              http.request(
                  req.optString("url"),
                  req.optString("method", "GET"),
                  Json.headers(req.optJSONObject("headers")),
                  req.isNull("body") ? null : Base64.getDecoder().decode(req.optString("body")),
                  20_000_000,
                  true,
                  30000);
          String id = "" + (nextRequest++);
          pending.put(
              id,
              Json.obj(
                      "status",
                      r.status,
                      "headers",
                      new JSONObject(r.headers),
                      "base64",
                      Base64.getEncoder().encodeToString(r.bytes),
                      "text",
                      r.text())
                  .toString());
          return id;
        }
      case "httpPoll":
        return pending.remove(arg);
      case "locale":
        return JSONObject.quote("zh_CN");
      case "platform":
        return JSONObject.quote("android");
      case "uuid":
        return JSONObject.quote(UUID.randomUUID().toString());
      case "notice":
        return "null";
      case "encode":
        return JSONObject.quote(
            Base64.getEncoder()
                .encodeToString(
                    o.optString("text").getBytes(Charset.forName(o.optString("encoding", "gbk")))));
      case "data":
        key = "venera." + o.optString("key") + "@";
        if (o.has("get")) {
          String v = preferences.getString(key + o.optString("get"), "null");
          return v;
        }
        if (o.has("set"))
          preferences
              .edit()
              .putString(
                  key + o.optString("set"),
                  new JSONArray()
                      .put(o.opt("value"))
                      .toString()
                      .replaceFirst("^\\[", "")
                      .replaceFirst("\\]$", ""))
              .apply();
        if (o.has("delete")) preferences.edit().remove(key + o.optString("delete")).apply();
        return "null";
      case "cookie":
        HttpUrl cookieUrl = HttpUrl.get(o.optString("url"));
        if (o.optString("function").equals("get")) {
          JSONArray cookies = new JSONArray();
          for (Cookie c : http.client.cookieJar().loadForRequest(cookieUrl))
            cookies.put(
                Json.obj(
                    "name",
                    c.name(),
                    "value",
                    c.value(),
                    "domain",
                    c.domain(),
                    "path",
                    c.path(),
                    "secure",
                    c.secure(),
                    "httpOnly",
                    c.httpOnly()));
          return cookies.toString();
        }
        List<Cookie> cookies = new ArrayList<>();
        for (JSONObject raw : Json.objects(Json.array(o.opt("cookies")))) {
          Cookie c =
              Cookie.parse(
                  cookieUrl,
                  raw.optString("name")
                      + "="
                      + raw.optString("value")
                      + "; Path="
                      + raw.optString("path", "/"));
          if (c != null) cookies.add(c);
        }
        http.client.cookieJar().saveFromResponse(cookieUrl, cookies);
        return "null";
      case "cipher":
        return JSONObject.quote(
            Json.object(
                    Crypto.run(
                        Json.obj(
                                "op",
                                "aes",
                                "mode",
                                o.optString("mode"),
                                "encrypt",
                                o.optBoolean("encrypt"),
                                "padding",
                                false,
                                "key",
                                o.optString("key"),
                                "iv",
                                o.optString("iv"),
                                "data",
                                o.optString("data"))
                            .toString()))
                .optString("data"));
      default:
        throw new IllegalArgumentException("尚未支持漫画操作: " + op);
    }
  }
}
