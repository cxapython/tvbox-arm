package com.yingxia.tv.core;

import java.util.List;
import org.json.JSONObject;

/** Next-episode selection on the current source line, independent of Android player ownership. */
public final class EpisodePlaylist {
  private EpisodePlaylist() {}

  public static JSONObject next(JSONObject play) {
    List<JSONObject> episodes = Json.objects(Json.array(play.opt("episodes")));
    JSONObject current = Json.child(play, "episodeInfo");
    for (int index = 0; index + 1 < episodes.size(); index++) {
      JSONObject episode = episodes.get(index);
      if (episode.optString("id").equals(current.optString("id"))
          && episode.optString("flag").equals(current.optString("flag"))
          && episodes.get(index + 1).optString("flag").equals(current.optString("flag"))) {
        return episodes.get(index + 1);
      }
    }
    return null;
  }
}
