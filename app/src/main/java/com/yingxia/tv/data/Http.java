package com.yingxia.tv.data;

import android.content.Context;
import com.yingxia.tv.core.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

/** Shared bounded HTTP transport; real TLS validation stays enabled. */
public final class Http {
  public static final String UA =
      "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 Chrome/129.0 Safari/537.36";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  public final OkHttpClient client;

  public Http(Context context) {
    client =
        new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .cache(new Cache(new File(context.getCacheDir(), "http"), 32L * 1024 * 1024))
            .cookieJar(new StoredCookies(context))
            .build();
  }

  public static final class Result {
    public byte[] bytes;
    public String url;
    public int status;
    public Map<String, String> headers;

    public String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }

    public JSONObject json() throws JSONException {
      return new JSONObject(text());
    }
  }

  public Result request(
      String url,
      String method,
      Map<String, String> headers,
      byte[] body,
      int limit,
      boolean redirect,
      int timeout)
      throws IOException {
    return request(url, method, headers, body, limit, redirect, timeout, null);
  }

  /**
   * As {@link #request(String, String, Map, byte[], int, boolean, int)}, also publishing the
   * in-flight okhttp {@link Call} through {@code callRef} so callers may cancel it best-effort.
   */
  public Result request(
      String url,
      String method,
      Map<String, String> headers,
      byte[] body,
      int limit,
      boolean redirect,
      int timeout,
      java.util.concurrent.atomic.AtomicReference<Call> callRef)
      throws IOException {
    return request(url, method, headers, body, limit, redirect, timeout, callRef, null);
  }

  /** Also closes the race where cancellation happens just before okhttp creates its Call. */
  public Result request(
      String url,
      String method,
      Map<String, String> headers,
      byte[] body,
      int limit,
      boolean redirect,
      int timeout,
      java.util.concurrent.atomic.AtomicReference<Call> callRef,
      java.util.function.BooleanSupplier cancelled)
      throws IOException {
    Request.Builder b = new Request.Builder().url(url).header("User-Agent", UA);
    headers.forEach(b::header);
    if (!method.equals("GET") && !method.equals("HEAD"))
      b.method(
          method,
          RequestBody.create(
              body == null ? new byte[0] : body,
              MediaType.parse(headers.getOrDefault("Content-Type", "application/octet-stream"))));
    else b.method(method, null);
    OkHttpClient use =
        client
            .newBuilder()
            .followRedirects(redirect)
            .followSslRedirects(redirect)
            .callTimeout(Math.min(Math.max(timeout, 1000), 120000), TimeUnit.MILLISECONDS)
            .build();
    Call call = use.newCall(b.build());
    if (callRef != null) callRef.set(call);
    if (cancelled != null && cancelled.getAsBoolean()) call.cancel();
    try (Response r = call.execute()) {
      Result out = new Result();
      out.url = r.request().url().toString();
      out.status = r.code();
      out.headers = new LinkedHashMap<>();
      for (String k : r.headers().names()) out.headers.put(k, r.header(k, ""));
      if (r.body() != null && r.body().contentLength() > limit)
        throw new IOException("服务器响应超过 " + (limit / 1024 / 1024) + " MB");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      if (r.body() != null)
        try (InputStream in = r.body().byteStream()) {
          byte[] buf = new byte[16384];
          int n;
          while ((n = in.read(buf)) != -1) {
            if (bytes.size() + n > limit) throw new IOException("服务器响应过大");
            bytes.write(buf, 0, n);
          }
        }
      out.bytes = bytes.toByteArray();
      return out;
    }
  }

  public Result get(String url, Map<String, String> headers) throws IOException {
    Result r = request(url, "GET", headers, null, 20_000_000, true, 30000);
    check(r);
    return r;
  }

  public Result get(String url) throws IOException {
    return get(url, Collections.emptyMap());
  }

  public JSONObject json(String url) throws Exception {
    return get(url).json();
  }

  public JSONObject post(String url, JSONObject body, Map<String, String> headers)
      throws Exception {
    Map<String, String> h = new LinkedHashMap<>(headers);
    h.put("Content-Type", "application/json");
    Result r =
        request(
            url,
            "POST",
            h,
            body.toString().getBytes(StandardCharsets.UTF_8),
            20_000_000,
            true,
            30000);
    check(r);
    return r.json();
  }

  public static void check(Result r) throws IOException {
    if (r.status < 200 || r.status >= 300) throw new IOException("服务器返回 HTTP " + r.status);
  }

  public static String query(String url, Map<String, String> params) {
    HttpUrl parsed = HttpUrl.parse(url);
    if (parsed == null) throw new IllegalArgumentException("无效地址");
    HttpUrl.Builder b = parsed.newBuilder();
    params.forEach(b::setQueryParameter);
    return b.build().toString();
  }

  public static Map<String, String> params(String... values) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) m.put(values[i], values[i + 1]);
    return m;
  }

  private static final class StoredCookies implements CookieJar {
    private final android.content.SharedPreferences prefs;

    StoredCookies(Context c) {
      prefs = c.getSharedPreferences("http_cookies", 0);
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
      Map<String, Cookie> all = new LinkedHashMap<>();
      for (Cookie c : load(url)) all.put(c.name() + "@" + c.path(), c);
      for (Cookie c : cookies) all.put(c.name() + "@" + c.path(), c);
      JSONArray a = new JSONArray();
      for (Cookie c : all.values())
        if (c.expiresAt() > System.currentTimeMillis()) a.put(c.toString());
      prefs.edit().putString(url.host(), a.toString()).apply();
    }

    private List<Cookie> load(HttpUrl url) {
      List<Cookie> out = new ArrayList<>();
      String host = url.host();
      for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
        if (host.equals(e.getKey()) || host.endsWith("." + e.getKey()))
          for (int i = 0; i < Json.array(e.getValue()).length(); i++) {
            Cookie c = Cookie.parse(url, Json.array(e.getValue()).optString(i));
            if (c != null && c.expiresAt() > System.currentTimeMillis()) out.add(c);
          }
      return out;
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
      List<Cookie> out = new ArrayList<>();
      for (Cookie c : load(url)) if (c.matches(url)) out.add(c);
      return out;
    }
  }
}
