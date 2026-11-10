package com.yingxia.tv.core;

import java.io.IOException;
import org.json.JSONArray;

/** GD's shared 50 requests / 5 minutes window, ported from TVCore MusicHealth.admit. */
public final class MusicRequestBudget {
  private MusicRequestBudget() {}

  public static JSONArray admit(JSONArray saved, long now) throws IOException {
    return admit(saved, now, 50, "GD");
  }

  public static JSONArray admit(JSONArray saved, long now, int limit, String label) throws IOException {
    JSONArray recent = new JSONArray();
    long oldest = now;
    for (int i = 0; i < saved.length(); i++) {
      long time = saved.optLong(i);
      if (time > 0 && now - time < 300_000) {
        recent.put(time);
        oldest = Math.min(oldest, time);
      }
    }
    if (recent.length() >= limit)
      throw new IOException(label + " 请求较频繁，请在 " + Math.max(1, (oldest + 300_000 - now + 999) / 1000) + " 秒后重试");
    recent.put(now);
    return recent;
  }
}
