package com.yingxia.tv.data;

import com.yingxia.tv.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback stream gateway for CatVod Spider.proxy; never exposed on the LAN. */
public final class PluginProxy {
  private static PluginProxy shared;
  private final ServerSocket server;
  private final ExecutorService io = Executors.newFixedThreadPool(3);

  private PluginProxy() throws IOException {
    server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    io.execute(
        () -> {
          for (; ; )
            try {
              Socket c = server.accept();
              io.execute(() -> serve(c));
            } catch (IOException ignored) {
              break;
            }
        });
  }

  public static synchronized String url() {
    try {
      if (shared == null) shared = new PluginProxy();
      return "http://127.0.0.1:" + shared.server.getLocalPort() + "/proxy";
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void serve(Socket socket) {
    try (Socket client = socket) {
      client.setSoTimeout(15000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
      String first = reader.readLine();
      if (first == null) return;
      URI uri = new URI(first.split(" ")[1]);
      Map<String, String> params = new LinkedHashMap<>();
      String query = uri.getRawQuery();
      if (query != null)
        for (String pair : query.split("&")) {
          String[] kv = pair.split("=", 2);
          if (kv.length == 2)
            params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        String[] h = line.split(":", 2);
        if (h.length == 2 && h[0].equalsIgnoreCase("Range")) params.put("range", h[1].trim());
      }
      Object[] result = YingxiaApp.get().engine.proxy(params.get("siteKey"), params);
      if (result == null || result.length < 3) throw new IOException("Empty proxy response");
      int code = ((Number) result[0]).intValue();
      String mime = String.valueOf(result[1]);
      OutputStream out = client.getOutputStream();
      StringBuilder headers =
          new StringBuilder(
              "HTTP/1.1 " + code + " OK\r\nContent-Type: " + mime + "\r\nConnection: close\r\n");
      if (result.length > 3 && result[3] instanceof Map)
        for (Map.Entry<?, ?> h : ((Map<?, ?>) result[3]).entrySet())
          if (!String.valueOf(h.getValue()).contains("\r")
              && !String.valueOf(h.getValue()).contains("\n"))
            headers.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
      headers.append("\r\n");
      out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
      InputStream stream =
          result[2] instanceof InputStream
              ? (InputStream) result[2]
              : new ByteArrayInputStream(
                  result[2] instanceof byte[]
                      ? (byte[]) result[2]
                      : String.valueOf(result[2]).getBytes(StandardCharsets.UTF_8));
      try (InputStream in = stream) {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      }
      out.flush();
    } catch (Exception ignored) {
    }
  }
}
