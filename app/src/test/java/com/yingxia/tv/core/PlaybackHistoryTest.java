package com.yingxia.tv.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlaybackHistoryTest {
  @Test
  public void entryKeepsCatalogIdentityButNeverStreamAddressesHeadersOrPlaybackExtras() throws Exception {
    JSONObject play = Json.obj(
        "id", "site@video@episode", "historyId", "site@video", "name", "影片",
        "url", "https://cdn.example/signed.m3u8?token=secret",
        "header", Json.obj("Authorization", "secret"),
        "headers", Json.obj("Cookie", "secret"),
        "subtitles", new JSONArray().put(Json.obj("url", "https://sub.example/a.srt")),
        "subs", new JSONArray(), "subt", new JSONArray(),
        "skips", new JSONArray(), "danmaku", "https://danmaku.example/1",
        "decryptKey", "secret", "qualities", new JSONArray(), "quality", 1,
        "episodes", new JSONArray().put(Json.obj("url", "https://cdn.example/old.m3u8")),
        "site", Json.obj("id", "site"),
        "video", Json.obj("vod_id", "video", "vod_play_url", "第1集$https://cdn.example/old.m3u8"),
        "episodeInfo", Json.obj("id", "episode", "name", "第1集", "flag", "线路",
            "url", "https://cdn.example/signed.mp4?token=secret"));

    JSONObject saved = PlaybackHistory.entry(play, 12000, 180000, 1234);
    assertEquals("site@video", saved.getString("id"));
    assertEquals(12000, saved.getLong("position"));
    assertEquals(1234, saved.getLong("updated"));
    for (String key : new String[] {"url", "header", "headers", "subtitles", "subs", "subt",
        "skips", "danmaku", "decryptKey", "qualities", "episodes"}) {
      assertFalse(key, saved.has(key));
    }
    assertEquals("site", saved.getJSONObject("site").getString("id"));
    assertEquals("episode", saved.getJSONObject("episodeInfo").getString("id"));
    assertFalse(saved.getJSONObject("episodeInfo").has("url"));
    assertFalse(saved.getJSONObject("video").has("vod_play_url"));
    assertEquals(1, saved.getInt("quality"));
    assertFalse(saved.getBoolean("completed"));
  }

  @Test
  public void completionUsesLastSecondAndPositionIsClamped() throws Exception {
    JSONObject play = Json.obj("id", "one");
    assertTrue(PlaybackHistory.entry(play, 179000, 180000, 1).getBoolean("completed"));
    assertFalse(PlaybackHistory.entry(play, 178999, 180000, 1).getBoolean("completed"));
    JSONObject beyond = PlaybackHistory.entry(play, 181000, 180000, 1);
    assertEquals(180000, beyond.getLong("position"));
    assertTrue(beyond.getBoolean("completed"));
    assertFalse(PlaybackHistory.entry(play, 1000, 0, 1).getBoolean("completed"));
  }

  @Test
  public void onlyPlayableHistoryIdentitiesResumeDirectly() {
    assertFalse(PlaybackHistory.resumable(Json.obj("site", Json.obj("id", "site"),
        "video", Json.obj("vod_id", "video"))));
    assertTrue(PlaybackHistory.resumable(Json.obj("site", Json.obj("id", "site"),
        "video", Json.obj("vod_id", "video"), "episodeInfo", Json.obj("name", "第1集"))));
    assertTrue(PlaybackHistory.resumable(Json.obj("music", true)));
    assertFalse(PlaybackHistory.resumable(Json.obj("cloudServer", Json.obj("id", "cloud"))));
    assertFalse(PlaybackHistory.resumable(new JSONObject()));
  }

  @Test
  public void historyIsNewestFirstDeduplicatedAndLimitedToTwoHundred() throws Exception {
    JSONArray existing = new JSONArray();
    for (int index = 0; index < 250; index++) existing.put(Json.obj("id", "id" + index));
    JSONObject updated = Json.obj("id", "id200", "position", 99);
    JSONArray saved = PlaybackHistory.upsert(existing, updated);
    assertEquals(PlaybackHistory.LIMIT, saved.length());
    assertEquals("id200", saved.getJSONObject(0).getString("id"));
    assertEquals(99, saved.getJSONObject(0).getInt("position"));
    int matches = 0;
    for (JSONObject item : Json.objects(saved)) if (item.optString("id").equals("id200")) matches++;
    assertEquals(1, matches);
  }
}
