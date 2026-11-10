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
import java.util.concurrent.atomic.*;
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
  private final ScriptHttpTasks httpTasks = new ScriptHttpTasks(ScriptHttpTasks.defaultExecutor());

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

  public Object call(String expression) throws Exception {
    return call(expression, 40);
  }

  public synchronized Object call(String expression, int seconds) throws Exception {
    ensureOpen();
    if (seconds < 1) throw new IllegalArgumentException("脚本超时时间必须大于零");
    byte[] result = evaluate(handle, utf8(expression), seconds);
    if (result == null) throw new IOException("脚本没有返回结果");
    JSONObject envelope = new JSONObject(new String(result, StandardCharsets.UTF_8));
    if (envelope.has("error")) throw new IOException(envelope.optString("error"));
    return envelope.opt("value");
  }

  public JSONObject object(String expression) throws Exception {
    return object(expression, 40);
  }

  public synchronized JSONObject object(String expression, int seconds) throws Exception {
    Object value = call(expression, seconds);
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
    httpTasks.cancelAll();
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
          // Real background execution: the handle returns at once and the answer is polled later.
          JSONObject req = new JSONObject(arg);
          return httpTasks.start(cancel -> network(req, cancel).toString());
        }
      case "httpPoll":
        return httpTasks.poll(arg);
      case "httpCancel":
        httpTasks.cancel(arg);
        return null;
      default:
        throw new IllegalArgumentException("不支持的脚本操作: " + op);
    }
  }

  private JSONObject network(JSONObject o) throws Exception {
    return network(o, null);
  }

  /** {@code cancel}, when non-null, gets a hook interrupting the in-flight okhttp call. */
  private JSONObject network(JSONObject o, ScriptHttpTasks.CancelHandle cancel) throws Exception {
    String url = o.optString("url");
    if (!url.startsWith("http://") && !url.startsWith("https://"))
      url = ConfigCodec.address(url, origin);
    byte[] body = o.isNull("body") ? null : utf8(o.optString("body"));
    if (!o.isNull("bodyBase64")) body = Base64.getDecoder().decode(o.optString("bodyBase64"));
    CancellableCall inFlight = cancellableCall(cancel);
    Http.Result result =
        http.request(
            url,
            o.optString("method", "GET"),
            Json.headers(o.optJSONObject("headers")),
            body,
            20_000_000,
            o.optBoolean("redirect", true),
            o.optInt("timeout", 20000),
            inFlight.reference,
            inFlight.cancelled::get);
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

  /**
   * Publishes okhttp calls without a cancellation race: cancelling before {@code set} still cancels
   * the call as soon as the transport creates it, and cancelling after {@code set} cancels it now.
   */
  private static final class CancellableCall {
    final AtomicBoolean cancelled = new AtomicBoolean();
    final AtomicReference<Call> reference = new AtomicReference<>();
  }

  private static CancellableCall cancellableCall(ScriptHttpTasks.CancelHandle cancel) {
    CancellableCall inFlight = new CancellableCall();
    if (cancel != null)
      cancel.attach(
          () -> {
            inFlight.cancelled.set(true);
            Call call = inFlight.reference.get();
            if (call != null) call.cancel();
          });
    return inFlight;
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

}
