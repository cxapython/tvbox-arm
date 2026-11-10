package com.yingxia.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class LiteSitesTest {
  private static JSONObject answer(String json) throws Exception {
    return new JSONObject(json);
  }

  private static JSONObject params(Object... pairs) {
    JSONObject params = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) params.put((String) pairs[i], pairs[i + 1]);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return params;
  }

  @Test
  public void playbackRejectsMissingAndEmptyURL() throws Exception {
    assertEquals(
        "no playback URL", LiteSites.unusable(answer("{}"), params("play", "id")));
    assertEquals(
        "no playback URL", LiteSites.unusable(answer("{\"url\":\"\"}"), params("play", "id")));
    assertNull(
        LiteSites.unusable(
            answer("{\"url\":\"https://example.com/a.m3u8\"}"), params("play", "id")));
  }

  @Test
  public void playbackProviderErrorTakesPriorityOverMissingURL() throws Exception {
    assertEquals(
        "login required",
        LiteSites.unusable(
            answer("{\"errMsg\":\" login required \",\"msg\":\"ignored\"}"),
            params("play", "id")));
    assertEquals(
        "msg error", LiteSites.unusable(answer("{\"msg\":\"msg error\"}"), params("play", "id")));
    assertEquals(
        "message error",
        LiteSites.unusable(answer("{\"message\":\"message error\"}"), params("play", "id")));
    assertNull(
        LiteSites.unusable(
            answer("{\"url\":\"https://example.com/a.m3u8\",\"errMsg\":\"ignored\"}"),
            params("play", "id")));
  }

  @Test
  public void playbackRejectsOnlyAnEmptyURLArray() throws Exception {
    assertEquals(
        "no playback URL", LiteSites.unusable(answer("{\"url\":[]}"), params("play", "id")));
    assertNull(
        LiteSites.unusable(
            answer("{\"url\":[\"https://example.com/a.m3u8\"],\"msg\":\"ignored\"}"),
            params("play", "id")));
  }

  @Test
  public void detailsRejectMissingOrEmptyList() throws Exception {
    assertEquals("no details", LiteSites.unusable(answer("{}"), params("ids", "1")));
    assertEquals("no details", LiteSites.unusable(answer("{\"list\":[]}"), params("ids", "1")));
    assertNull(
        LiteSites.unusable(answer("{\"list\":[{\"vod_id\":\"1\"}]}"), params("ids", "1")));
  }

  @Test
  public void stringEncodedArraysDoNotCountAsLists() throws Exception {
    assertEquals(
        "no details",
        LiteSites.unusable(answer("{\"list\":\"[{\\\"vod_id\\\":1}]\"}"), params("ids", "1")));
  }

  @Test
  public void emptySearchAndFilterResultsAreUsable() throws Exception {
    assertNull(LiteSites.unusable(answer("{}"), params("wd", "nothing")));
    assertNull(LiteSites.unusable(answer("{\"list\":[]}"), params("filters", "{}")));
    assertNull(
        LiteSites.unusable(answer("{}"), params("t", "movies", "filters", "{}")));
  }

  @Test
  public void firstCategoryPageRejectsMissingOrEmptyList() throws Exception {
    assertEquals("no videos", LiteSites.unusable(answer("{}"), params("t", "movies")));
    assertEquals(
        "no videos",
        LiteSites.unusable(answer("{\"list\":[]}"), params("t", "movies", "pg", "1")));
    assertNull(LiteSites.unusable(answer("{}"), params("t", "")));
    assertNull(
        LiteSites.unusable(
            answer("{\"list\":[{\"vod_id\":\"1\"}]}"), params("t", "movies")));
  }

  @Test
  public void laterCategoryPagesMayBeEmpty() throws Exception {
    assertNull(
        LiteSites.unusable(answer("{\"list\":[]}"), params("t", "movies", "pg", "2")));
    assertNull(LiteSites.unusable(answer("{}"), params("t", "movies", "pg", "3")));
  }

  @Test
  public void homeRequiresBothClassAndListToBeEmpty() throws Exception {
    assertEquals("no categories", LiteSites.unusable(answer("{}"), params()));
    assertEquals(
        "no categories", LiteSites.unusable(answer("{\"class\":[],\"list\":[]}"), params()));
    assertNull(
        LiteSites.unusable(
            answer("{\"class\":[{\"type_id\":\"1\"}],\"list\":[]}"), params()));
    assertNull(
        LiteSites.unusable(
            answer("{\"class\":[],\"list\":[{\"vod_id\":\"1\"}]}"), params()));
  }
}
