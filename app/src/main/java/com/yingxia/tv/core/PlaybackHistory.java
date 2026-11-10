package com.yingxia.tv.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Catalog-only playback history entries; signed stream addresses never enter persistent storage. */
public final class PlaybackHistory {
  public static final int LIMIT = 200;
  private static final String[] TRANSIENT = {
      "url", "header", "headers", "subtitles", "subs", "subt", "skips", "danmaku",
      "decryptKey", "qualities", "episodes"
  };

  private PlaybackHistory() {}

  /** Builds a complete entry before Repository persists it, so a JSON failure cannot write a partial one. */
  public static JSONObject entry(JSONObject play, long position, long duration, long now) {
    JSONObject item = Json.copy(play);
    for (String key : TRANSIENT) item.remove(key);
    JSONObject episode = item.optJSONObject("episodeInfo");
    if (episode != null) {
      episode.remove("url");
      episode.remove("header");
    }
    JSONObject video = item.optJSONObject("video");
    if (video != null) {
      video.remove("vod_play_url");
      video.remove("header");
    }
    long savedPosition = duration > 0 ? Math.min(position, duration) : position;
    try {
      item.put("id", play.optString("historyId", play.optString("id")))
          .put("position", savedPosition)
          .put("duration", duration)
          .put("updated", now)
          .put("completed", duration > 0 && savedPosition >= Math.max(0, duration - 1000));
      return item;
    } catch (JSONException error) {
      throw new IllegalStateException("无法更新播放进度", error);
    }
  }

  /** Only a playable catalog identity resumes directly; ordinary favorites still open their details page. */
  public static boolean resumable(JSONObject item) {
    return item.optBoolean("music")
        || Json.child(item, "site").length() > 0
            && !Json.child(item, "video").optString("vod_id").isEmpty()
            && Json.child(item, "episodeInfo").length() > 0;
  }

  /** Most recent first, one row per catalog identity, bounded exactly like the original history. */
  public static JSONArray upsert(JSONArray current, JSONObject item) {
    JSONArray out = new JSONArray();
    out.put(item);
    String id = item.optString("id");
    for (JSONObject old : Json.objects(Json.array(current))) {
      if (!old.optString("id").equals(id) && out.length() < LIMIT) out.put(old);
    }
    return out;
  }
}
