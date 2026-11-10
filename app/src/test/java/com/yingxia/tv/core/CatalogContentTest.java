package com.yingxia.tv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CatalogContentTest {
  @Test
  public void repeatedlyEscapedEntitiesAndLayoutLinesAreCleaned() {
    String html =
        "太阳&amp;amp;月亮<br>播放线路1<br>1080P<br>剧情 &amp;amp;lt;3 &amp;amp;nbsp;继续";
    assertEquals("太阳&月亮\n剧情 <3 继续", Catalog.clean(html));
    // A decoded title is text, not markup to be stripped on the next pass.
    assertEquals("A <3 & B", Catalog.name("A &lt;3 &amp;amp; B"));
    assertTrue(Catalog.clean("蓝光线路不是剧情，1080p 画质很好").contains("蓝光线路"));
  }

  @Test
  public void videosUseTheSameSynopsisCleaning() {
    JSONObject response =
        Json.obj(
            "list",
            new JSONArray()
                .put(Json.obj("vod_id", "1", "vod_name", "剧&amp;amp;名", "vod_content", "线路一<br>简介")));
    List<JSONObject> videos = Catalog.videos(response);
    assertEquals("剧&名", videos.get(0).optString("vod_name"));
    assertEquals("简介", videos.get(0).optString("vod_content"));
  }

  @Test
  public void typeOnePosterIdsAndBackfillPreserveExistingFields() throws Exception {
    JSONArray home =
        new JSONArray()
            .put(Json.obj("vod_id", "1", "vod_name", "无图"))
            .put(Json.obj("vod_id", "2", "vod_pic", "existing.jpg"))
            .put(Json.obj("vod_id", "3", "vod_pic", "  "));
    assertEquals(List.of("1", "3"), Catalog.idsMissingPosters(home));
    JSONArray details =
        new JSONArray()
            .put(Json.obj("vod_id", "1", "vod_pic", "one.jpg"))
            .put(Json.obj("vod_id", "2", "vod_pic", "must-not-replace.jpg"))
            .put(Json.obj("vod_id", "3", "vod_pic", "three.jpg"));
    JSONArray filled = Catalog.fillingPosters(home, details);
    assertEquals("one.jpg", filled.getJSONObject(0).optString("vod_pic"));
    assertEquals("无图", filled.getJSONObject(0).optString("vod_name"));
    assertEquals("existing.jpg", filled.getJSONObject(1).optString("vod_pic"));
    assertEquals("three.jpg", filled.getJSONObject(2).optString("vod_pic"));
    assertFalse(home.getJSONObject(0).has("vod_pic"));
  }
}
