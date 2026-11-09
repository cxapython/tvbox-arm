package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

public final class Fixture extends Spider {
  private String endpoint;

  @Override
  public void init(Context context, String ext) {
    endpoint = ext;
  }

  @Override
  public String homeContent(boolean filter) throws Exception {
    String api = net == null ? "legacy" : net.get(endpoint);
    String script = js.eval("JSON.stringify({emoji:'中文😀',sum:6*7})");
    return new JSONObject()
        .put("api", api)
        .put("script", new JSONObject(script))
        .put("proxy", proxyUrl())
        .toString();
  }

  @Override
  public Object[] proxy(Map<String, String> params) {
    byte[] bytes = "proxy-中文😀".getBytes(StandardCharsets.UTF_8);
    return new Object[] {200, "text/plain; charset=utf-8", new ByteArrayInputStream(bytes)};
  }
}
