package com.yingxia.tv.core;

import java.util.*;
import org.json.*;
import org.jsoup.Jsoup;

public final class Catalog {
  public static String clean(String s) {
    return Jsoup.parse(s == null ? "" : s).text();
  }

  public static List<JSONObject> videos(JSONObject response) {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject v : Json.objects(Json.array(response.opt("list")))) {
      if (v.optString("vod_id").isEmpty()) continue;
      JSONObject item = Json.copy(v);
      try {
        item.put("vod_name", clean(v.optString("vod_name", v.optString("vod_id"))));
        item.put("vod_content", clean(v.optString("vod_content")));
      } catch (JSONException ignored) {
      }
      out.add(item);
    }
    return out;
  }

  public static List<JSONObject> episodes(JSONObject video) {
    List<JSONObject> out = new ArrayList<>();
    String[] flags = video.optString("vod_play_from").split("\\$\\$\\$", -1),
        groups = video.optString("vod_play_url").split("\\$\\$\\$", -1);
    for (int g = 0; g < groups.length; g++) {
      String flag = g < flags.length ? flags[g] : "线路 " + (g + 1);
      String[] entries = groups[g].split("#");
      for (int i = 0; i < entries.length; i++) {
        String line = entries[i].trim();
        if (line.isEmpty()) continue;
        int at = line.indexOf('$');
        String name = at < 0 ? "播放" : clean(line.substring(0, at)),
            url = at < 0 ? line : line.substring(at + 1);
        if (!url.isEmpty())
          out.add(Json.obj("id", g + ":" + i, "name", name, "url", url, "flag", flag));
      }
    }
    return out;
  }

  public static boolean direct(String url) {
    return url.matches(
            "(?i).*\\.(m3u8|mp4|mkv|webm|ts|m4v|mp3|flac|m4a|aac|wav|ogg|mpd)(?:[?#].*)?$")
        || url.startsWith("content:")
        || url.startsWith("file:");
  }

  public static Map<String, String> posterHeaders(JSONObject video) {
    String[] p = video.optString("vod_pic").split("@", 2);
    Map<String, String> h = new LinkedHashMap<>();
    if (p.length == 2) h.putAll(Json.headers(Json.object(p[1])));
    return h;
  }
}
