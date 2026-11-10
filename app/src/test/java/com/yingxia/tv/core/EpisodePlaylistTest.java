package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class EpisodePlaylistTest {
  private static JSONObject episode(String id, String flag) {
    return Json.obj("id", id, "name", id, "flag", flag, "url", "catalog-" + id);
  }

  @Test
  public void nextStaysOnCurrentLineAndUsesEpisodeIdentity() throws Exception {
    JSONArray episodes = new JSONArray()
        .put(episode("one", "a")).put(episode("two", "a")).put(episode("other", "b"));
    JSONObject play = Json.obj("episodes", episodes, "episodeInfo", episode("one", "a"));
    assertEquals("two", EpisodePlaylist.next(play).getString("id"));
    play = Json.obj("episodes", episodes, "episodeInfo", episode("two", "a"));
    assertNull(EpisodePlaylist.next(play));
  }

  @Test
  public void missingCurrentLastEpisodeAndEmptyListHaveNoNext() {
    JSONArray episodes = new JSONArray().put(episode("one", "a"));
    assertNull(EpisodePlaylist.next(Json.obj("episodes", episodes,
        "episodeInfo", episode("missing", "a"))));
    assertNull(EpisodePlaylist.next(Json.obj("episodes", episodes,
        "episodeInfo", episode("one", "a"))));
    assertNull(EpisodePlaylist.next(new JSONObject()));
  }
}
