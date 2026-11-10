package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class LiveChannelsTest {
  @Test
  public void inlineGroupsKeepEveryValidLineInOrderWithSourceHeaders() throws Exception {
    JSONObject source = Json.obj(
        "name", "电视直播",
        "header", Json.obj("X-Source", "yes", "Referer", "https://example.com/"),
        "ua", "Custom Agent",
        "channels", new JSONArray()
            .put(Json.obj("name", "CCTV1", "urls", new JSONArray()
                .put("https://example.com/1a.m3u8")
                .put("  ")
                .put("https://example.com/1b.m3u8")))
            .put(Json.obj("name", "空频道", "urls", new JSONArray().put("").put("   ")))
            .put(Json.obj("name", "CCTV2", "urls", new JSONArray()
                .put("https://example.com/2.m3u8"))));

    List<JSONObject> channels = LiveChannels.fromInline(source);
    assertEquals(2, channels.size());
    JSONObject first = channels.get(0);
    assertEquals("CCTV1", first.optString("name"));
    assertEquals("https://example.com/1a.m3u8", first.optString("url"));
    assertEquals("电视直播", first.optString("group"));
    assertTrue(first.optBoolean("live"));
    assertEquals("线路 1", first.optString("flag"));

    JSONArray episodes = first.optJSONArray("episodes");
    assertNotNull(episodes);
    assertEquals(2, episodes.length());
    assertEquals("https://example.com/1a.m3u8", episodes.getJSONObject(0).optString("url"));
    assertEquals("https://example.com/1b.m3u8", episodes.getJSONObject(1).optString("url"));
    assertEquals("线路1", episodes.getJSONObject(0).optString("name"));
    assertEquals("线路2", episodes.getJSONObject(1).optString("name"));
    assertEquals("线路 1", episodes.getJSONObject(0).optString("flag"));
    assertEquals("线路 2", episodes.getJSONObject(1).optString("flag"));
    assertEquals(first.optString("id") + "|2", episodes.getJSONObject(1).optString("id"));
    assertEquals(first.optString("id"),
        LiveChannels.fromInline(source).get(0).optString("id"));

    for (JSONObject item : List.of(first, episodes.getJSONObject(0), episodes.getJSONObject(1))) {
      JSONObject header = Json.child(item, "header");
      assertEquals("yes", header.optString("X-Source"));
      assertEquals("https://example.com/", header.optString("Referer"));
      assertEquals("Custom Agent", header.optString("User-Agent"));
      assertEquals("电视直播", item.optString("group"));
      assertTrue(item.optBoolean("live"));
    }
  }

  @Test
  public void missingOrEntirelyEmptyInlineChannelsReturnEmpty() {
    assertTrue(LiveChannels.fromInline(new JSONObject()).isEmpty());
    JSONObject source = Json.obj("channels", new JSONArray()
        .put(Json.obj("name", "空", "urls", new JSONArray().put("")))
        .put(Json.obj("name", "没有地址")));
    assertTrue(LiveChannels.fromInline(source).isEmpty());
  }
}
