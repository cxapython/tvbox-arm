package com.yingxia.tv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
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

  /** Merge only equal names in the same group; keep channels separate from a channel's lines. */
  public static List<JSONObject> groupLines(List<JSONObject> channels) {
    Map<String, JSONObject> grouped = new LinkedHashMap<>();
    Map<String, Set<String>> seen = new LinkedHashMap<>();
    for (JSONObject channel : channels) {
      String key = channel.optString("group") + "\u0000" + channel.optString("name");
      JSONObject merged = grouped.get(key);
      if (merged == null) {
        merged = Json.copy(channel);
        merged.remove("episodes"); merged.remove("liveChannels"); merged.remove("liveLines");
        try { merged.put("liveLines", new JSONArray()); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
        grouped.put(key, merged); seen.put(key, new HashSet<>());
      }
      List<JSONObject> lines = Json.objects(Json.array(channel.opt("liveLines")));
      if (lines.isEmpty()) lines = Json.objects(Json.array(channel.opt("episodes")));
      if (lines.isEmpty()) lines = java.util.Collections.singletonList(channel);
      JSONArray values = merged.optJSONArray("liveLines");
      for (JSONObject raw : lines) {
        String identity = raw.optString("url") + "|" + Json.child(raw, "header").toString();
        if (raw.optString("url").isEmpty() || !seen.get(key).add(identity)) continue;
        JSONObject line = Json.copy(raw);
        line.remove("episodes"); line.remove("liveChannels"); line.remove("liveLines");
        try {
          line.put("name", channel.optString("name")).put("live", true)
              .put("episode", "线路 " + (values.length() + 1));
        } catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
        values.put(line);
      }
    }
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject channel : grouped.values()) if (Json.array(channel.opt("liveLines")).length() > 0) result.add(channel);
    return result;
  }

  /** Each alternate is attempted once per manual channel/line selection: no infinite cycling. */
  public static JSONObject nextLine(JSONObject play, Set<String> failed) {
    failed.add(lineKey(play));
    List<JSONObject> lines = Json.objects(Json.array(play.opt("liveLines")));
    for (JSONObject line : lines) if (!failed.contains(lineKey(line))) return line;
    return null;
  }
  private static String lineKey(JSONObject line) {
    return line.optString("url") + "|" + Json.child(line, "header").toString();
  }

  public static JSONObject withLine(JSONObject channel, JSONObject line) {
    JSONObject result = Json.copy(line);
    try {
      result.put("name", channel.optString("name")).put("live", true)
          .put("liveChannelId", channel.optString("liveChannelId", channel.optString("id")))
          .put("logo", channel.optString("logo")).put("poster", channel.optString("poster", channel.optString("logo")))
          .put("liveLines", channel.opt("liveLines")).put("liveChannels", channel.opt("liveChannels"));
    } catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
    return result;
  }

}
