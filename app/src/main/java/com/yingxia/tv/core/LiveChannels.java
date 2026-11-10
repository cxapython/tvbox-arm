package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Builds playable channels from the inline channel groups of a TVBox live source. */
public final class LiveChannels {
  private LiveChannels() {}

  public static List<JSONObject> fromInline(JSONObject source) {
    List<JSONObject> out = new ArrayList<>();
    Map<String, String> headers = Json.headers(source.optJSONObject("header"));
    if (source.has("ua")) headers.put("User-Agent", source.optString("ua"));
    String sourceName = source.optString("name");
    for (JSONObject group : Json.objects(Json.array(source.opt("channels")))) {
      List<String> urls = new ArrayList<>();
      JSONArray values = Json.array(group.opt("urls"));
      for (int index = 0; index < values.length(); index++) {
        Object value = values.opt(index);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) continue;
        urls.add(((String) value).trim());
      }
      if (urls.isEmpty()) continue;
      String name = group.optString("name"), id = name + "|" + urls.get(0);
      JSONArray episodes = new JSONArray();
      for (int index = 0; index < urls.size(); index++) {
        String line = "线路 " + (index + 1);
        episodes.put(
            Json.obj(
                "id", id + "|" + (index + 1),
                "name", "线路" + (index + 1),
                "url", urls.get(index),
                "group", sourceName,
                "header", new JSONObject(headers),
                "live", true,
                "flag", line));
      }
      out.add(
          Json.obj(
              "id", id,
              "name", name,
              "url", urls.get(0),
              "group", sourceName,
              "header", new JSONObject(headers),
              "live", true,
              "flag", "线路 1",
              "episodes", episodes));
    }
    return out;
  }
}
