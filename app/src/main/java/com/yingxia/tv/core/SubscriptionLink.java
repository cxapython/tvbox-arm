package com.yingxia.tv.core;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Strict parser for the original {@code yingxia://import?url=&name=} subscription action. */
public final class SubscriptionLink {
  private SubscriptionLink() {}

  public static JSONObject parse(String value) throws Exception {
    URI uri = new URI(value == null ? "" : value);
    if (!"yingxia".equalsIgnoreCase(uri.getScheme()) || !"import".equalsIgnoreCase(uri.getHost()))
      throw new IllegalArgumentException("不是有效的映匣订阅导入链接");
    Map<String, String> query = query(uri.getRawQuery());
    String url = query.getOrDefault("url", "").trim();
    if (url.isEmpty()) throw new IllegalArgumentException("订阅导入链接缺少 url 参数");
    String name = query.getOrDefault("name", "").trim();
    return Json.obj("url", url, "name", name);
  }

  private static Map<String, String> query(String raw) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String part : raw.split("&")) {
      String[] pair = part.split("=", 2);
      String key = URLDecoder.decode(pair[0], "UTF-8");
      String value = pair.length > 1 ? URLDecoder.decode(pair[1], "UTF-8") : "";
      out.put(key, value);
    }
    return out;
  }
}
