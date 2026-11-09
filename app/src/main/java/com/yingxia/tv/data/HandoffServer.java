package com.yingxia.tv.data;

import android.content.*;
import android.net.nsd.*;
import android.os.*;
import com.yingxia.tv.*;
import com.yingxia.tv.core.*;
import com.yingxia.tv.ui.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Foreground-only LAN input and the original Play On newline-JSON protocol. */
public final class HandoffServer implements AutoCloseable {
  private final MainActivity activity;
  private final String token;
  private ServerSocket server;
  private NsdManager nsd;
  private NsdManager.RegistrationListener registration;
  private ExecutorService worker;
  private volatile boolean running;

  public HandoffServer(MainActivity activity) {
    this.activity = activity;
    byte[] bytes = new byte[18];
    new SecureRandom().nextBytes(bytes);
    token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public synchronized void start() {
    if (running) return;
    try {
      server = new ServerSocket(0);
      running = true;
      worker = Executors.newFixedThreadPool(3);
      ServerSocket listener = server;
      ExecutorService pool = worker;
      worker.execute(
          () -> {
            while (!listener.isClosed())
              try {
                Socket client = listener.accept();
                try {
                  pool.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                  client.close();
                }
              } catch (IOException | RejectedExecutionException ignored) {
              }
          });
      advertise();
    } catch (IOException ignored) {
    }
  }

  public String localBase() {
    if (server == null || server.isClosed()) return "";
    String ip = ip();
    return ip.isEmpty() ? "" : "http://" + ip + ":" + server.getLocalPort();
  }

  public String url() {
    String base = localBase();
    return base.isEmpty() ? "" : base + "/?token=" + token;
  }

  private String ip() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface n = interfaces.nextElement();
        if (!n.isUp() || n.isLoopback()) continue;
        Enumeration<InetAddress> addresses = n.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress a = addresses.nextElement();
          if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
            return a.getHostAddress();
        }
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private void advertise() {
    try {
      nsd = (NsdManager) activity.getSystemService(Context.NSD_SERVICE);
      NsdServiceInfo info = new NsdServiceInfo();
      info.setServiceName("映匣 · " + Build.MODEL);
      info.setServiceType("_yingxia._tcp.");
      info.setPort(server.getLocalPort());
      info.setAttribute("kind", "tv");
      android.content.SharedPreferences preferences = YingxiaApp.get().repository.settings;
      String installId = preferences.getString("installId", "");
      if (installId.isEmpty()) {
        installId = UUID.randomUUID().toString();
        preferences.edit().putString("installId", installId).apply();
      }
      info.setAttribute("id", installId);
      registration =
          new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) {}

            public void onRegistrationFailed(NsdServiceInfo i, int e) {}

            public void onServiceUnregistered(NsdServiceInfo i) {}

            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
          };
      nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    } catch (Exception ignored) {
    }
  }

  private void serve(Socket socket) {
    Socket client = socket;
    try {
      client.setSoTimeout(12000);
      InputStream in = client.getInputStream();
      String first = line(in, 1_000_000);
      if (first.startsWith("{")) {
        playOn(client, new JSONObject(first));
        return;
      }
      String[] req = first.split(" ");
      if (req.length < 2) return;
      int length = 0;
      for (String header; !(header = line(in, 8192)).isEmpty(); ) {
        if (header.toLowerCase(Locale.ROOT).startsWith("content-length:"))
          length = Integer.parseInt(header.substring(15).trim());
      }
      if (length > 2_000_000) throw new IOException("request too large");
      String body = "";
      if (length > 0) {
        byte[] bytes = new byte[length];
        int read = 0, n;
        while (read < length && (n = in.read(bytes, read, length - read)) > 0) read += n;
        if (read != length) throw new EOFException();
        body = new String(bytes, StandardCharsets.UTF_8);
      }
      URI path = new URI(req[1]);
      Map<String, String> fields = form(path.getRawQuery());
      fields.putAll(form(body));
      if (!token.equals(fields.get("token"))) {
        respond(client, 403, "text/plain", "请使用电视当前显示的二维码。 ");
        return;
      }
      if (req[0].equals("POST") && path.getPath().equals("/import")) {
        String address = ConfigCodec.address(fields.getOrDefault("url", ""), null);
        Http.Result r =
            YingxiaApp.get().http.get(address, Http.params("User-Agent", "okhttp/3.12.13"));
        YingxiaApp.get().repository.importConfig(r.bytes, r.url, fields.get("name"));
        YingxiaApp.get().engine.clear();
        respond(client, 200, "text/html; charset=utf-8", html("订阅已添加", "可以回到电视开始观看。"));
        activity.runOnUiThread(activity::subscriptionImported);
        return;
      }
      if (req[0].equals("GET") && path.getPath().equals("/")) {
        respond(
            client,
            200,
            "text/html; charset=utf-8",
            html(
                "添加订阅",
                "<form method='post' action='/import'><input type='hidden' name='token' value='"
                    + token
                    + "'><label>订阅地址<input type='url' name='url' placeholder='https://…'"
                    + " required></label><label>名称（可选）<input"
                    + " name='name'></label><button>添加到电视</button></form>"));
        return;
      }
      respond(client, 404, "text/plain", "Not found");
    } catch (Exception e) {
      try {
        respond(socket, 400, "text/plain; charset=utf-8", Json.message(e));
      } catch (Exception ignored) {
      }
    } finally {
      try {
        client.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void playOn(Socket client, JSONObject request) throws Exception {
    JSONObject channel = Json.child(request, "channel"),
        history = Json.child(channel, "historyContext");
    JSONObject play;
    if (history.length() > 0) {
      JSONObject source = null;
      for (JSONObject s : YingxiaApp.get().repository.sites())
        if (s.optString("key").equals(history.optString("sourceKey"))
            && s.optString("origin").equals(history.optString("origin"))) {
          source = s;
          break;
        }
      if (source == null) {
        client
            .getOutputStream()
            .write(
                (Json.obj("accepted", false, "message", "电视尚未添加此订阅") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        return;
      }
      String address = "";
      for (JSONObject e : Json.objects(Json.array(Json.child(request, "video").opt("episodes"))))
        if (e.optString("name").equals(history.optString("episodeName"))
            && e.optString("flag").equals(history.optString("episodeFlag"))) {
          address = e.optString("address");
          break;
        }
      if (address.isEmpty()) throw new IOException("没有找到对应集数");
      JSONObject video =
          Json.obj(
              "vod_id",
              history.optString("videoID"),
              "vod_name",
              history.optString("videoName"),
              "vod_pic",
              history.optString("poster"));
      JSONObject episode =
          Json.obj(
              "id",
              history.optString("episodeID"),
              "name",
              history.optString("episodeName"),
              "flag",
              history.optString("episodeFlag"),
              "url",
              address);
      play = YingxiaApp.get().engine.playback(source, video, episode);
    } else {
      String url = ConfigCodec.address(channel.optString("url"), null);
      String host = new URI(url).getHost();
      if (List.of("localhost", "127.0.0.1", "0.0.0.0", "::1").contains(host))
        throw new IOException("此地址只能在发送设备播放");
      play =
          Json.obj(
              "id",
              ConfigCodec.hash(url),
              "name",
              channel.optString("name"),
              "url",
              url,
              "header",
              channel.opt("headers"),
              "live",
              request.isNull("position"));
    }
    play.put("position", (long) (request.optDouble("position", 0) * 1000));
    client
        .getOutputStream()
        .write((Json.obj("accepted", true) + "\n").getBytes(StandardCharsets.UTF_8));
    activity.runOnUiThread(() -> PlayerActivity.open(activity, play));
  }

  private static String line(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1 && b != '\n') {
      if (out.size() > max) throw new IOException("request too large");
      if (b != '\r') out.write(b);
    }
    if (b == -1 && out.size() == 0) throw new EOFException();
    return out.toString("UTF-8");
  }

  private static Map<String, String> form(String query) throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    if (query == null) return values;
    for (String part : query.split("&")) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2)
        values.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
    }
    return values;
  }

  private static void respond(Socket client, int code, String type, String body)
      throws IOException {
    byte[] data = body.getBytes(StandardCharsets.UTF_8);
    OutputStream out = client.getOutputStream();
    out.write(
        ("HTTP/1.1 "
                + code
                + " OK\r\nContent-Type: "
                + type
                + "\r\nContent-Length: "
                + data.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.write(data);
    out.flush();
  }

  private static String html(String title, String content) {
    return "<!doctype html><html lang='zh'><meta charset='utf-8'><meta name='viewport'"
               + " content='width=device-width,initial-scale=1'><title>映匣</title><style>body{background:#050917;color:#eee;font:17px"
               + " system-ui;max-width:480px;margin:60px"
               + " auto;padding:24px}h1{font-size:32px}label{display:block;margin:20px"
               + " 0}input{box-sizing:border-box;display:block;width:100%;padding:14px;margin:8px"
               + " 0;border:1px solid"
               + " #45516b;border-radius:12px;background:#1a1f2d;color:white;font-size:17px}button{background:#eee;color:#050917;padding:14px"
               + " 24px;border:0;border-radius:24px;font-size:17px}</style><h1>映匣 · "
        + title
        + "</h1>"
        + content
        + "</html>";
  }

  @Override
  public synchronized void close() {
    running = false;
    if (server != null)
      try {
        server.close();
      } catch (IOException ignored) {
      }
    if (nsd != null && registration != null)
      try {
        nsd.unregisterService(registration);
      } catch (Exception ignored) {
      }
    registration = null;
    if (worker != null) worker.shutdownNow();
  }
}
